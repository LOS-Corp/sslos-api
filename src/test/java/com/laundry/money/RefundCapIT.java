package com.laundry.money;

import com.laundry.booking.dto.BookingDtos.BookingDto;
import com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import com.laundry.booking.service.BookingCancellationService;
import com.laundry.booking.service.PaymentReviewService;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.refund.dto.request.CreateRefundRequest;
import com.laundry.refund.dto.response.RefundResponse;
import com.laundry.refund.service.RefundService;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import com.laundry.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BR-14, FR-PAY-03, NFR-06: every refund counts against the cap, whichever way it was decided. */
@IntegrationTest
class RefundCapIT {

    private static final long PRICE = 50_000;

    @Autowired private ScheduledBookingService bookings;
    @Autowired private BookingCancellationService cancellations;
    @Autowired private PaymentReviewService reviews;
    @Autowired private RefundService refunds;
    @Autowired private WalletService wallet;
    @Autowired private JdbcTemplate jdbc;

    private UUID customerId;
    private CurrentUser customer;
    private CurrentUser owner;
    private Instant tomorrow;

    @BeforeEach
    void setUp() {
        Fixtures.bookableStore(jdbc, PRICE);
        customerId = Fixtures.customer(jdbc);
        customer = CurrentUser.of(customerId, Role.CUSTOMER);
        owner = CurrentUser.of(Fixtures.user(jdbc, "OWNER"), Role.OWNER);
        tomorrow = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
    }

    @Test
    @DisplayName("A cancelled booking cannot be refunded a second time by an exception refund")
    void cancelThenExceptionRefund() {
        Paid order = paidOnline(tomorrow);

        cancellations.cancel(customer, order.bookingId());

        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE);
        assertThat(refundRows(order.transactionId(), "COMPLETED")).isEqualTo(1);
        CurrentUser staff = CurrentUser.of(Fixtures.user(jdbc, "OWNER"), Role.OWNER);
        assertThatThrownBy(() -> refunds.raise(new CreateRefundRequest(order.paymentId(), BigDecimal.ONE,
                "Hoàn thêm", null), staff))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.REFUND_EXCEEDS_PAID));
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE);
    }

    @Test
    @DisplayName("A cancellation after an exception refund pays back only what is left")
    void exceptionRefundThenCancel() {
        Paid order = paidOnline(tomorrow);
        CurrentUser staff = CurrentUser.of(Fixtures.user(jdbc, "OWNER"), Role.OWNER);
        RefundResponse raised = refunds.raise(new CreateRefundRequest(order.paymentId(),
                BigDecimal.valueOf(20_000), "Máy ồn", null), staff);
        refunds.approve(raised.id(), "Đã kiểm tra", owner);

        assertThat(cancellations.preview(customerId, order.bookingId()).refundVnd()).isEqualTo(30_000);
        assertThat(cancellations.cancel(customer, order.bookingId()).refundVnd()).isEqualTo(30_000);

        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE);
    }

    @Test
    @DisplayName("SC-31: a refund of an order paid by wallet and payOS is spread over both payments")
    void refundAcrossWalletAndPayOs() {
        var hold = hold(tomorrow);
        Fixtures.payment(jdbc, hold.transactionId(), 20_000, "WALLET");
        Fixtures.paidPayment(jdbc, hold.transactionId(), 30_000);
        bookings.confirmPayment(hold.transactionId());

        cancellations.cancel(customer, hold.bookingId());

        assertThat(refundRows(hold.transactionId(), "COMPLETED")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM refund_requests WHERE transaction_id = ?",
                BigDecimal.class, hold.transactionId())).isEqualByComparingTo(BigDecimal.valueOf(PRICE));
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE);
    }

    @Test
    @DisplayName("V53: the database refuses a refund row above the amount paid, whatever wrote it")
    void databaseRefusesOverCap() {
        Paid order = paidOnline(tomorrow);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO refund_requests (id, payment_id, transaction_id, requested_by, amount, reason, status)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, 'x', 'COMPLETED')
                """, order.paymentId(), order.transactionId(), customerId, PRICE + 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("SM-01 B6: a payment after cancelling the hold is credited, and the review closes without paying twice")
    void paidAfterCancel() {
        var hold = hold(tomorrow);
        bookings.cancelBooking(customerId, hold.bookingId());

        Fixtures.paidPayment(jdbc, hold.transactionId(), PRICE);
        bookings.confirmPayment(hold.transactionId());

        assertThat(status(hold.bookingId())).isEqualTo("PAYMENT_REVIEW_REQUIRED");
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE);
        assertThat(reviews.open()).filteredOn(review -> review.bookingId().equals(hold.bookingId()))
                .singleElement().satisfies(review -> assertThat(review.stillOwedVnd()).isZero());

        assertThatThrownBy(() -> reviews.resolve(hold.bookingId(), " ", owner))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.REASON_REQUIRED));
        reviews.resolve(hold.bookingId(), "Đã kiểm tra với khách", owner);

        assertThat(status(hold.bookingId())).isEqualTo("CANCELLED");
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE);
        assertThat(reviews.open()).noneMatch(review -> review.bookingId().equals(hold.bookingId()));
        assertThatThrownBy(() -> reviews.resolve(hold.bookingId(), "Lần nữa", owner))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("Closing a review credits money that an older booking never received")
    void reviewCreditsWhatIsStillOwed() {
        Paid order = paidOnline(tomorrow);
        jdbc.update("UPDATE scheduled_bookings SET status = 'PAYMENT_REVIEW_REQUIRED' WHERE id = ?", order.bookingId());

        reviews.resolve(order.bookingId(), "Hoàn cho khách", owner);

        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE);
    }

    @Test
    @DisplayName("BR-04 / SC-18: a payment inside a live hold confirms the booking even after the start has passed")
    void liveHoldIsHonouredAfterTheStart() {
        var hold = hold(tomorrow);
        jdbc.update("UPDATE scheduled_bookings SET start_time = NOW() - interval '1 minute' WHERE id = ?", hold.bookingId());

        assertThat(bookings.prepareCheckout(customerId, hold.bookingId())).isEqualTo(hold.transactionId());
        Fixtures.paidPayment(jdbc, hold.transactionId(), PRICE);
        bookings.confirmPayment(hold.transactionId());

        assertThat(status(hold.bookingId())).isEqualTo("CONFIRMED");
        assertThat(wallet.balanceOf(customerId).vnd()).isZero();
    }

    private Paid paidOnline(Instant start) {
        var hold = hold(start);
        UUID paymentId = Fixtures.paidPayment(jdbc, hold.transactionId(), PRICE);
        bookings.confirmPayment(hold.transactionId());
        return new Paid(hold.bookingId(), hold.transactionId(), paymentId);
    }

    private BookingDto hold(Instant start) {
        return bookings.createHold(customerId, new CreateHoldRequest("WASH_ONLY", start,
                List.of(Fixtures.washer(jdbc)), "cap-" + UUID.randomUUID(), null, null, null));
    }

    private String status(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM scheduled_bookings WHERE id = ?", String.class, bookingId);
    }

    private int refundRows(UUID transactionId, String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refund_requests WHERE transaction_id = ? AND status = ?",
                Integer.class, transactionId, status);
    }

    private record Paid(UUID bookingId, UUID transactionId, UUID paymentId) {
    }
}
