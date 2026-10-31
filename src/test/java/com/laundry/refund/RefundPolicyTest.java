package com.laundry.refund;

import com.laundry.refund.domain.RefundPolicy;
import com.laundry.refund.domain.RefundPolicy.CancellationCause;
import com.laundry.refund.domain.RefundPolicy.RefundDecision;
import com.laundry.refund.domain.RefundPolicy.ViolationType;
import com.laundry.shared.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BR-10…BR-13 as a table — each row is an acceptance scenario of SRS 5.4.2. */
class RefundPolicyTest {

    private static final Instant T = Instant.parse("2026-11-01T03:00:00Z");
    private final RefundPolicy policy = new RefundPolicy(Duration.ofMinutes(60));

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "'SC-06 cancel exactly at T-60 → 100 %, no violation',  -60, 0, 50000, 50000, 100, ",
            "cancel at T-61 → 100 %,                               -61, 3, 50000, 50000, 100, ",
            "first late cancel at T-30 → 100 % but recorded,       -30, 0, 50000, 50000, 100, LATE_CANCELLATION",
            "'SC-29 second late cancel → 70 % (35,000)',           -30, 1, 50000, 35000,  70, LATE_CANCELLATION",
            "SC-07 third event in 90 days → 50 %,                  -30, 2, 50000, 25000,  50, LATE_CANCELLATION",
            "fourth event → nothing,                               -30, 3, 50000,     0,   0, LATE_CANCELLATION",
            "rounding never exceeds the rule,                      -30, 1, 33333, 23333,  70, LATE_CANCELLATION"
    })
    void customerCancellation(String scenario, int minutesFromStart, int priorEvents, long paid,
                              long expectedRefund, int expectedRate, ViolationType expectedViolation) {
        RefundDecision decision = policy.evaluate(CancellationCause.CUSTOMER_CANCEL,
                T.plus(Duration.ofMinutes(minutesFromStart)), T, priorEvents, Money.vnd(paid));

        assertThat(decision.amount()).isEqualTo(Money.vnd(expectedRefund));
        assertThat(decision.ratePercent()).isEqualTo(expectedRate);
        assertThat(decision.violation()).isEqualTo(expectedViolation);
    }

    @Test
    @DisplayName("NO_SHOW counts as a violation with the same rank table")
    void noShow() {
        RefundDecision decision = policy.evaluate(CancellationCause.NO_SHOW, T.plusSeconds(600), T, 1, Money.vnd(40000));
        assertThat(decision.violation()).isEqualTo(ViolationType.NO_SHOW);
        assertThat(decision.amount()).isEqualTo(Money.vnd(28000));
    }

    @Test
    @DisplayName("SC-35 / BR-13: system failure refunds 100 % and is never a violation, whatever the history")
    void systemFailure() {
        RefundDecision decision = policy.evaluate(CancellationCause.SYSTEM_FAILURE, T.plusSeconds(600), T, 9,
                Money.vnd(40000));
        assertThat(decision.amount()).isEqualTo(Money.vnd(40000));
        assertThat(decision.recordsViolation()).isFalse();
    }

    @Test
    @DisplayName("SC-37 / BR-10: stage value of a combo is proportional to standalone prices")
    void comboStageValue() {
        // Wash 30,000 + Dry 30,000 standalone; combo 50,000 → dryer stage worth 25,000.
        assertThat(RefundPolicy.stageValue(Money.vnd(50000), Money.vnd(30000), Money.vnd(60000)))
                .isEqualTo(Money.vnd(25000));
        assertThatThrownBy(() -> RefundPolicy.stageValue(Money.vnd(1), Money.vnd(1), Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freeCancellationDeadlineIsTMinus60() {
        assertThat(policy.freeCancellationDeadline(T)).isEqualTo(T.minus(Duration.ofMinutes(60)));
    }
}
