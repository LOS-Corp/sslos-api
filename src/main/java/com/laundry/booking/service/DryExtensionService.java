package com.laundry.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.machine.entity.DeviceCommand;
import com.laundry.machine.entity.DeviceCommandStatus;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.entity.PriceProfile;
import com.laundry.machine.exception.MachineNotFoundException;
import com.laundry.machine.repository.DeviceCommandRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.repository.PriceProfileRepository;
import com.laundry.machine.runtime.MachineRuntimeState;
import com.laundry.machine.runtime.MachineRuntimeStateService;
import com.laundry.machine.service.DeviceCommandFinished;
import com.laundry.machine.service.DeviceCommandRequested;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Extra drying time bought while the customer's dryer runs (V66), priced by the dryer's price profile
 * ({@code extra_minutes} for {@code extra_price}). Each purchase is its own transaction, paid like a booking
 * (wallet first, then payOS) and linked to the booking in {@code dry_extensions}.
 *
 * <p>Once paid, the dryer slot is held {@code minutes} longer and {@code EXTEND_CYCLE} goes to the board: a
 * LaundryOS dryer dries longer, a retrofit board gives the third-party dryer more coins. The customer gets the
 * money back in the wallet when the time cannot be added: the cycle ended, the next booking needs the dryer, or
 * the board refused the command, never received it, or did not answer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DryExtensionService {

    /** Extra time one drying cycle can get in total. */
    public static final int MAX_EXTRA_MINUTES_PER_CYCLE = 60;
    /** Below this the cycle may end before the board gets the command. */
    static final int MIN_REMAINING_SECONDS = 60;
    /** An unpaid purchase lapses after this; the customer can start another. */
    static final int PAYMENT_WINDOW_MINUTES = 10;
    public static final String COMMAND = "EXTEND_CYCLE";

    private static final String OPEN_BOOKINGS = "('CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING')";

    private final JdbcTemplate jdbc;
    private final MachineRepository machines;
    private final PriceProfileRepository profiles;
    private final MachineRuntimeStateService runtime;
    private final TransactionRepository transactions;
    private final DeviceCommandRepository commands;
    private final WalletPaymentService walletPayments;
    private final AutomaticRefundService refunds;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    @Value("${app.machine.command-timeout-seconds:15}")
    private int commandTimeoutSeconds = 15;

    /** What the customer sees on the dryer page. {@code reason} says why nothing is on sale. */
    public record Offer(boolean available, String reason, UUID bookingId, Integer minutes, BigDecimal price,
                        int boughtMinutes, int maxMinutes, Extension pending, Extension latest) {
    }

    /** One purchase. */
    public record Extension(UUID transactionId, UUID bookingId, UUID allocationId, UUID machineId, int minutes,
                            BigDecimal price, Integer vendPulses, String status, String commandId, String failReason,
                            OffsetDateTime createdAt) {
    }

    /** The customer's running dryer slot. */
    private record Slot(UUID allocationId, UUID bookingId, OffsetDateTime until) {
    }

    @Transactional(readOnly = true)
    public boolean isExtension(UUID transactionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM dry_extensions WHERE transaction_id = ?)", Boolean.class, transactionId));
    }

    /** The offer on {@code machineCode} for this customer, with their unpaid and their last purchase. */
    @Transactional(readOnly = true)
    public Offer offer(String machineCode, UUID customerId) {
        Machine machine = machine(machineCode);
        PriceProfile profile = profile(machine);
        Optional<Slot> slot = runningSlot(machine.getId(), customerId);
        Extension pending = slot.flatMap(s -> pending(s.allocationId())).orElse(null);
        Extension latest = slot.flatMap(s -> latest(s.allocationId())).orElse(null);
        int bought = slot.map(s -> boughtMinutes(s.allocationId())).orElse(0);
        String reason = refusal(machine, profile, slot.orElse(null), bought);
        return new Offer(reason == null, reason, slot.map(Slot::bookingId).orElse(null),
                profile != null ? profile.getExtraMinutes() : null, profile != null ? profile.getExtraPrice() : null,
                bought, MAX_EXTRA_MINUTES_PER_CYCLE, pending, latest);
    }

    /**
     * The unpaid purchase of the offered minutes: the existing one when there is one, otherwise a new transaction.
     * Nothing is held yet: the slot is extended once the payment is confirmed.
     */
    @Transactional
    public Extension prepare(String machineCode, UUID customerId) {
        Machine machine = machine(machineCode);
        PriceProfile profile = profile(machine);
        Slot slot = runningSlot(machine.getId(), customerId).orElse(null);
        String reason = refusal(machine, profile, slot, slot == null ? 0 : boughtMinutes(slot.allocationId()));
        if (reason != null) {
            throw BusinessException.of(ErrorCode.CONFLICT, reason);
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "dry-extension:" + slot.allocationId());
        Optional<Extension> existing = pending(slot.allocationId());
        if (existing.isPresent()) {
            Extension open = existing.get();
            boolean samePrice = open.minutes() == profile.getExtraMinutes() && open.price().compareTo(profile.getExtraPrice()) == 0;
            boolean fresh = open.createdAt().isAfter(OffsetDateTime.now().minusMinutes(PAYMENT_WINDOW_MINUTES));
            if (samePrice && fresh) {
                return open;
            }
            cancel(open.transactionId(), "Hoàn phần ví: lượt mua thêm phút sấy cũ đã hết hạn");
        }
        // V67: a retrofit dryer gets its own pulses per purchase; LaundryOS dryers ignore them.
        int pulses = machine.getExtraVendPulses() != null ? machine.getExtraVendPulses() : 1;
        Transaction transaction = transactions.saveAndFlush(Transaction.builder()
                .customerId(customerId)
                .serviceType(ServiceType.SELF_SERVICE)
                .totalAmount(profile.getExtraPrice())
                .status(TransactionStatus.PENDING_PAYMENT)
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(PAYMENT_WINDOW_MINUTES))
                .build());
        jdbc.update("""
                INSERT INTO dry_extensions (transaction_id, booking_id, allocation_id, machine_id, minutes, price, vend_pulses)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, transaction.getId(), slot.bookingId(), slot.allocationId(), machine.getId(),
                profile.getExtraMinutes(), profile.getExtraPrice(), pulses);
        log.info("[{}] Dry extension {} created: booking={} +{} min = {} VND", machineCode, transaction.getId(),
                slot.bookingId(), profile.getExtraMinutes(), profile.getExtraPrice());
        return pending(slot.allocationId()).orElseThrow();
    }

    /**
     * The purchase was paid (wallet or payOS webhook). Idempotent. Extends the slot and sends EXTEND_CYCLE, or gives
     * the money back when the time can no longer be added.
     */
    @Transactional
    public void confirmPayment(UUID transactionId) {
        Transaction transaction = transactions.findByIdForUpdate(transactionId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Transaction not found"));
        Extension extension = locked(transactionId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Dry extension not found"));
        if (!"PENDING".equals(extension.status()) && !"CANCELLED".equals(extension.status())) {
            return;  // already handled
        }
        transaction.setStatus(TransactionStatus.PAID);
        transaction.setPaidAt(OffsetDateTime.now(ZoneOffset.UTC));
        transactions.save(transaction);
        jdbc.update("UPDATE dry_extensions SET paid_at = NOW() WHERE transaction_id = ?", transactionId);
        if ("CANCELLED".equals(extension.status())) {
            log.warn("Payment for cancelled dry extension {}: credited to the wallet", transactionId);
            refund(extension, transaction.getCustomerId(), "Thanh toán mua thêm phút sấy đến sau khi đã huỷ",
                    WalletEntryType.LATE_PAYMENT_CREDIT, "LATE_PAYMENT");
            return;
        }
        Machine machine = machines.findById(extension.machineId()).orElse(null);
        String reason = machine == null ? "Không tìm thấy máy" : stillApplicable(machine, extension);
        if (reason != null) {
            log.warn("[{}] Paid dry extension {} cannot be applied: {}", machine == null ? "?" : machine.getMachineCode(),
                    transactionId, reason);
            refund(extension, transaction.getCustomerId(), "Hoàn tiền mua thêm phút sấy: " + reason,
                    WalletEntryType.REFUND_CREDIT, reason);
            return;
        }
        shiftSlot(extension, extension.minutes());
        String commandId = "EXT-" + transactionId;
        DeviceCommand command = commands.save(DeviceCommand.builder()
                .commandId(commandId)
                .machineId(machine.getId())
                .machineCode(machine.getMachineCode())
                .command(COMMAND)
                .bookingId(extension.bookingId())
                .issuedByUserId(transaction.getCustomerId())
                .status(DeviceCommandStatus.PENDING)
                .timeoutAt(OffsetDateTime.now().plusSeconds(commandTimeoutSeconds))
                .build());
        jdbc.update("UPDATE dry_extensions SET status = 'PAID', command_id = ? WHERE transaction_id = ?",
                commandId, transactionId);
        events.publishEvent(new DeviceCommandRequested(command.getId(),
                new DeviceCommandRequested.Payload(machine.getMachineCode(), payload(machine, extension, commandId))));
        log.info("[{}] Dry extension {} paid: +{} min, {} sent", machine.getMachineCode(), transactionId,
                extension.minutes(), commandId);
    }

    /** payOS reported a failed payment: the purchase is dropped (the wallet part was already given back). */
    @Transactional
    public void cancelForPaymentFailure(UUID transactionId) {
        cancel(transactionId, "Hoàn phần ví do thanh toán payOS không thành công");
    }

    /**
     * The board answered EXTEND_CYCLE (or it could not be sent, or timed out). Accepted: the time is added. Refused or
     * never sent: the slot goes back to its length and the money to the wallet. No answer: the board may have added
     * the time, so the slot stays longer, but the customer is not charged for time that may not exist.
     */
    @EventListener
    @Transactional
    public void onCommandFinished(DeviceCommandFinished finished) {
        if (!COMMAND.equals(finished.command()) || finished.commandId() == null) {
            return;
        }
        List<UUID> ids = jdbc.query("SELECT transaction_id FROM dry_extensions WHERE command_id = ? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), finished.commandId());
        if (ids.isEmpty()) {
            return;
        }
        Extension extension = locked(ids.getFirst()).orElseThrow();
        if (!"PAID".equals(extension.status())) {
            return;
        }
        if (finished.accepted()) {
            jdbc.update("UPDATE dry_extensions SET status = 'APPLIED', applied_at = NOW() WHERE transaction_id = ?",
                    extension.transactionId());
            log.info("Dry extension {} applied by the board", extension.transactionId());
            return;
        }
        if (finished.certainlyNotApplied()) {
            shiftSlot(extension, -extension.minutes());
        }
        UUID customerId = transactions.findById(extension.transactionId()).map(Transaction::getCustomerId).orElse(null);
        String why = "REJECTED".equals(finished.outcome()) ? "máy không nhận thêm thời gian"
                : "TIMEOUT".equals(finished.outcome()) ? "máy không trả lời" : "không gửi được lệnh xuống máy";
        log.warn("Dry extension {} not applied ({}: {}): refunded to the wallet", extension.transactionId(),
                finished.outcome(), finished.reason());
        refund(extension, customerId, "Hoàn tiền mua thêm phút sấy: " + why, WalletEntryType.REFUND_CREDIT,
                finished.outcome() + (finished.reason() != null ? ": " + finished.reason() : ""));
    }

    // ── Rules ──────────────────────────────────────────────────────────────────────────

    /** Why the customer cannot buy extra time now; null when they can. */
    private String refusal(Machine machine, PriceProfile profile, Slot slot, int boughtMinutes) {
        if (machine.getType() != MachineType.DRYER) return "Chỉ máy sấy mới mua thêm thời gian";
        if (profile == null || !profile.sellsExtraTime()) return "Máy này không bán thêm thời gian sấy";
        if (slot == null) return "Bạn không có lượt sấy đang chạy trên máy này";
        if (boughtMinutes + profile.getExtraMinutes() > MAX_EXTRA_MINUTES_PER_CYCLE) {
            return "Mỗi lượt sấy mua thêm tối đa " + MAX_EXTRA_MINUTES_PER_CYCLE + " phút";
        }
        return machineRefusal(machine, slot, profile.getExtraMinutes());
    }

    /** Re-checked once paid: the cycle may have ended or another booking taken the time meanwhile. */
    private String stillApplicable(Machine machine, Extension extension) {
        Optional<Slot> slot = slot(extension.allocationId());
        if (slot.isEmpty()) return "lượt sấy đã kết thúc";
        int bought = boughtMinutes(extension.allocationId());
        if (bought + extension.minutes() > MAX_EXTRA_MINUTES_PER_CYCLE) {
            return "đã mua thêm tối đa " + MAX_EXTRA_MINUTES_PER_CYCLE + " phút";
        }
        return machineRefusal(machine, slot.get(), extension.minutes());
    }

    private String machineRefusal(Machine machine, Slot slot, int minutes) {
        if (!machine.isOnline()) return "Máy đang mất kết nối";
        if (machine.getStatus() != MachineStatus.RUNNING) return "Máy không còn đang sấy";
        Integer remaining = runtime.get(machine.getId()).map(MachineRuntimeState::getRemainingSeconds).orElse(null);
        if (remaining != null && remaining < MIN_REMAINING_SECONDS) return "Máy sắp sấy xong, không kịp thêm thời gian";
        if (nextBookingConflicts(machine.getId(), slot, minutes)) {
            return "Máy đã có khách đặt ngay sau lượt của bạn";
        }
        return null;
    }

    /** Another live booking or hold on the dryer would overlap the longer slot. */
    private boolean nextBookingConflicts(UUID machineId, Slot slot, int minutes) {
        OffsetDateTime from = slot.until();
        OffsetDateTime until = from.plusMinutes(minutes);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM booking_resource_allocations a
                    JOIN scheduled_bookings b ON b.id = a.booking_id
                    WHERE a.active AND a.resource_id = ? AND a.booking_id <> ?
                      AND tstzrange(a.occupied_from, a.occupied_until, '[)') && tstzrange(?, ?, '[)')
                      AND (b.status <> 'HELD' OR b.hold_expires_at > NOW()))
                """, Boolean.class, machineId, slot.bookingId(), Timestamp.from(from.toInstant()),
                Timestamp.from(until.toInstant())));
    }

    // ── Data ──────────────────────────────────────────────────────────────────────────

    private Machine machine(String machineCode) {
        return machines.findByMachineCode(machineCode).orElseThrow(() -> MachineNotFoundException.byCode(machineCode));
    }

    private PriceProfile profile(Machine machine) {
        return machine.getPriceProfileId() == null ? null : profiles.findById(machine.getPriceProfileId()).orElse(null);
    }

    private Optional<Slot> runningSlot(UUID machineId, UUID customerId) {
        return jdbc.query("""
                SELECT a.id, a.booking_id, a.occupied_until
                FROM booking_resource_allocations a
                JOIN scheduled_bookings b ON b.id = a.booking_id
                JOIN transactions t ON t.id = b.transaction_id
                WHERE a.resource_id = ? AND t.customer_id = ? AND a.cycle_state = 'RUNNING'
                  AND b.status IN """ + OPEN_BOOKINGS + """

                ORDER BY a.occupied_from DESC LIMIT 1
                """, (rs, row) -> new Slot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, OffsetDateTime.class)), machineId, customerId).stream().findFirst();
    }

    private Optional<Slot> slot(UUID allocationId) {
        return jdbc.query("""
                SELECT a.id, a.booking_id, a.occupied_until
                FROM booking_resource_allocations a
                JOIN scheduled_bookings b ON b.id = a.booking_id
                WHERE a.id = ? AND a.cycle_state = 'RUNNING' AND b.status IN """ + OPEN_BOOKINGS,
                (rs, row) -> new Slot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, OffsetDateTime.class)), allocationId).stream().findFirst();
    }

    /** Minutes already bought (paid, applied or being applied) for this drying cycle. */
    private int boughtMinutes(UUID allocationId) {
        Integer minutes = jdbc.queryForObject("""
                SELECT COALESCE(sum(minutes), 0) FROM dry_extensions WHERE allocation_id = ? AND status IN ('PAID', 'APPLIED')
                """, Integer.class, allocationId);
        return minutes == null ? 0 : minutes;
    }

    /** The slot (and the booking's end) {@code minutes} longer, or shorter when negative. */
    private void shiftSlot(Extension extension, int minutes) {
        jdbc.update("UPDATE booking_resource_allocations SET occupied_until = occupied_until + make_interval(mins => ?) WHERE id = ?",
                minutes, extension.allocationId());
        jdbc.update("""
                UPDATE scheduled_bookings SET expected_end_time = expected_end_time + make_interval(mins => ?), updated_at = NOW()
                WHERE id = ?
                """, minutes, extension.bookingId());
    }

    private String payload(Machine machine, Extension extension, String commandId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", COMMAND);
        payload.put("command", COMMAND);
        payload.put("commandId", commandId);
        payload.put("machineId", machine.getMachineCode());
        payload.put("bookingId", extension.bookingId().toString());
        // Real seconds: a LaundryOS dryer divides them by its program's demo speed itself.
        payload.put("extraSeconds", extension.minutes() * 60);
        if (extension.vendPulses() != null) payload.put("vendPulses", extension.vendPulses());
        payload.put("issuedAt", OffsetDateTime.now().toString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + COMMAND, e);
        }
    }

    private void cancel(UUID transactionId, String why) {
        int changed = jdbc.update("UPDATE dry_extensions SET status = 'CANCELLED' WHERE transaction_id = ? AND status = 'PENDING'",
                transactionId);
        if (changed == 0) {
            return;
        }
        jdbc.update("""
                UPDATE transactions SET status = 'CANCELLED', updated_at = NOW()
                WHERE id = ? AND status IN ('PENDING_PAYMENT', 'EXPIRED')
                """, transactionId);
        walletPayments.reverse(List.of(transactionId), why);
    }

    /** Everything paid for this purchase back to the wallet. */
    private void refund(Extension extension, UUID customerId, String why, WalletEntryType type, String failReason) {
        jdbc.update("UPDATE dry_extensions SET status = 'REFUNDED', fail_reason = ? WHERE transaction_id = ?",
                failReason.length() > 200 ? failReason.substring(0, 200) : failReason, extension.transactionId());
        walletPayments.reverse(List.of(extension.transactionId()), why);
        Money paid = refunds.refundable(extension.transactionId());
        if (customerId != null && paid.isPositive()) {
            refunds.settle(extension.transactionId(), customerId, paid, type, why);
        }
    }

    private Optional<Extension> pending(UUID allocationId) {
        return find("WHERE allocation_id = ? AND status = 'PENDING'", allocationId).stream().findFirst();
    }

    private Optional<Extension> latest(UUID allocationId) {
        return find("WHERE allocation_id = ? AND status <> 'PENDING' ORDER BY created_at DESC LIMIT 1", allocationId)
                .stream().findFirst();
    }

    private Optional<Extension> locked(UUID transactionId) {
        return find("WHERE transaction_id = ? FOR UPDATE", transactionId).stream().findFirst();
    }

    private List<Extension> find(String where, Object... args) {
        return jdbc.query("""
                SELECT transaction_id, booking_id, allocation_id, machine_id, minutes, price, vend_pulses, status,
                       command_id, fail_reason, created_at
                FROM dry_extensions
                """ + where, (rs, row) -> new Extension(rs.getObject("transaction_id", UUID.class),
                rs.getObject("booking_id", UUID.class), rs.getObject("allocation_id", UUID.class),
                rs.getObject("machine_id", UUID.class), rs.getInt("minutes"), rs.getBigDecimal("price"),
                rs.getObject("vend_pulses", Integer.class), rs.getString("status"), rs.getString("command_id"),
                rs.getString("fail_reason"), rs.getObject("created_at", OffsetDateTime.class)), args);
    }
}
