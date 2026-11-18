package com.laundry.booking.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Wash-by-weight rules, pure so they are unit-testable.
 *
 * <ul>
 *   <li>The customer books (and pays for) whole kilograms.</li>
 *   <li>The scale may read up to {@link #FREE_TOLERANCE_KG} over the paid kilograms at no cost.</li>
 *   <li>Above that the customer pays the difference rounded up to whole kilograms
 *       (5 kg paid, 5.7 kg weighed: 1 kg more; 6.6 kg: 2 kg more), or takes laundry out.</li>
 *   <li>Above the machine's capacity the laundry does not fit: the customer moves to a larger washer or
 *       takes laundry out. A machine without a declared capacity has no limit.</li>
 * </ul>
 */
public final class LoadPolicy {

    public static final BigDecimal FREE_TOLERANCE_KG = new BigDecimal("0.5");

    /** Smallest and largest load a customer can book. */
    public static final int MIN_BOOKED_KG = 1;
    public static final int MAX_BOOKED_KG = 50;

    private LoadPolicy() {
    }

    public enum Kind { ACCEPTED, OVER_BOOKED, OVER_CAPACITY }

    /**
     * @param extraKg whole kilograms to pay for on top of the booked ones (0 when within the tolerance)
     */
    public record Verdict(Kind kind, BigDecimal measuredKg, BigDecimal bookedKg, BigDecimal capacityKg, int extraKg) {
        public boolean accepted() {
            return kind == Kind.ACCEPTED;
        }
    }

    /**
     * @param measuredKg scale reading; negative noise counts as 0
     * @param bookedKg   kilograms paid for; null = the booking was not priced by weight (always accepted
     *                   unless it exceeds the capacity)
     * @param capacityKg rated load of the machine; null = unknown, never blocks
     */
    public static Verdict evaluate(BigDecimal measuredKg, BigDecimal bookedKg, BigDecimal capacityKg) {
        BigDecimal measured = measuredKg == null || measuredKg.signum() < 0 ? BigDecimal.ZERO
                : measuredKg.setScale(2, RoundingMode.HALF_UP);
        int extra = extraKg(measured, bookedKg);
        if (capacityKg != null && measured.compareTo(capacityKg) > 0) {
            return new Verdict(Kind.OVER_CAPACITY, measured, bookedKg, capacityKg, extra);
        }
        return new Verdict(extra > 0 ? Kind.OVER_BOOKED : Kind.ACCEPTED, measured, bookedKg, capacityKg, extra);
    }

    /** Whole kilograms to add to {@code bookedKg} so {@code measuredKg} is within the tolerance. */
    public static int extraKg(BigDecimal measuredKg, BigDecimal bookedKg) {
        if (bookedKg == null || measuredKg == null) {
            return 0;
        }
        if (measuredKg.compareTo(bookedKg.add(FREE_TOLERANCE_KG)) <= 0) {
            return 0;
        }
        return measuredKg.subtract(bookedKg).setScale(0, RoundingMode.CEILING).intValueExact();
    }

    /** Price of a weight-priced service: fixed part + per-kg price x kilograms. */
    public static BigDecimal price(BigDecimal fixedPart, BigDecimal pricePerKg, BigDecimal kg) {
        BigDecimal fixed = fixedPart == null ? BigDecimal.ZERO : fixedPart;
        if (pricePerKg == null || kg == null) {
            return fixed;
        }
        return fixed.add(pricePerKg.multiply(kg));
    }

    /** A bookable load: a whole number of kilograms in range. */
    public static boolean isBookableKg(BigDecimal kg) {
        if (kg == null || kg.signum() <= 0) {
            return false;
        }
        BigDecimal stripped = kg.stripTrailingZeros();
        return stripped.scale() <= 0
                && kg.compareTo(BigDecimal.valueOf(MIN_BOOKED_KG)) >= 0
                && kg.compareTo(BigDecimal.valueOf(MAX_BOOKED_KG)) <= 0;
    }
}
