package com.laundry.order;

import com.laundry.device.DeviceCommandService;
import com.laundry.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Các mốc thời gian của đơn (§6, §8, §9, §10, §11). Mỗi việc một transaction; lỗi một đơn không chặn đơn khác. */
@Component
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OrderJobs {

    /** Máy mất kết nối quá lâu thì chuyển các lượt sắp bắt đầu sang máy khác (§12). */
    static final Duration OFFLINE_GRACE = Duration.ofMinutes(2);
    static final Duration REASSIGN_AHEAD = Duration.ofMinutes(15);

    private final OrderRepository orders;
    private final OrderLegRepository legs;
    private final BookingService booking;
    private final MachineRunService runs;
    private final PaymentService payments;
    private final OfflineReassigner offline;
    private final BookingSupport support;
    private final Clock clock;

    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    public void tick() {
        Instant now = clock.instant();
        each("hold", orders.expiredHolds(now), booking::expireHold);
        each("intent", payments.expiredIntents(), payments::expire);
        each("no-show", orders.pastDeadlineNotStarted(List.of(Order.Status.CONFIRMED, Order.Status.IN_PROGRESS), now),
                booking::noShow);
        each("activation", legs.activationExpired(now).stream().map(OrderLeg::getId).toList(), runs::expireActivation);
        each("dryer", legs.withStatus(OrderLeg.Status.SCHEDULED).stream()
                .filter(l -> l.getLegNo() == 2 && l.getStartDeadlineAt() != null && l.getActivationCount() == 0)
                .map(OrderLeg::getId).toList(), runs::activateDryerIfDue);
        each("offline", offline.candidates(now), offline::reassign);
    }

    /** Hết thời gian lấy đồ: chặng xong. Theo tham số của từng đơn nên lọc lại trong service. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 25_000)
    public void unload() {
        Instant now = clock.instant();
        each("unload", legs.unloadOver(now.minus(Duration.ofMinutes(1))).stream()
                .filter(l -> unloadDue(l, now)).map(OrderLeg::getId).toList(), runs::completeUnload);
    }

    private boolean unloadDue(OrderLeg leg, Instant now) {
        return orders.findById(leg.getOrderId())
                .map(o -> leg.getFinishedAt().plus(Duration.ofMinutes(support.policy(o).unloadMinutes())).isBefore(now))
                .orElse(false);
    }

    private static void each(String job, List<UUID> ids, Consumer<UUID> action) {
        for (UUID id : ids) {
            try {
                action.accept(id);
            } catch (RuntimeException e) {
                log.warn("[JOB {}] {} failed: {}", job, id, e.getMessage());
            }
        }
    }

    /** Chuyển lượt sắp tới khỏi máy mất kết nối. */
    @Component
    @RequiredArgsConstructor
    public static class OfflineReassigner {
        private final JdbcTemplate jdbc;
        private final OrderRepository orders;
        private final OrderLegRepository legs;
        private final ReassignmentService reassignment;
        private final BookingSupport support;
        private final Clock clock;

        List<UUID> candidates(Instant now) {
            return jdbc.queryForList("""
                    select l.id from order_legs l
                    join orders o on o.id = l.order_id
                    join machine_reservations r on r.id = l.reservation_id
                    left join devices d on d.machine_id = l.machine_id and d.link_state = 'BOUND'
                    where l.status = 'SCHEDULED' and o.status in ('CONFIRMED', 'IN_PROGRESS')
                      and r.starts_at < ? and r.ends_at > ?
                      and (d.id is null or (not d.online and coalesce(d.last_seen_at, 'epoch') < ?))
                    """, UUID.class, Timestamp.from(now.plus(REASSIGN_AHEAD)), Timestamp.from(now),
                    Timestamp.from(now.minus(OFFLINE_GRACE)));
        }

        @Transactional
        public void reassign(UUID legId) {
            support.lockSchedule();
            OrderLeg leg = legs.lock(legId).orElse(null);
            if (leg == null || leg.getStatus() != OrderLeg.Status.SCHEDULED) return;
            Order o = orders.lock(leg.getOrderId()).orElseThrow();
            if (o.isOpen()) reassignment.reassign(o, leg, "Máy mất kết nối");
        }
    }
}
