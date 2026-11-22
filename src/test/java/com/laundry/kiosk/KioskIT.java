package com.laundry.kiosk;

import com.laundry.booking.dto.BookingDtos.BookingDto;
import com.laundry.kiosk.KioskService.KioskHoldRequest;
import com.laundry.kiosk.KioskService.KioskRefundRequest;
import com.laundry.refund.service.AutomaticRefundService;
import com.laundry.refund.service.RefundService;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.money.Money;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V72: the in-store kiosk books and pays for walk-in customers; their refunds never reach the kiosk's wallet. */
@IntegrationTest
@DisplayName("Kiosk (walk-in customers)")
class KioskIT {

    @Autowired private KioskService kiosk;
    @Autowired private AutomaticRefundService automaticRefunds;
    @Autowired private RefundService refundService;
    @Autowired private WalletService wallet;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private CurrentUser device;
    private UUID washer;

    @BeforeEach
    void setUp() {
        Fixtures.bookableStore(jdbc, 30_000);
        device = CurrentUser.of(Fixtures.user(jdbc, "KIOSK"), Role.KIOSK);
        washer = Fixtures.washer(jdbc);
    }

    @Test
    @DisplayName("Books a machine for now, owned by the kiosk; an unpaid hold is released when the customer leaves")
    void holdsNowAndReleases() {
        BookingDto hold = holdNow();
        assertThat(hold.status()).isEqualTo("HELD");
        assertThat(kiosk.get(device, hold.bookingId()).bookingId()).isEqualTo(hold.bookingId());

        kiosk.cancel(device, hold.bookingId());
        assertThat(kiosk.get(device, hold.bookingId()).status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("No advance booking from the kiosk")
    void refusesAdvanceBooking() {
        Instant later = minute().plus(Duration.ofHours(2));
        assertThatThrownBy(() -> kiosk.hold(device, new KioskHoldRequest("WASH_ONLY", later, List.of(washer),
                "kiosk-" + UUID.randomUUID()))).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("A rule refund of a kiosk booking waits for the Owner instead of crediting the kiosk's wallet")
    void automaticRefundIsQueuedForTheOwner() {
        BookingDto hold = holdNow();
        Fixtures.paidPayment(jdbc, hold.transactionId(), 30_000);

        Money queued = tx.execute(status -> automaticRefunds.settle(hold.transactionId(), device.id(),
                Money.of(new java.math.BigDecimal(30_000)), WalletEntryType.REFUND_CREDIT, "Máy dừng giữa chừng"));

        assertThat(queued.vnd()).isEqualTo(30_000);
        assertThat(wallet.balanceOf(device.id()).vnd()).isZero();
        assertThat(refundRow(hold.transactionId(), "status")).isEqualTo("PENDING_APPROVAL");
        assertThat(refundRow(hold.transactionId(), "reason")).startsWith(AutomaticRefundService.KIOSK_REASON_PREFIX);
    }

    @Test
    @DisplayName("The customer's refund request carries their phone; approving it never credits the kiosk")
    void refundRequestApprovedInPerson() {
        BookingDto hold = holdNow();
        Fixtures.paidPayment(jdbc, hold.transactionId(), 30_000);

        var refund = kiosk.requestRefund(device, hold.bookingId(), new KioskRefundRequest("0901234567", "Máy không chạy"));
        assertThat(refund.status().toString()).isEqualTo("PENDING_APPROVAL");
        assertThat(refundRow(hold.transactionId(), "reason")).contains("0901234567");
        assertThatThrownBy(() -> kiosk.requestRefund(device, hold.bookingId(),
                new KioskRefundRequest("0901234567", null))).isInstanceOf(BusinessException.class);

        CurrentUser owner = CurrentUser.of(Fixtures.user(jdbc, "OWNER"), Role.OWNER);
        refundService.approve(refund.id(), "Đã trả tiền mặt", owner);

        assertThat(refundRow(hold.transactionId(), "status")).isEqualTo("COMPLETED");
        assertThat(wallet.balanceOf(device.id()).vnd()).isZero();
    }

    @Test
    @DisplayName("A refund needs a paid booking")
    void refundNeedsPayment() {
        BookingDto hold = holdNow();
        assertThatThrownBy(() -> kiosk.requestRefund(device, hold.bookingId(),
                new KioskRefundRequest("0901234567", null))).isInstanceOf(BusinessException.class);
    }

    private BookingDto holdNow() {
        return kiosk.hold(device, new KioskHoldRequest("WASH_ONLY", minute().plus(Duration.ofMinutes(2)),
                List.of(washer), "kiosk-" + UUID.randomUUID()));
    }

    private static Instant minute() {
        return Instant.now().truncatedTo(ChronoUnit.MINUTES);
    }

    private String refundRow(UUID transactionId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM refund_requests WHERE transaction_id = ? "
                + "ORDER BY created_at DESC LIMIT 1", String.class, transactionId);
    }
}
