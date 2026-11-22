package com.laundry.booking.service;

import com.laundry.booking.domain.LoadPolicy;
import com.laundry.booking.event.LoadTopUpPaid;
import com.laundry.payment.service.WalletPaymentService;
import com.laundry.refund.service.AutomaticRefundService;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.money.Money;
import com.laundry.transaction.entity.ServiceType;
import com.laundry.transaction.entity.Transaction;
import com.laundry.transaction.entity.TransactionStatus;
import com.laundry.transaction.repository.TransactionRepository;
import com.laundry.wallet.entity.WalletEntryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Extra kilograms paid at the washer (V61, {@link LoadPolicy}). Each top-up is its own transaction, paid like a
 * booking (wallet first, then payOS), linked to the booking in {@code booking_load_topups}. Once paid, the
 * booking's paid kilograms ({@code declared_load_kg}) grow by the extra ones and {@link LoadTopUpPaid} lets
 * the machine unlock START.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoadTopUpService {

    private final JdbcTemplate jdbc;
    private final TransactionRepository transactionRepository;
    private final WalletPaymentService walletPayments;
    private final AutomaticRefundService refunds;
    private final BookingResourceQueries queries;
    private final ApplicationEventPublisher events;

    /** A top-up as the customer sees it. */
    public record TopUp(UUID transactionId, UUID bookingId, UUID authorizationId, int extraKg,
                        BigDecimal pricePerKg, BigDecimal amount, String status) {
    }

    /** What weighing needs to know about a booking. */
    public record BookingLoad(BigDecimal paidKg, BigDecimal pricePerKg) {
    }

    @Transactional(readOnly = true)
    public boolean isTopUp(UUID transactionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM booking_load_topups WHERE transaction_id = ?)",
                Boolean.class, transactionId));
    }

    /**
     * Kilograms paid for and the per-kg price of the booking (both null when not priced by weight): the price per kg
     * of the washer's price profile recorded at booking time (V64), else the service's.
     */
    @Transactional(readOnly = true)
    public BookingLoad bookingLoad(UUID bookingId) {
        return jdbc.query("SELECT declared_load_kg, service_code, price_per_kg FROM scheduled_bookings WHERE id = ?",
                        (rs, row) -> new BookingLoad(rs.getBigDecimal(1), rs.getBigDecimal(3) != null
                                ? rs.getBigDecimal(3) : queries.pricePerKg(rs.getString(2))),
                        bookingId).stream()
                .findFirst().orElse(new BookingLoad(null, null));
    }

    /** The unpaid top-up of an authorization, if any. */
    @Transactional(readOnly = true)
    public Optional<TopUp> pending(UUID authorizationId) {
        return find("WHERE u.authorization_id = ? AND u.status = 'PENDING'", authorizationId).stream().findFirst();
    }

    /**
     * The unpaid top-up for {@code extraKg} more kilograms: the existing one when it is for the same weight,
     * otherwise a new one (an older one for another reading is cancelled, its wallet part given back).
     */
    @Transactional
    public TopUp prepare(UUID bookingId, UUID authorizationId, UUID customerId, int extraKg, OffsetDateTime expiresAt) {
        if (extraKg <= 0) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Nothing extra to pay for this weight");
        }
        BookingLoad load = bookingLoad(bookingId);
        if (load.pricePerKg() == null) {
            throw BusinessException.of(ErrorCode.CONFLICT, "This booking is not priced by weight");
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "load-topup:" + authorizationId);
        Optional<TopUp> existing = pending(authorizationId);
        if (existing.isPresent()) {
            if (existing.get().extraKg() == extraKg) {
                return existing.get();
            }
            cancel(existing.get().transactionId(), "Hoàn phần ví: khách cân lại đồ, số kg trả thêm đã đổi");
        }
        BigDecimal amount = load.pricePerKg().multiply(BigDecimal.valueOf(extraKg));
        Transaction transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .customerId(customerId)
                .serviceType(ServiceType.SELF_SERVICE)
                .totalAmount(amount)
                .status(TransactionStatus.PENDING_PAYMENT)
                .expiresAt(expiresAt)
                .build());
        jdbc.update("""
                INSERT INTO booking_load_topups (transaction_id, booking_id, authorization_id, extra_kg, price_per_kg)
                VALUES (?, ?, ?, ?, ?)
                """, transaction.getId(), bookingId, authorizationId, extraKg, load.pricePerKg());
        log.info("Load top-up {} created: booking={} +{} kg = {} VND", transaction.getId(), bookingId, extraKg, amount);
        return new TopUp(transaction.getId(), bookingId, authorizationId, extraKg, load.pricePerKg(), amount, "PENDING");
    }

    /**
     * The top-up was paid (wallet or payOS webhook). Idempotent. A payment for a top-up that was already
     * cancelled (the customer re-weighed) is credited to the wallet instead of being lost.
     */
    @Transactional
    public void confirmPayment(UUID transactionId) {
        Transaction transaction = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Transaction not found"));
        List<TopUp> rows = find("WHERE u.transaction_id = ? FOR UPDATE OF u", transactionId);
        if (rows.isEmpty()) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Load top-up not found");
        }
        TopUp topUp = rows.getFirst();
        if ("PAID".equals(topUp.status())) {
            return;
        }
        transaction.setStatus(TransactionStatus.PAID);
        transaction.setPaidAt(OffsetDateTime.now(ZoneOffset.UTC));
        transactionRepository.save(transaction);
        if (!"PENDING".equals(topUp.status())) {
            log.warn("Payment for cancelled load top-up {}: credited to the wallet", transactionId);
            walletPayments.reverse(List.of(transactionId), "Hoàn phần ví: phần kg trả thêm đã huỷ");
            Money paid = refunds.refundable(transactionId);
            if (transaction.getCustomerId() != null && paid.isPositive()) {
                refunds.settle(transactionId, transaction.getCustomerId(), paid,
                        WalletEntryType.LATE_PAYMENT_CREDIT, "Thanh toán thêm kg đến sau khi đã huỷ");
            }
            return;
        }
        jdbc.update("UPDATE booking_load_topups SET status = 'PAID', paid_at = NOW() WHERE transaction_id = ?",
                transactionId);
        jdbc.update("""
                UPDATE scheduled_bookings SET declared_load_kg = COALESCE(declared_load_kg, 0) + ?, updated_at = NOW()
                WHERE id = ?
                """, topUp.extraKg(), topUp.bookingId());
        log.info("Load top-up {} paid: booking={} +{} kg", transactionId, topUp.bookingId(), topUp.extraKg());
        events.publishEvent(new LoadTopUpPaid(topUp.bookingId(), topUp.authorizationId()));
    }

    /** payOS reported a failed payment: the top-up is dropped (the wallet part was already given back). */
    @Transactional
    public void cancelForPaymentFailure(UUID transactionId) {
        cancel(transactionId, "Hoàn phần ví do thanh toán payOS không thành công");
    }

    private void cancel(UUID transactionId, String why) {
        int changed = jdbc.update("""
                UPDATE booking_load_topups SET status = 'CANCELLED' WHERE transaction_id = ? AND status = 'PENDING'
                """, transactionId);
        if (changed == 0) {
            return;
        }
        jdbc.update("""
                UPDATE transactions SET status = 'CANCELLED', updated_at = NOW()
                WHERE id = ? AND status IN ('PENDING_PAYMENT', 'EXPIRED')
                """, transactionId);
        walletPayments.reverse(List.of(transactionId), why);
    }

    private List<TopUp> find(String where, Object... args) {
        return jdbc.query("""
                SELECT u.transaction_id, u.booking_id, u.authorization_id, u.extra_kg, u.price_per_kg, u.status,
                       t.total_amount
                FROM booking_load_topups u JOIN transactions t ON t.id = u.transaction_id
                """ + where, (rs, row) -> new TopUp(rs.getObject("transaction_id", UUID.class),
                rs.getObject("booking_id", UUID.class), rs.getObject("authorization_id", UUID.class),
                rs.getInt("extra_kg"), rs.getBigDecimal("price_per_kg"), rs.getBigDecimal("total_amount"),
                rs.getString("status")), args);
    }
}
