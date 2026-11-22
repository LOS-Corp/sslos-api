package com.laundry.booking;

import com.laundry.booking.service.BookingAvailabilityService;
import com.laundry.booking.service.LoadTopUpService;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.machine.dto.PriceProfileDtos.PriceProfileRequest;
import com.laundry.machine.dto.PriceProfileDtos.PriceProfileView;
import com.laundry.machine.dto.UpdateMachineRequest;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.service.MachineCatalogService;
import com.laundry.machine.service.PriceProfileService;
import com.laundry.shared.error.BusinessException;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V64/V65: self-service is priced per cycle, by the machine's price profile or else the service price. */
@IntegrationTest
@DisplayName("Price profiles and per-cycle self-service")
class PriceProfileIT {

    @Autowired private PriceProfileService profiles;
    @Autowired private MachineCatalogService catalog;
    @Autowired private ScheduledBookingService bookings;
    @Autowired private BookingAvailabilityService availability;
    @Autowired private LoadTopUpService topUps;
    @Autowired private com.laundry.booking.service.StoreBookingConfigService storeConfig;
    @Autowired private JdbcTemplate jdbc;

    private Instant tomorrow;

    @BeforeEach
    void setUp() {
        Fixtures.bookableStore(jdbc, 30_000);
        // V65: WASH_ONLY is a self-service, priced per cycle.
        jdbc.update("UPDATE service_offerings SET price = 30000, price_per_kg = NULL WHERE service_code = 'WASH_ONLY'");
        tomorrow = LocalDate.now(Fixtures.ZONE).plusDays(1).atTime(10, 0).atZone(Fixtures.ZONE).toInstant();
    }

    @Test
    @DisplayName("A washer with a profile costs its price per cycle, whatever the load; no extra kilograms to buy")
    void profilePricesTheBooking() {
        PriceProfileView profile = profiles.create(new PriceProfileRequest("Giặt lớn " + suffix(), MachineType.WASHER,
                new BigDecimal("45000"), null));
        UUID washer = Fixtures.washer(jdbc);
        assertThat(catalog.updateMachine(code(washer), UpdateMachineRequest.builder().priceProfileId(profile.id()).build())
                .getPriceProfileName()).isEqualTo(profile.name());

        var search = availability.machineSearch("WASH_ONLY", tomorrow, tomorrow.plusSeconds(3600), null);
        assertThat(search.options()).filteredOn(o -> o.resources().getFirst().resourceId().equals(washer))
                .singleElement().satisfies(o -> assertThat(o.price()).isEqualByComparingTo("45000"));

        var booking = bookings.createHold(UUID.randomUUID(), new CreateHoldRequest("WASH_ONLY", tomorrow,
                List.of(washer), "pp-" + UUID.randomUUID(), null, null, new BigDecimal("7")));
        assertThat(booking.totalAmount()).isEqualByComparingTo("45000");
        assertThat(topUps.bookingLoad(booking.bookingId()).pricePerKg()).isNull();
    }

    @Test
    @DisplayName("V68: a machine without a profile is not bookable; the service price never prices a machine")
    void noServiceFallback() {
        UUID washer = Fixtures.washer(jdbc);
        jdbc.update("UPDATE service_offerings SET price = 99000 WHERE service_code = 'WASH_ONLY'");
        // An active machine keeps its profile.
        assertThatThrownBy(() -> catalog.updateMachine(code(washer),
                UpdateMachineRequest.builder().clearPriceProfile(true).build())).isInstanceOf(BusinessException.class);

        var booking = bookings.createHold(UUID.randomUUID(), new CreateHoldRequest("WASH_ONLY", tomorrow.plusSeconds(7200),
                List.of(washer), "pp-" + UUID.randomUUID(), null, null, null));
        assertThat(booking.totalAmount()).isEqualByComparingTo("30000");

        jdbc.update("UPDATE machines SET price_profile_id = NULL WHERE id = ?", washer);
        var search = availability.machineSearch("WASH_ONLY", tomorrow, tomorrow.plusSeconds(3600), null);
        assertThat(search.options()).noneMatch(o -> o.resources().getFirst().resourceId().equals(washer));
        assertThatThrownBy(() -> bookings.createHold(UUID.randomUUID(), new CreateHoldRequest("WASH_ONLY",
                tomorrow.plusSeconds(4 * 3600), List.of(washer), "pp-" + UUID.randomUUID(), null, null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Profiles must charge something, fit the machine type, and cannot be deleted while used")
    void rules() {
        assertThatThrownBy(() -> profiles.create(new PriceProfileRequest("Miễn phí " + suffix(), MachineType.WASHER,
                BigDecimal.ZERO, null))).isInstanceOf(BusinessException.class);
        PriceProfileView dryerProfile = profiles.create(new PriceProfileRequest("Sấy " + suffix(), MachineType.DRYER,
                new BigDecimal("20000"), null));
        UUID washer = Fixtures.washer(jdbc);
        assertThatThrownBy(() -> catalog.updateMachine(code(washer),
                UpdateMachineRequest.builder().priceProfileId(dryerProfile.id()).build()))
                .isInstanceOf(BusinessException.class);

        UUID dryer = Fixtures.dryer(jdbc);
        catalog.updateMachine(code(dryer), UpdateMachineRequest.builder().priceProfileId(dryerProfile.id()).build());
        assertThatThrownBy(() -> profiles.delete(dryerProfile.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> profiles.create(new PriceProfileRequest(dryerProfile.name().toUpperCase(),
                MachineType.DRYER, BigDecimal.ONE, null))).isInstanceOf(BusinessException.class);
        assertThat(profiles.list()).filteredOn(p -> p.id().equals(dryerProfile.id())).singleElement()
                .satisfies(p -> assertThat(p.machineCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("V68: customers see the cheapest washer's profile as the 'from' price; the service price is ignored")
    void startingPriceFromProfiles() {
        UUID cheap = Fixtures.washer(jdbc);
        PriceProfileView profile = profiles.create(new PriceProfileRequest("Rẻ nhất " + suffix(), MachineType.WASHER,
                new BigDecimal("500"), 230, null, null, null));
        catalog.updateMachine(code(cheap), UpdateMachineRequest.builder().priceProfileId(profile.id()).build());
        jdbc.update("UPDATE service_offerings SET price = 99000 WHERE service_code = 'WASH_ONLY'");

        assertThat(availability.listOfferings()).filteredOn(o -> o.serviceCode().equals("WASH_ONLY")).singleElement()
                .satisfies(o -> {
                    assertThat(o.price()).isEqualByComparingTo("500");
                    assertThat(o.priceFromMachines()).isTrue();
                    // V69: the longest washer cycle holds the machine, not the service's own duration.
                    assertThat(o.processingMinutes()).isEqualTo(230);
                });
        assertThat(storeConfig.listOfferings()).filteredOn(o -> o.code().equals("WASH_ONLY")).singleElement()
                .satisfies(o -> {
                    assertThat(o.priceByMachine()).isTrue();
                    assertThat(o.processingMinutes()).isEqualTo(230);
                });
        jdbc.update("UPDATE machines SET price_profile_id = NULL, deployment_status = 'PROVISIONED' WHERE id = ?", cheap);
    }

    private String code(UUID machineId) {
        return jdbc.queryForObject("SELECT machine_code FROM machines WHERE id = ?", String.class, machineId);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
