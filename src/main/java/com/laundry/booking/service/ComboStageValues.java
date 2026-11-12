package com.laundry.booking.service;

import com.laundry.refund.domain.RefundPolicy;
import com.laundry.shared.money.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/** Value of the dryer stage of a Wash–Dry order (BR-10, FR-REF-06). */
@Component
@RequiredArgsConstructor
class ComboStageValues {

    private final JdbcTemplate jdbc;

    /**
     * BR-10 needs both standalone prices. A store that sells Wash–Dry without one of them has no basis for
     * a proportion, so the order is split evenly between the two stages.
     */
    Money dryerStage(Money comboPrice) {
        BigDecimal wash = standalonePrice("WASH_ONLY");
        BigDecimal dry = standalonePrice("DRY_ONLY");
        if (wash == null || dry == null) {
            return Money.vnd(comboPrice.vnd() / 2);
        }
        return RefundPolicy.stageValue(comboPrice, Money.of(dry), Money.of(wash.add(dry)));
    }

    private BigDecimal standalonePrice(String serviceCode) {
        return jdbc.query("SELECT price FROM service_offerings WHERE service_code = ?",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, serviceCode);
    }
}
