package com.laundry.booking.service;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingOpeningHoursTest {
    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    @Test
    void allowsBookingToContinueIntoAdjacentOpenDay() {
        Map<DayOfWeek, ScheduledBookingService.ScheduleHours> hours = Map.of(
                DayOfWeek.MONDAY, hours("00:00", "23:59"),
                DayOfWeek.TUESDAY, hours("00:00", "23:59"));

        assertTrue(isCovered(hours, MONDAY, "23:45", MONDAY.plusDays(1), "00:30"));
    }

    @Test
    void rejectsBookingWhenAdjacentDayOpensAfterAClosedGap() {
        Map<DayOfWeek, ScheduledBookingService.ScheduleHours> hours = Map.of(
                DayOfWeek.MONDAY, hours("00:00", "23:59"),
                DayOfWeek.TUESDAY, hours("00:15", "23:59"));

        assertFalse(isCovered(hours, MONDAY, "23:45", MONDAY.plusDays(1), "00:30"));
    }

    @Test
    void allowsOvernightHoursToCoverTheEarlyHoursOfTheNextDay() {
        Map<DayOfWeek, ScheduledBookingService.ScheduleHours> hours = Map.of(
                DayOfWeek.MONDAY, hours("20:00", "02:00"));

        assertTrue(isCovered(hours, MONDAY, "23:45", MONDAY.plusDays(1), "01:30"));
    }

    @Test
    void rejectsBookingThatExtendsPastOpeningHours() {
        Map<DayOfWeek, ScheduledBookingService.ScheduleHours> hours = Map.of(
                DayOfWeek.MONDAY, hours("08:00", "18:00"));

        assertFalse(isCovered(hours, MONDAY, "17:45", MONDAY, "18:30"));
    }

    private static ScheduledBookingService.ScheduleHours hours(String opensAt, String closesAt) {
        return new ScheduledBookingService.ScheduleHours(
                LocalTime.parse(opensAt), LocalTime.parse(closesAt));
    }

    private static boolean isCovered(
            Map<DayOfWeek, ScheduledBookingService.ScheduleHours> hours,
            LocalDate startDate,
            String startTime,
            LocalDate endDate,
            String endTime) {
        Instant start = startDate.atTime(LocalTime.parse(startTime)).atZone(ZONE).toInstant();
        Instant end = endDate.atTime(LocalTime.parse(endTime)).atZone(ZONE).toInstant();
        return ScheduledBookingService.isCoveredByOpeningHours(hours, ZONE, start, end);
    }
}
