package com.laundry.money;

import com.laundry.shared.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void arithmeticStaysInWholeDong() {
        Money price = Money.vnd(50_000);

        assertThat(price.plus(Money.vnd(1_000))).isEqualTo(Money.vnd(51_000));
        assertThat(price.minus(Money.vnd(20_000))).isEqualTo(Money.vnd(30_000));
        assertThat(price.negate()).isEqualTo(Money.vnd(-50_000));
        assertThat(price.percent(70)).isEqualTo(Money.vnd(35_000));
        assertThat(Money.vnd(33_333).percent(70)).as("rounded down").isEqualTo(Money.vnd(23_333));
        assertThat(price.isPositive()).isTrue();
        assertThat(Money.ZERO.isPositive()).isFalse();
        assertThat(price.compareTo(Money.vnd(49_999))).isPositive();
    }

    @Test
    void convertsOnlyWholeDecimalAmounts() {
        assertThat(Money.of(new BigDecimal("25000.00"))).isEqualTo(Money.vnd(25_000));
        assertThatThrownBy(() -> Money.of(new BigDecimal("25000.50"))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void overflowIsAnErrorNotAWrapAround() {
        assertThatThrownBy(() -> Money.vnd(Long.MAX_VALUE).plus(Money.vnd(1))).isInstanceOf(ArithmeticException.class);
    }
}
