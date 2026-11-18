package com.laundry.booking.model;

import com.laundry.booking.domain.LoadPolicy;
import com.laundry.booking.domain.LoadPolicy.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LoadPolicyTest {

    private static BigDecimal kg(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    @ParameterizedTest(name = "paid {1} kg, weighed {0} kg, capacity {2} kg -> {3} (+{4} kg)")
    @CsvSource({
            // within the paid weight or up to 0.5 kg over: free
            "4.20, 5, 10, ACCEPTED, 0",
            "5.00, 5, 10, ACCEPTED, 0",
            "5.50, 5, 10, ACCEPTED, 0",
            // over by more than 0.5 kg: pay the difference rounded up to whole kilograms
            "5.51, 5, 10, OVER_BOOKED, 1",
            "5.70, 5, 10, OVER_BOOKED, 1",
            "6.60, 5, 10, OVER_BOOKED, 2",
            "7.00, 5, 10, OVER_BOOKED, 2",
            // over the machine's capacity: move or take laundry out (the extra kg is still reported)
            "10.01, 5, 10, OVER_CAPACITY, 6",
            "7.30, 7, 7, OVER_CAPACITY, 0",
            // unknown capacity never blocks
            "30.00, 5, , OVER_BOOKED, 25",
            // a booking not priced by weight only checks the capacity
            "6.00, , 10, ACCEPTED, 0",
            // scale noise below zero counts as empty
            "-0.03, 1, 10, ACCEPTED, 0",
    })
    void evaluates(String measured, String paid, String capacity, Kind kind, int extraKg) {
        var verdict = LoadPolicy.evaluate(kg(measured), kg(paid), kg(capacity));
        assertThat(verdict.kind()).isEqualTo(kind);
        assertThat(verdict.extraKg()).isEqualTo(extraKg);
    }

    @Test
    @DisplayName("Price = fixed part + per-kg price x kilograms")
    void prices() {
        assertThat(LoadPolicy.price(BigDecimal.ZERO, new BigDecimal("10000"), new BigDecimal("5")))
                .isEqualByComparingTo("50000");
        assertThat(LoadPolicy.price(new BigDecimal("20000"), new BigDecimal("10000"), new BigDecimal("3")))
                .isEqualByComparingTo("50000");
        assertThat(LoadPolicy.price(new BigDecimal("20000"), null, new BigDecimal("3")))
                .isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("Only whole kilograms from 1 to 50 can be booked")
    void bookableKilograms() {
        assertThat(LoadPolicy.isBookableKg(new BigDecimal("1"))).isTrue();
        assertThat(LoadPolicy.isBookableKg(new BigDecimal("7.0"))).isTrue();
        assertThat(LoadPolicy.isBookableKg(new BigDecimal("50"))).isTrue();
        assertThat(LoadPolicy.isBookableKg(new BigDecimal("2.5"))).isFalse();
        assertThat(LoadPolicy.isBookableKg(new BigDecimal("0"))).isFalse();
        assertThat(LoadPolicy.isBookableKg(new BigDecimal("51"))).isFalse();
        assertThat(LoadPolicy.isBookableKg(null)).isFalse();
    }
}
