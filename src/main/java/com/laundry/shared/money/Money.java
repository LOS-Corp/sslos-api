package com.laundry.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount of Vietnamese đồng as a whole number (SRS 5.2: money in VND as integer amounts).
 * Arithmetic never silently loses đồng: conversions from decimals must already be whole.
 */
public record Money(long vnd) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public static Money vnd(long amount) {
        return new Money(amount);
    }

    /** Converts a legacy NUMERIC amount; fails if it carries fractional đồng. */
    public static Money of(BigDecimal amount) {
        return new Money(amount.setScale(0, RoundingMode.UNNECESSARY).longValueExact());
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(vnd, other.vnd));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(vnd, other.vnd));
    }

    /** {@code percent} of this amount, rounded down so a refund never exceeds the rule (BR-12). */
    public Money percent(int percent) {
        return new Money(Math.multiplyExact(vnd, percent) / 100);
    }

    public Money negate() {
        return new Money(Math.negateExact(vnd));
    }

    public boolean isPositive() {
        return vnd > 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(vnd, other.vnd);
    }
}
