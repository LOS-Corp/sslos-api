package com.laundry.order;

import com.laundry.common.Money;
import com.laundry.refund.Refund;
import com.laundry.store.StoreCalendar;
import com.laundry.store.StoreHour;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Quy tắc nghiệp vụ thuần (00_NGHIEP_VU.md), không cần DB. */
class BusinessRulesTest {

    /** Tham số mặc định §18. */
    static final OrderPolicy P = new OrderPolicy(15, 10, 3, 10, 5, 10, 10, 20, 20, 1000, 60, 10, 60);
    static final Instant T = Instant.parse("2026-12-01T03:00:00Z");   // 10:00 giờ VN

    @Nested
    class MoneyRules {
        @Test
        void depositIsTwentyPercentRoundedUpToThousand() {
            assertThat(Money.percentRoundedUp(55_000, 20, 1000)).isEqualTo(11_000);
            assertThat(Money.percentRoundedUp(30_000, 20, 1000)).isEqualTo(6_000);
            assertThat(Money.percentRoundedUp(12_345, 20, 1000)).isEqualTo(3_000);   // 2.469 -> 3.000
            assertThat(Money.percentRoundedUp(500, 20, 1000)).isEqualTo(500);        // không vượt tổng
            assertThat(Money.percentRoundedUp(30_000, 100, 1000)).isEqualTo(30_000);
        }

        @Test
        void formatsVnd() {
            assertThat(Money.format(1_234_000)).isEqualTo("1.234.000 đ");
        }
    }

    @Nested
    class TimelineRules {
        @Test
        void firstLegHoldsGraceStartRunUnloadCleanup() {
            Timeline.Window w = Timeline.firstLeg(T, 35, P);
            assertThat(w.start()).isEqualTo(T);
            assertThat(Duration.between(T, w.unloadEnd()).toMinutes()).isEqualTo(10 + 3 + 35 + 10);
            assertThat(Duration.between(T, w.end()).toMinutes()).isEqualTo(10 + 3 + 35 + 10 + 5);
        }

        @Test
        void comboDryerStartsWhenWasherEndsAtTheLatest() {
            Timeline.Window d = Timeline.dryerAfterWash(T, 35, 40, P);
            assertThat(Duration.between(T, d.start()).toMinutes()).isEqualTo(48);
            assertThat(Duration.between(d.start(), d.end()).toMinutes()).isEqualTo(10 + 40 + 10 + 5);
        }

        @Test
        void startedMachineGivesBackUnusedGrace() {
            Instant started = T.plusSeconds(60);
            assertThat(Timeline.endAfterStart(started, 35, 0, P)).isEqualTo(started.plusSeconds(60L * (35 + 10 + 5)));
            assertThat(Timeline.endAfterStart(started, 35, 0, P)).isBefore(Timeline.firstLeg(T, 35, P).end());
        }
    }

    @Nested
    class Cancellation {
        Order order(Order.Channel channel, Instant createdAt, long paid) {
            Order o = new Order();
            o.setChannel(channel);
            o.setStatus(Order.Status.CONFIRMED);
            o.setScheduledStartAt(T);
            o.setCreatedAt(createdAt);
            o.setTotalAmount(55_000);
            o.setDepositAmount(11_000);
            o.setPaidAmount(paid);
            return o;
        }

        @Test
        void freeBeforeTMinus60() {
            Order o = order(Order.Channel.APP, T.minus(Duration.ofDays(1)), 55_000);
            CancellationRules.Outcome r = CancellationRules.cancel(o, P, T.minus(Duration.ofMinutes(61)));
            assertThat(r.kind()).isEqualTo(Order.CancellationKind.FREE);
            assertThat(r.refund()).isEqualTo(55_000);
            assertThat(r.forfeit()).isZero();
        }

        @Test
        void freeWithinTenMinutesOfBookingEvenCloseToT() {
            Instant booked = T.minus(Duration.ofMinutes(20));
            Order o = order(Order.Channel.APP, booked, 11_000);
            assertThat(CancellationRules.cancel(o, P, booked.plus(Duration.ofMinutes(9))).kind())
                    .isEqualTo(Order.CancellationKind.FREE);
            assertThat(CancellationRules.cancel(o, P, booked.plus(Duration.ofMinutes(11))).kind())
                    .isEqualTo(Order.CancellationKind.LATE);
        }

        @Test
        void lateCancelKeepsDepositAndRefundsTheRest() {
            Order o = order(Order.Channel.APP, T.minus(Duration.ofDays(1)), 55_000);
            CancellationRules.Outcome r = CancellationRules.cancel(o, P, T.minus(Duration.ofMinutes(30)));
            assertThat(r.kind()).isEqualTo(Order.CancellationKind.LATE);
            assertThat(r.forfeit()).isEqualTo(11_000);
            assertThat(r.refund()).isEqualTo(44_000);
            assertThat(r.reason()).isEqualTo(Refund.Reason.LATE_CANCEL_EXCESS);
        }

        @Test
        void noShowWithOnlyDepositPaidLosesDepositOnly() {
            Order o = order(Order.Channel.APP, T.minus(Duration.ofDays(1)), 11_000);
            CancellationRules.Outcome r = CancellationRules.noShow(o, P);
            assertThat(r.forfeit()).isEqualTo(11_000);
            assertThat(r.refund()).isZero();
        }

        @Test
        void penaltyCustomerPaysFullButStillLosesOnlyTwentyPercent() {
            OrderPolicy penalty = new OrderPolicy(15, 10, 3, 10, 5, 10, 10, 100, 20, 1000, 60, 10, 60);
            Order o = order(Order.Channel.APP, T.minus(Duration.ofDays(1)), 55_000);
            CancellationRules.Outcome r = CancellationRules.noShow(o, penalty);
            assertThat(r.forfeit()).isEqualTo(11_000);
            assertThat(r.refund()).isEqualTo(44_000);
        }

        @Test
        void kioskCancelBeforeStartRefundsAllAndAbandonRefundsEighty() {
            Order o = order(Order.Channel.KIOSK, T.minusSeconds(60), 55_000);
            assertThat(CancellationRules.cancel(o, P, T.plusSeconds(60)).refund()).isEqualTo(55_000);
            CancellationRules.Outcome abandon = CancellationRules.noShow(o, P);
            assertThat(abandon.refund()).isEqualTo(44_000);
            assertThat(abandon.reason()).isEqualTo(Refund.Reason.ABANDONED_80);
        }

        @Test
        void rescheduleOnlyOnceAndBeforeTMinus60() {
            Order o = order(Order.Channel.APP, T.minus(Duration.ofDays(1)), 11_000);
            assertThat(CancellationRules.canReschedule(o, P, T.minus(Duration.ofMinutes(61)))).isTrue();
            assertThat(CancellationRules.canReschedule(o, P, T.minus(Duration.ofMinutes(59)))).isFalse();
            o.setRescheduleCount(1);
            assertThat(CancellationRules.canReschedule(o, P, T.minus(Duration.ofHours(5)))).isFalse();
        }
    }

    @Nested
    class Violations {
        Instant now = Instant.parse("2026-12-01T00:00:00Z");

        @Test
        void threeViolationsInNinetyDaysTriggerThirtyDayPenalty() {
            List<Instant> v = List.of(now.minus(Duration.ofDays(80)), now.minus(Duration.ofDays(40)),
                    now.minus(Duration.ofDays(10)));
            assertThat(ViolationPolicy.penaltyActive(v, now, 3, 90, 30)).isTrue();
            assertThat(ViolationPolicy.penaltyActive(v, now.plus(Duration.ofDays(21)), 3, 90, 30)).isFalse();
        }

        @Test
        void violationsSpreadOverMoreThanNinetyDaysDoNotCount() {
            List<Instant> v = List.of(now.minus(Duration.ofDays(120)), now.minus(Duration.ofDays(40)),
                    now.minus(Duration.ofDays(10)));
            assertThat(ViolationPolicy.penaltyActive(v, now, 3, 90, 30)).isFalse();
        }
    }

    @Nested
    class Calendar {
        StoreCalendar cal() {
            List<StoreHour> hours = new ArrayList<>();
            for (int d = 1; d <= 7; d++) {
                StoreHour h = new StoreHour();
                h.setKind(StoreHour.Kind.OPEN);
                h.setDayOfWeek(d);
                h.setOpensAt(LocalTime.of(6, 0));
                h.setClosesAt(LocalTime.of(22, 0));
                hours.add(h);
            }
            return new StoreCalendar(ZoneId.of("Asia/Ho_Chi_Minh"), hours, List.of());
        }

        @Test
        void unloadMustEndBeforeClosing() {
            Instant at2100 = Instant.parse("2026-12-01T14:00:00Z");   // 21:00 VN
            assertThat(cal().fitsOpen(at2100, at2100.plus(Duration.ofMinutes(58)))).isTrue();
            assertThat(cal().fitsOpen(at2100, at2100.plus(Duration.ofMinutes(61)))).isFalse();
            assertThat(cal().isOpen(Instant.parse("2026-11-30T22:30:00Z"))).isFalse();   // 05:30 VN
        }
    }

    @Nested
    class Schedule {
        @Test
        void walkInFindsFirstGapAfterCurrentUse() {
            UUID m = UUID.randomUUID();
            Instant now = T;
            ScheduleSnapshot s = new ScheduleSnapshot(List.of(
                    new ScheduleSnapshot.Busy(UUID.randomUUID(), m, now.minusSeconds(600), now.plusSeconds(600), null, false),
                    new ScheduleSnapshot.Busy(UUID.randomUUID(), m, now.plusSeconds(4200), now.plusSeconds(8000), null, true)));
            Instant at = s.earliestFree(m, now, now.plus(Duration.ofMinutes(30)), Duration.ofMinutes(55));
            assertThat(at).isEqualTo(now.plusSeconds(600));
            // Khe trống 03:10-04:10 dài đúng 60': lượt 60' vừa khít, lượt 61' không lọt trước lượt đặt trước kế tiếp.
            assertThat(s.earliestFree(m, now, now.plus(Duration.ofMinutes(30)), Duration.ofMinutes(60)))
                    .isEqualTo(now.plusSeconds(600));
            assertThat(s.earliestFree(m, now, now.plus(Duration.ofMinutes(30)), Duration.ofMinutes(61))).isNull();
            assertThat(s.advanceBusyMachines(List.of(m), now.plusSeconds(5000), now.plusSeconds(6000), null)).isEqualTo(1);
        }
    }
}
