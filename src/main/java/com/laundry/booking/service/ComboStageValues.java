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
     * BR-10: the order's total split in proportion to its two stages. V68: washers and dryers are priced by their
     * price profiles, so the stages are the booked washer's and dryer's profiles. Orders whose machines had no profile
     * (before V68) fall back to the standalone service prices; without any basis the order is split evenly.
     */
    Money dryerStage(UUID bookingId, Money comboPrice) {
        BigDecimal wash = machinePrice(bookingId, "WASHER");
        BigDecimal dry = machinePrice(bookingId, "DRYER");
        if (!positive(wash) || !positive(dry)) {
            wash = standalonePrice("WASH_ONLY");
            dry = standalonePrice("DRY_ONLY");
        }
        if (!positive(wash) || !positive(dry)) {
            return Money.vnd(comboPrice.vnd() / 2);
        }
        return RefundPolicy.stageValue(comboPrice, Money.of(dry), Money.of(wash.add(dry)));
    }

    private BigDecimal machinePrice(UUID bookingId, String type) {
        return jdbc.query("""
                SELECT p.base_price FROM booking_resource_allocations a
                JOIN machines m ON m.id = a.resource_id
                JOIN price_profiles p ON p.id = m.price_profile_id
                WHERE a.booking_id = ? AND a.resource_type = ?
                ORDER BY a.occupied_from LIMIT 1
                """, rs -> rs.next() ? rs.getBigDecimal(1) : null, bookingId, type);
    }

    private BigDecimal standalonePrice(String serviceCode) {
        return jdbc.query("SELECT price FROM service_offerings WHERE service_code = ?",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, serviceCode);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
