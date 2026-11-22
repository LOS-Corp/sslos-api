package com.laundry.booking.service;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Assisted laundry priced by weight (V65). The customer pays the service's fixed part when booking; staff collect the
 * laundry from the locker, weigh it and record the kilograms here; the per-kg price recorded on the booking
 * ({@code scheduled_bookings.price_per_kg}) times the weight is a charge of its own transaction, paid like a booking
 * (wallet first, then payOS). The pickup PIN is given only once it is paid (or nothing is due).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistedWeightService {

    /** Heavier than this is surely a typo. */
    static final BigDecimal MAX_KG = new BigDecimal("100");

    private final JdbcTemplate jdbc;
    private final TransactionRepository transactionRepository;
    private final WalletPaymentService walletPayments;
    private final AutomaticRefundService refunds;
    private final BookingResourceQueries queries;

    /**
     * @param status PENDING (to pay), PAID, or WAIVED (nothing due)
     */
    public record WeightCharge(UUID bookingId, UUID transactionId, BigDecimal weightKg, BigDecimal pricePerKg,
                               BigDecimal amount, String status, OffsetDateTime weighedAt, OffsetDateTime paidAt) {
        public boolean settled() {
            return "PAID".equals(status) || "WAIVED".equals(status);
        }
    }

    private record Booking(UUID id, String status, String mode, String serviceCode, BigDecimal pricePerKg,
                           UUID customerId) {}

    @Transactional(readOnly = true)
    public Optional<WeightCharge> find(UUID bookingId) {
        return query("WHERE c.booking_id = ?", bookingId).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public boolean isWeightCharge(UUID transactionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM booking_weight_charges WHERE transaction_id = ?)", Boolean.class, transactionId));
    }

    /** True when the booking may be picked up as far as money goes: not priced by weight, or its charge is settled. */
    @Transactional(readOnly = true)
    public boolean pickupAllowed(UUID bookingId) {
        Booking booking = booking(bookingId, false);
        if (booking == null || !byWeight(booking)) return true;
        return find(bookingId).map(WeightCharge::settled).orElse(false);
    }

    /** True when the booking is priced by weight: staff must weigh it before putting it back in the locker. */
    @Transactional(readOnly = true)
    public boolean needsWeighing(UUID bookingId) {
        Booking booking = booking(bookingId, false);
        return booking != null && byWeight(booking) && find(bookingId).isEmpty();
    }

    /**
     * Staff weighed the laundry. Re-weighing replaces an unpaid charge (its payment is cancelled, a wallet part given
     * back); a paid charge cannot change.
     */
    @Transactional
    public WeightCharge recordWeight(UUID bookingId, BigDecimal weightKg, UUID staffId) {
        if (weightKg == null || weightKg.signum() <= 0 || weightKg.compareTo(MAX_KG) > 0) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Khối lượng phải lớn hơn 0 và tối đa " + MAX_KG + " kg");
        }
        Booking booking = booking(bookingId, true);
        if (booking == null) throw BusinessException.of(ErrorCode.NOT_FOUND, "Không tìm thấy lượt đặt");
        if (!"ASSISTED_LOCKER".equals(booking.mode())) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Chỉ giặt hộ mới cân đồ để tính tiền");
        }
        if (!byWeight(booking)) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Dịch vụ giặt hộ chưa có đơn giá/kg");
        }
        if (!List.of("CHECKED_IN", "IN_PROGRESS").contains(booking.status())) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Chỉ cân khi đồ đã gửi vào tủ và chưa trả lại (trạng thái " + booking.status() + ")");
        }
        BigDecimal kg = weightKg.setScale(2, RoundingMode.HALF_UP);
        BigDecimal perKg = pricePerKg(booking);
        BigDecimal amount = perKg.multiply(kg).setScale(0, RoundingMode.HALF_UP);

        Optional<WeightCharge> existing = query("WHERE c.booking_id = ? FOR UPDATE OF c", bookingId).stream().findFirst();
        if (existing.isPresent()) {
            if ("PAID".equals(existing.get().status())) {
                throw BusinessException.of(ErrorCode.CONFLICT, "Khách đã thanh toán tiền cân, không cân lại được");
            }
            if (existing.get().transactionId() != null) {
                cancelTransaction(existing.get().transactionId(), "Hoàn phần ví: nhân viên cân lại đồ giặt hộ");
            }
        }
        UUID transactionId = null;
        String status = "WAIVED";
        if (amount.signum() > 0) {
            transactionId = newTransaction(booking.customerId(), amount);
            status = "PENDING";
        }
        jdbc.update("""
                INSERT INTO booking_weight_charges (booking_id, transaction_id, weight_kg, price_per_kg, amount, status,
                                                    weighed_by, weighed_at, paid_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NULL)
                ON CONFLICT (booking_id) DO UPDATE SET transaction_id = EXCLUDED.transaction_id,
                    weight_kg = EXCLUDED.weight_kg, price_per_kg = EXCLUDED.price_per_kg, amount = EXCLUDED.amount,
                    status = EXCLUDED.status, weighed_by = EXCLUDED.weighed_by, weighed_at = NOW(), paid_at = NULL
                """, bookingId, transactionId, kg, perKg, amount, status, staffId);
        log.info("Assisted booking {} weighed {} kg × {} = {} VND by {}", bookingId, kg, perKg, amount, staffId);
        return find(bookingId).orElseThrow();
    }

    /**
     * The transaction the customer pays the charge with. A payment that failed cancelled the previous one, so a fresh
     * transaction is made for the same amount.
     */
    @Transactional
    public UUID payableTransaction(UUID bookingId, UUID customerId) {
        WeightCharge charge = query("WHERE c.booking_id = ? FOR UPDATE OF c", bookingId).stream().findFirst()
                .orElseThrow(() -> BusinessException.of(ErrorCode.CONFLICT, "Đồ chưa được cân"));
        Booking booking = booking(bookingId, false);
        if (booking == null || !customerId.equals(booking.customerId())) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Không tìm thấy lượt đặt");
        }
        if (charge.settled()) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Tiền cân đã được thanh toán");
        }
        String txStatus = jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class,
                charge.transactionId());
        if ("PENDING_PAYMENT".equals(txStatus)) {
            return charge.transactionId();
        }
        UUID fresh = newTransaction(customerId, charge.amount());
        jdbc.update("UPDATE booking_weight_charges SET transaction_id = ? WHERE booking_id = ?", fresh, bookingId);
        return fresh;
    }

    /** The charge was paid (wallet or payOS webhook). Idempotent; a payment for a replaced charge goes to the wallet. */
    @Transactional
    public void confirmPayment(UUID transactionId) {
        Transaction transaction = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Transaction not found"));
        if (transaction.getStatus() == TransactionStatus.PAID) return;
        transaction.setStatus(TransactionStatus.PAID);
        transaction.setPaidAt(OffsetDateTime.now(ZoneOffset.UTC));
        transactionRepository.save(transaction);
        int updated = jdbc.update("""
                UPDATE booking_weight_charges SET status = 'PAID', paid_at = NOW()
                WHERE transaction_id = ? AND status = 'PENDING'
                """, transactionId);
        if (updated == 0) {
            // Staff re-weighed after this payment started: credit what was paid instead of losing it.
            log.warn("Payment for a replaced weight charge {}: credited to the wallet", transactionId);
            walletPayments.reverse(List.of(transactionId), "Hoàn phần ví: tiền cân đã được tính lại");
            Money paid = refunds.refundable(transactionId);
            if (transaction.getCustomerId() != null && paid.isPositive()) {
                refunds.settle(transactionId, transaction.getCustomerId(), paid,
                        WalletEntryType.LATE_PAYMENT_CREDIT, "Thanh toán tiền cân đến sau khi đã tính lại");
            }
            return;
        }
        log.info("Weight charge {} paid", transactionId);
    }

    /** payOS reported a failed payment: the transaction is cancelled, the charge stays due (paid again later). */
    @Transactional
    public void cancelForPaymentFailure(UUID transactionId) {
        cancelTransaction(transactionId, "Hoàn phần ví do thanh toán payOS không thành công");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private UUID newTransaction(UUID customerId, BigDecimal amount) {
        return transactionRepository.saveAndFlush(Transaction.builder()
                .customerId(customerId)
                .serviceType(ServiceType.LAUNDRY_SERVICE)
                .totalAmount(amount)
                .status(TransactionStatus.PENDING_PAYMENT)
                .build()).getId();
    }

    private void cancelTransaction(UUID transactionId, String why) {
        int changed = jdbc.update("""
                UPDATE transactions SET status = 'CANCELLED', updated_at = NOW()
                WHERE id = ? AND status IN ('PENDING_PAYMENT', 'EXPIRED')
                """, transactionId);
        if (changed > 0) walletPayments.reverse(List.of(transactionId), why);
    }

    private static boolean byWeight(Booking booking) {
        return "ASSISTED_LOCKER".equals(booking.mode()) && booking.pricePerKg() != null;
    }

    private BigDecimal pricePerKg(Booking booking) {
        return booking.pricePerKg();
    }

    /** The booking with its per-kg price: recorded when booking, else the service's current one (older bookings). */
    private Booking booking(UUID bookingId, boolean lock) {
        List<Booking> rows = jdbc.query("""
                SELECT b.id, b.status, d.service_mode, b.service_code, b.price_per_kg, t.customer_id
                FROM scheduled_bookings b
                JOIN booking_service_definitions d ON d.code = b.service_code
                JOIN transactions t ON t.id = b.transaction_id
                WHERE b.id = ?""" + (lock ? " FOR UPDATE OF b" : ""),
                (rs, row) -> new Booking(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getObject(6, UUID.class)), bookingId);
        if (rows.isEmpty()) return null;
        Booking b = rows.getFirst();
        if (b.pricePerKg() == null && "ASSISTED_LOCKER".equals(b.mode())) {
            b = new Booking(b.id(), b.status(), b.mode(), b.serviceCode(), queries.pricePerKg(b.serviceCode()), b.customerId());
        }
        return b;
    }

    private List<WeightCharge> query(String where, Object... args) {
        return jdbc.query("""
                SELECT c.booking_id, c.transaction_id, c.weight_kg, c.price_per_kg, c.amount, c.status,
                       c.weighed_at, c.paid_at
                FROM booking_weight_charges c
                """ + where, (rs, row) -> new WeightCharge(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class)), args);
    }
}
