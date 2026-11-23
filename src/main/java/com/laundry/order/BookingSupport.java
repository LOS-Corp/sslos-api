package com.laundry.order;

import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.machine.MachineReservation;
import com.laundry.machine.MachineReservationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Công cụ dùng chung của module đơn: khóa lịch, ảnh chụp lịch, giữ / nhả / chuyển khoảng giữ máy. */
@Component
@RequiredArgsConstructor
public class BookingSupport {

    private final NamedParameterJdbcTemplate jdbc;
    private final MachineReservationRepository reservations;
    private final OrderLegRepository legs;
    private final ObjectMapper mapper;
    private final EntityManager em;
    private final Clock clock;

    /**
     * Mọi thao tác đổi lịch máy chạy tuần tự (một cửa hàng, lượng đặt nhỏ): tránh hai người cùng giữ một máy và
     * tránh vượt hạn mức đặt trước khi đặt đồng thời. EXCLUDE constraint ở DB vẫn là lớp chặn cuối.
     */
    public void lockSchedule() {
        jdbc.getJdbcTemplate().queryForObject("select pg_advisory_xact_lock(424242)", Object.class);
    }

    public OrderPolicy policy(Order o) {
        return mapper.readValue(o.getPolicySnapshot(), OrderPolicy.class);
    }

    public String json(OrderPolicy p) {
        return mapper.writeValueAsString(p);
    }

    public List<OrderLeg> legs(UUID orderId) {
        return legs.findByOrderIdOrderByLegNo(orderId);
    }

    public ScheduleSnapshot snapshot(Collection<UUID> machineIds, Instant from, Instant to) {
        if (machineIds.isEmpty()) return new ScheduleSnapshot(List.of());
        MapSqlParameterSource p = new MapSqlParameterSource("ids", machineIds)
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to))
                .addValue("now", Timestamp.from(clock.instant()));
        List<ScheduleSnapshot.Busy> busy = jdbc.query("""
                select r.id, r.machine_id, r.starts_at, r.ends_at, o.id as order_id, (o.channel = 'APP') as advance
                from machine_reservations r
                left join order_legs l on l.reservation_id = r.id
                left join orders o on o.id = l.order_id
                where r.machine_id in (:ids) and r.active and r.starts_at < :to and r.ends_at > :from
                  and not coalesce(o.status = 'HOLD' and o.hold_expires_at < :now, false)
                """, p, (rs, i) -> new ScheduleSnapshot.Busy(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), rs.getObject(5, UUID.class),
                rs.getBoolean(6)));
        return new ScheduleSnapshot(busy);
    }

    /** Nhả các khoảng của đơn HOLD đã hết giữ chỗ trên máy này (job sẽ đóng đơn sau), để giữ chỗ mới không đụng. */
    public void releaseExpiredHolds(UUID machineId, Instant from, Instant to) {
        jdbc.update("""
                update machine_reservations set active = false, version = version + 1
                where id in (select r.id from machine_reservations r
                             join order_legs l on l.reservation_id = r.id join orders o on o.id = l.order_id
                             where r.machine_id = :machine and r.active and o.status = 'HOLD' and o.hold_expires_at < :now
                               and r.starts_at < :to and r.ends_at > :from)
                """, new MapSqlParameterSource("machine", machineId).addValue("now", Timestamp.from(clock.instant()))
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)));
    }

    /** Giữ máy [start, end); đã kiểm trống trước (dưới khóa lịch). */
    public MachineReservation reserve(UUID machineId, Instant start, Instant end) {
        releaseExpiredHolds(machineId, start, end);
        if (!reservations.overlapping(machineId, start, end).isEmpty()) {
            throw new BusinessException(ErrorCode.MACHINE_UNAVAILABLE);
        }
        MachineReservation r = new MachineReservation();
        r.setMachineId(machineId);
        r.setKind(MachineReservation.Kind.ORDER_LEG);
        r.setStartsAt(start);
        r.setEndsAt(end);
        return reservations.saveAndFlush(r);
    }

    public MachineReservation reservation(OrderLeg leg) {
        return reservations.findById(leg.getReservationId()).orElseThrow();
    }

    /** Đổi khoảng giữ (co / giãn / dời). Không trống thì ném MACHINE_UNAVAILABLE. */
    public void resize(OrderLeg leg, Instant start, Instant end) {
        MachineReservation r = reservation(leg);
        if (!end.isAfter(start)) end = start.plusSeconds(60);
        if (start.isBefore(r.getStartsAt()) || end.isAfter(r.getEndsAt())) {
            List<MachineReservation> others = reservations.overlapping(r.getMachineId(), start, end).stream()
                    .filter(x -> !x.getId().equals(r.getId())).toList();
            if (!others.isEmpty()) throw new BusinessException(ErrorCode.MACHINE_UNAVAILABLE);
        }
        r.setStartsAt(start);
        r.setEndsAt(end);
        reservations.saveAndFlush(r);
    }

    /** Như {@link #resize} nhưng chỉ giãn được tới chỗ trống; trả false khi không giãn được. */
    public boolean tryResize(OrderLeg leg, Instant start, Instant end) {
        MachineReservation r = reservation(leg);
        List<MachineReservation> others = reservations.overlapping(r.getMachineId(), start, end).stream()
                .filter(x -> !x.getId().equals(r.getId())).toList();
        if (!others.isEmpty()) return false;
        r.setStartsAt(start);
        r.setEndsAt(end);
        reservations.saveAndFlush(r);
        return true;
    }

    public void release(OrderLeg leg) {
        reservations.findById(leg.getReservationId()).ifPresent(r -> {
            r.setActive(false);
            reservations.saveAndFlush(r);
        });
    }

    /**
     * Chuyển chặng sang máy khác giữ nguyên khung giờ (có thể dời). machine_id của chặng đi theo khoảng giữ
     * (FK kép ON UPDATE CASCADE), nên cập nhật bằng SQL rồi nạp lại entity.
     */
    public void move(OrderLeg leg, UUID machineId, Instant start, Instant end) {
        em.flush();
        MachineReservation r = reservation(leg);
        jdbc.update("""
                update machine_reservations set machine_id = :machine, starts_at = :start, ends_at = :end,
                       version = version + 1, updated_at = now() where id = :id
                """, new MapSqlParameterSource("machine", machineId).addValue("start", Timestamp.from(start))
                .addValue("end", Timestamp.from(end)).addValue("id", r.getId()));
        em.refresh(r);
        em.refresh(leg);
    }
}
