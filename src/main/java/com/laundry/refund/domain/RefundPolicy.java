package com.laundry.refund.domain;

import com.laundry.shared.money.Money;

import java.time.Duration;
import java.time.Instant;

/**
 * Cancellation and no-show refund rules (SRS 5.1). Pure function: no clock, no database.
 * <ul>
 *   <li>BR-13: device, locker, power, network or system failure → 100 % of the unused part, no violation.</li>
 *   <li>BR-11: customer cancels at or before T−60 → 100 %, no violation.</li>
 *   <li>BR-12: later cancellation or no-show → one violation; rank = events in [t−90d, t) + 1;
 *       refund rate by rank 100 / 70 / 50 / 0 %; refund = eligible unused amount × rate, rounded down.</li>
 * </ul>
 */
public final class RefundPolicy {

    /** Refund rate by violation rank 1, 2, 3, 4+ (BR-12). */
    static final int[] RATE_BY_RANK = {100, 70, 50, 0};
    public static final Duration VIOLATION_WINDOW = Duration.ofDays(90);

    private final Duration freeCancellationBefore;

    public RefundPolicy(Duration freeCancellationBefore) {
        this.freeCancellationBefore = freeCancellationBefore;
    }

    public RefundDecision evaluate(CancellationCause cause, Instant at, Instant bookedStart,
                                   int violationsInWindow, Money eligibleUnused) {
        if (cause == CancellationCause.SYSTEM_FAILURE) {
            return RefundDecision.full(eligibleUnused);
        }
        if (cause == CancellationCause.CUSTOMER_CANCEL && !at.isAfter(bookedStart.minus(freeCancellationBefore))) {
            return RefundDecision.full(eligibleUnused);
        }
        ViolationType violation = cause == CancellationCause.NO_SHOW
                ? ViolationType.NO_SHOW : ViolationType.LATE_CANCELLATION;
        int rank = violationsInWindow + 1;
        int rate = RATE_BY_RANK[Math.min(rank, RATE_BY_RANK.length) - 1];
        return new RefundDecision(eligibleUnused.percent(rate), rate, violation);
    }

    /** Last moment a customer may cancel for free (T−60). */
    public Instant freeCancellationDeadline(Instant bookedStart) {
        return bookedStart.minus(freeCancellationBefore);
    }

    /** BR-10: stage value of a combo = comboPrice × standalone(stage) / Σ standalone. */
    public static Money stageValue(Money comboPrice, Money standaloneStage, Money standaloneSum) {
        if (!standaloneSum.isPositive()) {
            throw new IllegalArgumentException("Sum of standalone prices must be positive");
        }
        return Money.vnd(Math.multiplyExact(comboPrice.vnd(), standaloneStage.vnd()) / standaloneSum.vnd());
    }

    public enum CancellationCause {
        CUSTOMER_CANCEL,
        NO_SHOW,
        SYSTEM_FAILURE
    }

    public enum ViolationType {
        LATE_CANCELLATION,
        NO_SHOW
    }

    /** How much goes back to the wallet, at which rate, and which violation (if any) is recorded. */
    public record RefundDecision(Money amount, int ratePercent, ViolationType violation) {

        static RefundDecision full(Money amount) {
            return new RefundDecision(amount, 100, null);
        }

        public boolean recordsViolation() {
            return violation != null;
        }
    }
}
