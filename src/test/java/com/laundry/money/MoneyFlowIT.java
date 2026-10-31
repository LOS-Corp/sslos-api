package com.laundry.money;

import com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import com.laundry.booking.service.BookingCancellationService;
import com.laundry.booking.service.BookingCancellationService.CancellationPreview;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.machine.service.MachineStartAuthorizationService;
import com.laundry.refund.dto.request.CreateRefundRequest;
import com.laundry.refund.dto.response.RefundCandidate;
import com.laundry.refund.dto.response.RefundResponse;
import com.laundry.refund.entity.RefundStatus;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Money end to end on PostgreSQL: cancellation refunds, exception refunds and the wallet ledger (UC-08, UC-13). */
@IntegrationTest
class MoneyFlowIT {

    private static final long PRICE = 50_000;

    @Autowired private ScheduledBookingService bookings;
    @Autowired private BookingCancellationService cancellations;
    @Autowired private RefundService refunds;
    @Autowired private WalletService wallet;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MachineStartAuthorizationService startAuthorizations;

    private UUID branchId;
    private UUID washerId;
    private UUID customerId;
    private CurrentUser customer;
    private Instant tomorrow;

    @BeforeEach
    void setUp() {
        branchId = Fixtures.bookableBranch(jdbc, PRICE);
        washerId = Fixtures.washer(jdbc, branchId);
        customerId = Fixtures.customer(jdbc);
        customer = CurrentUser.of(customerId, Role.CUSTOMER);
        tomorrow = LocalDate.now(Fixtures.ZONE).plusDays(1).atTime(8, 0).atZone(Fixtures.ZONE).toInstant();
    }

    @Test
    @DisplayName("SC-06: cancel far ahead → 100 % to the wallet, no violation")
    void freeCancellation() {
        UUID booking = paidBooking(tomorrow);

        CancellationPreview result = cancellations.cancel(customer, booking);

        assertThat(result.refundVnd()).isEqualTo(PRICE);
        assertThat(result.violation()).isNull();
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE);
        assertThat(status(booking)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("SC-29: second late cancellation in 90 days → 70 % (35,000 ₫) credited in the same transaction")
    void secondLateCancellation() {
        UUID first = paidBooking(soon(30));
        // Use-now (within 15 min): outside the BR-27 advance quota, still a late cancellation (inside T−60).
        UUID second = paidBooking(soon(10), Fixtures.washer(jdbc, branchId));

        CancellationPreview firstResult = cancellations.cancel(customer, first);
        assertThat(firstResult.violation()).isEqualTo("LATE_CANCELLATION");
        assertThat(firstResult.refundVnd()).isEqualTo(PRICE);

        assertThat(cancellations.preview(customerId, second).refundVnd()).as("MSG17 preview").isEqualTo(35_000);
        CancellationPreview secondResult = cancellations.cancel(customer, second);
        assertThat(secondResult.ratePercent()).isEqualTo(70);
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE + 35_000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_violations WHERE customer_id = ?",
                Integer.class, customerId)).isEqualTo(2);
    }

    @Test
    @DisplayName("SC-23 / BR-14: approver ≠ requester, approval credits the wallet once, cap never exceeded")
    void exceptionRefund() {
        UUID booking = paidBooking(tomorrow);
        UUID paymentId = paymentOf(booking);
        CurrentUser staff = staffOf(branchId);
        CurrentUser owner = CurrentUser.of(Fixtures.user(jdbc, "OWNER"), Role.OWNER);

        RefundResponse raised = refunds.raise(new CreateRefundRequest(paymentId, BigDecimal.valueOf(20_000),
                "Máy ồn bất thường", "ảnh chụp"), staff);
        assertThat(raised.requestedBy()).isEqualTo(staff.id());
        assertThat(raised.status()).isEqualTo(RefundStatus.PENDING_APPROVAL);

        assertThatThrownBy(() -> refunds.raise(new CreateRefundRequest(paymentId, BigDecimal.valueOf(40_000),
                "Thêm", null), staff))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.REFUND_EXCEEDS_PAID));

        RefundResponse approved = refunds.approve(raised.id(), "Đã kiểm tra", owner);
        assertThat(approved.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(approved.approvedBy()).isEqualTo(owner.id());
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(20_000);

        assertThatThrownBy(() -> refunds.approve(raised.id(), null, owner))
                .isInstanceOf(BusinessException.class);
        assertThat(wallet.balanceOf(customerId).vnd()).as("no double refund").isEqualTo(20_000);
    }

    @Test
    @DisplayName("SC-16: an Owner cannot approve the request they raised themselves")
    void selfApprovalIsRefused() {
        UUID paymentId = paymentOf(paidBooking(tomorrow));
        CurrentUser owner = CurrentUser.of(Fixtures.user(jdbc, "OWNER"), Role.OWNER);
        RefundResponse raised = refunds.raise(new CreateRefundRequest(paymentId, BigDecimal.valueOf(10_000),
                "Lỗi máy", null), owner);

        assertThatThrownBy(() -> refunds.approve(raised.id(), null, owner))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.SELF_APPROVAL_FORBIDDEN));
    }

    @Test
    @DisplayName("Owner rejects with a reason; Staff see only their branch's pending requests")
    void rejectAndVisibility() {
        UUID paymentId = paymentOf(paidBooking(tomorrow));
        CurrentUser staff = staffOf(branchId);
        CurrentUser otherStaff = staffOf(Fixtures.bookableBranch(jdbc, PRICE));
        CurrentUser owner = CurrentUser.of(Fixtures.user(jdbc, "OWNER"), Role.OWNER);
        RefundResponse raised = refunds.raise(new CreateRefundRequest(paymentId, BigDecimal.valueOf(5_000),
                "Khách phàn nàn", null), staff);

        assertThat(refunds.pending(null, staff)).extracting(RefundResponse::id).contains(raised.id());
        assertThat(refunds.pending(null, otherStaff)).extracting(RefundResponse::id).doesNotContain(raised.id());
        assertThat(refunds.pending(branchId, owner)).extracting(RefundResponse::id).contains(raised.id());
        assertThat(refunds.get(raised.id(), staff).id()).isEqualTo(raised.id());
        assertThatThrownBy(() -> refunds.get(raised.id(), otherStaff))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.BRANCH_OUT_OF_SCOPE));
        assertThatThrownBy(() -> refunds.raise(new CreateRefundRequest(paymentId, BigDecimal.ONE, "x", null), otherStaff))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> refunds.reject(raised.id(), " ", owner))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.REASON_REQUIRED));
        RefundResponse rejected = refunds.reject(raised.id(), "Không đủ bằng chứng", owner);
        assertThat(rejected.status()).isEqualTo(RefundStatus.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("Không đủ bằng chứng");
        assertThat(wallet.balanceOf(customerId).vnd()).isZero();
        assertThat(refunds.pending(branchId, owner)).extracting(RefundResponse::id).doesNotContain(raised.id());
    }

    @Test
    @DisplayName("UC-08: cancelling a booking whose START is already unlocked revokes it after commit")
    void cancellationRevokesStart() {
        UUID booking = paidBooking(soon(10));
        String machineCode = jdbc.queryForObject("SELECT machine_code FROM machines WHERE id = ?", String.class, washerId);
        var authorization = startAuthorizations.checkIn(machineCode, customerId);

        cancellations.cancel(customer, booking);

        assertThat(jdbc.queryForObject("SELECT status FROM machine_start_authorizations WHERE id = ?",
                String.class, authorization.authorizationId())).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT booking_id FROM machine_start_authorizations WHERE id = ?",
                UUID.class, authorization.authorizationId())).isEqualTo(booking);
    }

    @Test
    @DisplayName("UC-08 4a: Staff find the order by its short code; other branches never see it")
    void lookupByShortCode() {
        UUID booking = paidBooking(tomorrow);
        String shortCode = booking.toString().substring(0, 8).toUpperCase();
        CurrentUser staff = staffOf(branchId);

        List<RefundCandidate> found = refunds.lookup(" " + shortCode + " ", staff);
        assertThat(found).singleElement().satisfies(candidate -> {
            assertThat(candidate.bookingId()).isEqualTo(booking);
            assertThat(candidate.shortCode()).isEqualTo(shortCode);
            assertThat(candidate.payments()).singleElement().satisfies(payment -> {
                assertThat(payment.refundable()).isEqualByComparingTo(BigDecimal.valueOf(PRICE));
                assertThat(payment.alreadyRefunded()).isEqualByComparingTo(BigDecimal.ZERO);
            });
        });

        // Raising against the found payment lowers what is still refundable (BR-14 cap).
        refunds.raise(new CreateRefundRequest(found.getFirst().payments().getFirst().paymentId(),
                BigDecimal.valueOf(20_000), "Máy dừng giữa chu trình", null), staff);
        assertThat(refunds.lookup(booking.toString(), staff).getFirst().payments().getFirst().refundable())
                .isEqualByComparingTo(BigDecimal.valueOf(PRICE - 20_000));

        assertThat(refunds.lookup(shortCode, staffOf(Fixtures.bookableBranch(jdbc, PRICE)))).isEmpty();
        assertThatThrownBy(() -> refunds.lookup("abc", staff))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("BR-15 / NFR-17: ledger is append-only and balances reconcile")
    void ledgerIsImmutable() {
        cancellations.cancel(customer, paidBooking(tomorrow));

        assertThatThrownBy(() -> jdbc.update("UPDATE wallet_entries SET amount_vnd = 1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM wallet_entries"))
                .isInstanceOf(DataAccessException.class);
        assertThat(wallet.outOfBalanceWallets()).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Holds and pays a WASH_ONLY booking starting at {@code start}; returns the booking id. */
    private UUID paidBooking(Instant start) {
        return paidBooking(start, washerId);
    }

    private UUID paidBooking(Instant start, UUID washer) {
        var hold = bookings.createHold(customerId, new CreateHoldRequest(branchId, "WASH_ONLY",
                start.truncatedTo(ChronoUnit.MINUTES), List.of(washer), "money-" + UUID.randomUUID(), null, null, null));
        jdbc.update("""
                INSERT INTO payments (id, transaction_id, amount, payment_method, status, paid_at, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, 'ONLINE', 'PAID', NOW(), NOW(), NOW())
                """, hold.transactionId(), PRICE);
        bookings.confirmPayment(hold.transactionId());
        assertThat(status(hold.bookingId())).isEqualTo("CONFIRMED");
        return hold.bookingId();
    }

    /** A start {@code minutes} from now — inside T−60, so cancelling it is late (the test branch is open 24/7). */
    private Instant soon(int minutes) {
        return Instant.now().plus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
    }

    private UUID paymentOf(UUID bookingId) {
        return jdbc.queryForObject("""
                SELECT p.id FROM payments p JOIN scheduled_bookings b ON b.transaction_id = p.transaction_id
                WHERE b.id = ?
                """, UUID.class, bookingId);
    }

    private CurrentUser staffOf(UUID branch) {
        UUID staff = Fixtures.user(jdbc, "STAFF");
        jdbc.update("INSERT INTO branch_assignments (user_id, branch_id) VALUES (?, ?)", staff, branch);
        return CurrentUser.of(staff, Role.STAFF);
    }

    private String status(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM scheduled_bookings WHERE id = ?", String.class, bookingId);
    }
}
