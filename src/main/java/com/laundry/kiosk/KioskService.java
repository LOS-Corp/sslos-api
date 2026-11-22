package com.laundry.kiosk;

import com.laundry.booking.dto.BookingDtos.BookingDto;
import com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import com.laundry.booking.dto.BookingDtos.RecommendedResourceDto;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.machine.dto.CheckInResponse;
import com.laundry.machine.service.MachineStartAuthorizationService;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.payment.service.PaymentService;
import com.laundry.refund.dto.request.CreateRefundRequest;
import com.laundry.refund.dto.response.RefundResponse;
import com.laundry.refund.repository.RefundRequestRepository;
import com.laundry.refund.service.AutomaticRefundService;
import com.laundry.refund.service.RefundService;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * "Dùng ngay" at the in-store kiosk (V72): a walk-in customer without an account picks a free machine, pays by
 * the payOS QR on the screen and the kiosk unlocks the machine's START. Bookings belong to the kiosk account; the
 * same hold, payment and check-in rules as the customer app apply. A machine that then does not run becomes a
 * refund request the Owner settles in person (the customer has no wallet).
 */
@Service
@RequiredArgsConstructor
public class KioskService {

    /** The kiosk serves people standing in the store: no advance booking from it. */
    static final Duration MAX_START_AHEAD = Duration.ofMinutes(30);

    private final ScheduledBookingService bookings;
    private final PaymentService paymentService;
    private final PaymentRepository payments;
    private final MachineStartAuthorizationService starts;
    private final RefundService refundService;
    private final RefundRequestRepository refunds;
    private final Clock clock;

    public BookingDto hold(CurrentUser kiosk, KioskHoldRequest request) {
        if (request.startTime().isAfter(clock.instant().plus(MAX_START_AHEAD))) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST,
                    "The kiosk only books machines to use now; book ahead in the customer app");
        }
        return bookings.createHold(kiosk.id(), new CreateHoldRequest(request.serviceCode(), request.startTime(),
                request.resourceIds(), request.idempotencyKey(), null, null, null));
    }

    public BookingDto get(CurrentUser kiosk, UUID bookingId) {
        return bookings.getBooking(kiosk.id(), bookingId);
    }

    /** The payOS QR shown on the kiosk; the whole amount goes through payOS (a kiosk has no wallet credit). */
    public PaymentResponse checkout(CurrentUser kiosk, UUID bookingId) {
        BookingDto booking = bookings.getBooking(kiosk.id(), bookingId);
        return paymentService.createBookingPayment(bookingId, booking.transactionId(),
                "kiosk:" + bookingId, kiosk.id(), false);
    }

    /** The customer walks away before paying: the machine is released at once. */
    public void cancel(CurrentUser kiosk, UUID bookingId) {
        bookings.cancelBooking(kiosk.id(), bookingId);
    }

    /**
     * Unlocks START on one machine of a paid booking (the washer first for wash + dry). Same rules as the QR
     * check-in: paid, within the check-in window, the machine online and free. Can be repeated while the window
     * is open, e.g. when the customer did not press START in time.
     */
    public CheckInResponse start(CurrentUser kiosk, UUID bookingId, String machineCode) {
        BookingDto booking = bookings.getBooking(kiosk.id(), bookingId);
        boolean ofBooking = booking.resources().stream()
                .map(RecommendedResourceDto::resourceCode)
                .anyMatch(code -> code.equalsIgnoreCase(machineCode));
        if (!ofBooking) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "This machine is not part of the booking");
        }
        return starts.checkIn(machineCode, kiosk.id());
    }

    public KioskStartStatus startStatus(CurrentUser kiosk, UUID authorizationId) {
        return starts.findOwnedByUser(authorizationId, kiosk.id())
                .map(auth -> new KioskStartStatus(auth.getId(), auth.getBookingId(), auth.getMachineCode(),
                        auth.getStatus().name(), auth.getExpiresAt(), auth.getRevokeReason()))
                .orElseThrow(() -> BusinessException.notFound("Start authorization not found"));
    }

    /**
     * The machine did not run after the customer paid: a refund request for the Owner, with the customer's
     * phone so the store can pay them back. One request per booking payment.
     */
    @Transactional
    public RefundResponse requestRefund(CurrentUser kiosk, UUID bookingId, KioskRefundRequest request) {
        BookingDto booking = bookings.getBooking(kiosk.id(), bookingId);
        Payment paid = payments.findByTransactionIdOrderByCreatedAtDesc(booking.transactionId()).stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.PAID)
                .findFirst()
                .orElseThrow(() -> BusinessException.of(ErrorCode.REFUND_REJECTED, "This booking was not paid"));
        if (!refunds.findByPaymentId(paid.getId()).isEmpty()) {
            throw BusinessException.of(ErrorCode.CONFLICT, "A refund was already requested for this payment");
        }
        BigDecimal amount = paid.getAmount().subtract(refunds.countedAgainstCap(paid.getId(), null));
        if (amount.signum() <= 0) {
            throw BusinessException.of(ErrorCode.REFUND_EXCEEDS_PAID, "Nothing is left to refund on this payment");
        }
        String note = request.note() == null || request.note().isBlank() ? "Máy không chạy" : request.note().trim();
        String reason = AutomaticRefundService.KIOSK_REASON_PREFIX + "SĐT " + request.phone().trim() + ": " + note;
        return refundService.raise(new CreateRefundRequest(paid.getId(), amount, reason, null), kiosk);
    }

    public record KioskHoldRequest(
            @jakarta.validation.constraints.NotBlank String serviceCode,
            @jakarta.validation.constraints.NotNull Instant startTime,
            @jakarta.validation.constraints.NotNull List<UUID> resourceIds,
            @jakarta.validation.constraints.NotBlank String idempotencyKey) {}

    public record KioskStartRequest(@jakarta.validation.constraints.NotBlank String machineCode) {}

    public record KioskStartStatus(UUID authorizationId, UUID bookingId, String machineCode, String status,
                                   OffsetDateTime expiresAt, String revokeReason) {}

    /** Vietnamese mobile or landline number, so the store can reach the customer about the refund. */
    public record KioskRefundRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "^\\+?[0-9 .-]{9,15}$", message = "Số điện thoại không hợp lệ")
            String phone,
            @jakarta.validation.constraints.Size(max = 300) String note) {}
}
