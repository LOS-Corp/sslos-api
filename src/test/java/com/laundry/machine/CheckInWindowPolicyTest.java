package com.laundry.machine;

import com.laundry.machine.domain.CheckInWindowPolicy;
import com.laundry.machine.domain.CheckInWindowPolicy.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** BR-06 / BR-07 boundaries (SC-18, SC-19). */
class CheckInWindowPolicyTest {

    private static final Instant T = Instant.parse("2026-11-01T03:00:00Z"); // 10:00 in Hồ Chí Minh City
    private final CheckInWindowPolicy policy =
            new CheckInWindowPolicy(Duration.ofMinutes(15), Duration.ofMinutes(10), Duration.ofMinutes(5));

    @Test
    @DisplayName("SC-19: book-later T=10:00 → 9:44 not yet, 9:50 open, 10:10 open, 10:11 NO_SHOW")
    void bookLater() {
        var window = policy.windowFor(T, T.minus(Duration.ofHours(20)));

        assertThat(window.positionOf(T.minus(Duration.ofMinutes(16)))).isEqualTo(Position.NOT_YET_OPEN);
        assertThat(window.positionOf(T.minus(Duration.ofMinutes(10)))).isEqualTo(Position.OPEN);
        assertThat(window.positionOf(T.plus(Duration.ofMinutes(10)))).isEqualTo(Position.OPEN);
        assertThat(window.positionOf(T.plus(Duration.ofMinutes(11)))).isEqualTo(Position.CLOSED);
    }

    @Test
    @DisplayName("SC-18: use-now paid 3 min after the start minute → START allowed until PAID+10")
    void useNow() {
        Instant paid = T.plus(Duration.ofMinutes(3));
        var window = policy.windowFor(T, paid);

        assertThat(window.positionOf(T.plus(Duration.ofMinutes(8)))).isEqualTo(Position.OPEN);
        assertThat(window.positionOf(paid.plus(Duration.ofMinutes(10)))).isEqualTo(Position.OPEN);
        assertThat(window.positionOf(paid.plus(Duration.ofMinutes(11)))).isEqualTo(Position.CLOSED);
    }

    @Test
    @DisplayName("A payment far after the hold cannot reopen an old window")
    void latePaymentDoesNotReopen() {
        var window = policy.windowFor(T, T.plus(Duration.ofHours(2)));
        assertThat(window.closesAt()).isEqualTo(T.plus(Duration.ofMinutes(15)));
    }
}
