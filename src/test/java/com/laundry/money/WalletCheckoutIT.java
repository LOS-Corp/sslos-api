package com.laundry.money;

import com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.entity.PaymentMethod;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.service.PaymentService;
import com.laundry.shared.money.Money;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import com.laundry.wallet.entity.WalletEntryType;
import com.laundry.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-PAY-04 wallet first, the remainder through payOS; SC-31 the wallet part comes back if payOS fails. */
@IntegrationTest
class WalletCheckoutIT {

    private static final long PRICE = 50_000;

    @Autowired private ScheduledBookingService bookings;
    @Autowired private PaymentService payments;
    @Autowired private WalletService wallet;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID branchId;
    private UUID washerId;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        branchId = Fixtures.bookableBranch(jdbc, PRICE);
        washerId = Fixtures.washer(jdbc, branchId);
        customerId = Fixtures.customer(jdbc);
    }

    @Test
    @DisplayName("SC-31: order 50,000, wallet 20,000 → wallet −20,000, payOS link 30,000; payOS failure returns 20,000")
    void partialWalletThenPayOSFailure() {
        topUp(20_000);
        var hold = hold();

        PaymentResponse online = checkout(hold.bookingId(), hold.transactionId(), "sc31");
        assertThat(online.getPaymentMethod()).isEqualTo(PaymentMethod.ONLINE);
        assertThat(online.getAmount()).isEqualByComparingTo("30000");
        assertThat(balance()).isZero();

        payments.processPayOSWebhook(online.getPaymentId(), false, "MOCK", "Customer cancelled");

        assertThat(balance()).isEqualTo(20_000);
        assertThat(walletPaymentStatus(hold.transactionId())).isEqualTo("REVERSED");
        assertThat(bookingStatus(hold.bookingId())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("UC-02 5b: the wallet covers everything → booking CONFIRMED without payOS; repeated checkout debits once")
    void walletCoversEverything() {
        topUp(60_000);
        var hold = hold();

        PaymentResponse paid = checkout(hold.bookingId(), hold.transactionId(), "full");
        PaymentResponse again = checkout(hold.bookingId(), hold.transactionId(), "full");

        assertThat(paid.getPaymentMethod()).isEqualTo(PaymentMethod.WALLET);
        assertThat(paid.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(again.getPaymentId()).isEqualTo(paid.getPaymentId());
        assertThat(bookingStatus(hold.bookingId())).isEqualTo("CONFIRMED");
        assertThat(balance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("Hold expires with a wallet part paid → the wallet part is given back")
    void holdExpiryReversesWalletPart() {
        topUp(20_000);
        var hold = hold();
        checkout(hold.bookingId(), hold.transactionId(), "expiry");

        jdbc.update("UPDATE scheduled_bookings SET hold_expires_at = NOW() - interval '1 second' WHERE id = ?",
                hold.bookingId());
        bookings.expireBookingHolds();

        assertThat(bookingStatus(hold.bookingId())).isEqualTo("EXPIRED");
        assertThat(balance()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("BR-04 + FR-PAY-04: payOS succeeds after the hold expired → full 50,000 back, never twice")
    void latePayOSAfterWalletPart() {
        topUp(20_000);
        var hold = hold();
        PaymentResponse online = checkout(hold.bookingId(), hold.transactionId(), "late");
        jdbc.update("UPDATE scheduled_bookings SET hold_expires_at = NOW() - interval '1 second' WHERE id = ?",
                hold.bookingId());
        bookings.expireBookingHolds();
        assertThat(balance()).as("wallet part reversed at expiry").isEqualTo(20_000);

        payments.processPayOSWebhook(online.getPaymentId(), true, "MOCK-LATE", "Paid late");
        payments.processPayOSWebhook(online.getPaymentId(), true, "MOCK-LATE", "Repeated webhook");

        assertThat(bookingStatus(hold.bookingId())).isEqualTo("PAYMENT_REVIEW_REQUIRED");
        assertThat(balance()).as("20,000 reversed + 30,000 late credit").isEqualTo(PRICE);
        assertThat(wallet.outOfBalanceWallets()).isEmpty();
    }

    @Test
    @DisplayName("useWallet=false pays the whole amount through payOS")
    void walletCanBeSkipped() {
        topUp(20_000);
        var hold = hold();
        PaymentResponse online = payments.createBookingPayment(hold.bookingId(), hold.transactionId(),
                "skip-" + UUID.randomUUID(), customerId, false);
        assertThat(online.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(PRICE));
        assertThat(balance()).isEqualTo(20_000);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Hold hold() {
        var dto = bookings.createHold(customerId, new CreateHoldRequest(branchId, "WASH_ONLY",
                LocalDate.now(Fixtures.ZONE).plusDays(1).atTime(9, 0).atZone(Fixtures.ZONE).toInstant(),
                List.of(washerId), "wallet-" + UUID.randomUUID(), null, null, null));
        return new Hold(dto.bookingId(), dto.transactionId());
    }

    private PaymentResponse checkout(UUID bookingId, UUID transactionId, String key) {
        return payments.createBookingPayment(bookingId, transactionId, "checkout-" + key + "-" + transactionId,
                customerId, true);
    }

    private void topUp(long vnd) {
        tx.executeWithoutResult(s -> wallet.credit(customerId, Money.vnd(vnd), WalletEntryType.REFUND_CREDIT,
                "seed:" + UUID.randomUUID(), "Test seed"));
    }

    private long balance() {
        return wallet.balanceOf(customerId).vnd();
    }

    private String bookingStatus(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM scheduled_bookings WHERE id = ?", String.class, bookingId);
    }

    private String walletPaymentStatus(UUID transactionId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE transaction_id = ? AND payment_method = 'WALLET'",
                String.class, transactionId);
    }

    private record Hold(UUID bookingId, UUID transactionId) {
    }
}
