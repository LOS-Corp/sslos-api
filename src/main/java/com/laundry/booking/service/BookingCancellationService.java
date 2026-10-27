package com.laundry.booking.service;

import com.laundry.audit.service.AuditAction;
import com.laundry.audit.service.AuditService;
import com.laundry.audit.service.AuditService.AuditEntry;
import com.laundry.booking.event.BookingReleased;
import com.laundry.config.BookingProperties;
import com.laundry.refund.domain.RefundPolicy;
import com.laundry.refund.domain.RefundPolicy.CancellationCause;
import com.laundry.refund.domain.RefundPolicy.RefundDecision;
import com.laundry.refund.repository.PolicyViolationRepository;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.money.Money;
import com.laundry.shared.security.CurrentUser;
import com.laundry.wallet.entity.WalletEntryType;
import com.laundry.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * UC-08 Cancel Booking and Refund. A HELD booking is simply released. A CONFIRMED booking that has not
 * started is refunded by {@link RefundPolicy} (BR-11/12): the booking is cancelled, its allocations are
 * freed (V44 trigger), the violation is recorded and the refund is credited to the wallet — all in one
 * transaction (FR-WAL-02, SC-29).
 */
@Service
@RequiredArgsConstructor
public class BookingCancellationService {

    private final JdbcTemplate jdbc;
    private final ScheduledBookingService bookings;
    private final PolicyViolationRepository violations;
    private final WalletService wallet;
    private final AuditService audit;
    private final Clock clock;
    private final BookingProperties props;
    private final ApplicationEventPublisher events;


    /** MSG17: what the customer would get back if they cancelled now. */
    @Transactional(readOnly = true)
    public CancellationPreview preview(UUID customerId, UUID bookingId) {
        PaidBooking booking = paidBooking(customerId, bookingId, false);
        return toPreview(booking, decide(booking, clock.instant()));
    }

    @Transactional
    public CancellationPreview cancel(CurrentUser customer, UUID bookingId) {
        String status = jdbc.query("""
                SELECT sb.status FROM scheduled_bookings sb JOIN transactions t ON t.id = sb.transaction_id
                WHERE sb.id = ? AND t.customer_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, bookingId, customer.id());
        if (status == null) {
            throw BusinessException.notFound("Booking not found");
        }
        if ("HELD".equals(status)) {
            bookings.cancelBooking(customer.id(), bookingId);
            return new CancellationPreview(bookingId, 0, 0, 100, null, null, "MSG17");
        }

        Instant now = clock.instant();
        PaidBooking booking = paidBooking(customer.id(), bookingId, true);
        RefundDecision decision = decide(booking, now);

        jdbc.update("""
                UPDATE scheduled_bookings SET status = 'CANCELLED', updated_at = NOW(),
                    reconciliation_reason = 'Cancelled by customer'
                WHERE id = ?
                """, bookingId);
        // Any START already unlocked for this booking is revoked once the cancellation commits.
        events.publishEvent(new BookingReleased(bookingId, "BOOKING_CANCELLED"));
        if (decision.recordsViolation()) {
            violations.record(customer.id(), bookingId, decision.violation(), now);
        }
        if (decision.amount().isPositive()) {
            wallet.credit(customer.id(), decision.amount(), WalletEntryType.REFUND_CREDIT,
                    "booking-cancel:" + bookingId, "Hoàn tiền hủy đặt chỗ (" + decision.ratePercent() + "%)");
        }
        audit.record(AuditEntry.of(AuditAction.BOOKING_CANCELLED, "BOOKING", bookingId, booking.branchId(), customer)
                .withChange(Map.of("status", "CONFIRMED"),
                        Map.of("status", "CANCELLED", "refundVnd", decision.amount().vnd(),
                                "rate", decision.ratePercent(),
                                "violation", decision.violation() == null ? "NONE" : decision.violation().name())));
        return toPreview(booking, decision);
    }

    private RefundDecision decide(PaidBooking booking, Instant now) {
        int prior = violations.countInWindow(booking.customerId(), now.minus(RefundPolicy.VIOLATION_WINDOW), now);
        return new RefundPolicy(props.freeCancellationBefore())
                .evaluate(CancellationCause.CUSTOMER_CANCEL, now, booking.start(), prior, booking.paid());
    }

    private CancellationPreview toPreview(PaidBooking booking, RefundDecision decision) {
        return new CancellationPreview(booking.bookingId(), booking.paid().vnd(), decision.amount().vnd(),
                decision.ratePercent(), decision.violation() == null ? null : decision.violation().name(),
                new RefundPolicy(props.freeCancellationBefore()).freeCancellationDeadline(booking.start()), "MSG17");
    }

    /** A CONFIRMED, paid booking of this customer that has not started yet (UC-08 pre-condition). */
    private PaidBooking paidBooking(UUID customerId, UUID bookingId, boolean lock) {
        List<PaidBooking> rows = jdbc.query("""
                SELECT sb.id, sb.branch_id, sb.status, sb.start_time, t.id transaction_id, t.customer_id,
                       t.status transaction_status, t.total_amount
                FROM scheduled_bookings sb JOIN transactions t ON t.id = sb.transaction_id
                WHERE sb.id = ? AND t.customer_id = ?
                """ + (lock ? " FOR UPDATE OF sb, t" : ""), (rs, row) -> {
            if (!"CONFIRMED".equals(rs.getString("status")) || !"PAID".equals(rs.getString("transaction_status"))) {
                throw BusinessException.of(ErrorCode.INVALID_STATE_TRANSITION,
                        "Only a paid, confirmed booking can be cancelled for a refund");
            }
            BigDecimal amount = rs.getBigDecimal("total_amount");
            return new PaidBooking(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                    rs.getObject("transaction_id", UUID.class), rs.getObject("customer_id", UUID.class),
                    rs.getTimestamp("start_time").toInstant(), Money.of(amount));
        }, bookingId, customerId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("Booking not found");
        }
        PaidBooking booking = rows.getFirst();
        if (!booking.start().isAfter(clock.instant())) {
            throw BusinessException.of(ErrorCode.INVALID_STATE_TRANSITION,
                    "The booking has already started and can no longer be cancelled");
        }
        return booking;
    }

    private record PaidBooking(UUID bookingId, UUID branchId, UUID transactionId, UUID customerId, Instant start,
                               Money paid) {
    }

    /** MSG17 data: refund amount and rate if cancelled now, and until when cancellation is free. */
    public record CancellationPreview(UUID bookingId, long paidVnd, long refundVnd, int ratePercent,
                                      String violation, Instant freeCancellationUntil, String messageCode) {
    }
}
