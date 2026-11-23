package com.laundry.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ảnh chụp các khoảng giữ máy đang active trong một cửa sổ thời gian, để tính lịch trống trong bộ nhớ.
 * Khoảng của đơn HOLD đã hết hạn giữ chỗ được coi như đã nhả (job sẽ nhả thật sau).
 */
public final class ScheduleSnapshot {

    public record Busy(UUID reservationId, UUID machineId, Instant start, Instant end, UUID orderId, boolean advance) {
        boolean overlaps(Instant s, Instant e) {
            return start.isBefore(e) && end.isAfter(s);
        }
    }

    private final Map<UUID, List<Busy>> byMachine = new HashMap<>();

    public ScheduleSnapshot(Collection<Busy> busy) {
        for (Busy b : busy) byMachine.computeIfAbsent(b.machineId(), k -> new ArrayList<>()).add(b);
        byMachine.values().forEach(l -> l.sort((a, b) -> a.start().compareTo(b.start())));
    }

    /** Máy trống trong [s, e), bỏ qua khoảng của {@code ignoreOrderId} (đổi giờ / chuyển máy chính đơn đó). */
    public boolean free(UUID machineId, Instant s, Instant e, UUID ignoreOrderId) {
        for (Busy b : byMachine.getOrDefault(machineId, List.of())) {
            if (ignoreOrderId != null && ignoreOrderId.equals(b.orderId())) continue;
            if (b.overlaps(s, e)) return false;
        }
        return true;
    }

    /** Lúc sớm nhất trong [from, latest] máy trống đủ {@code length}; null nếu không có. */
    public Instant earliestFree(UUID machineId, Instant from, Instant latest, java.time.Duration length) {
        List<Instant> candidates = new ArrayList<>();
        candidates.add(from);
        for (Busy b : byMachine.getOrDefault(machineId, List.of())) {
            if (b.end().isAfter(from) && !b.end().isAfter(latest)) candidates.add(b.end());
        }
        candidates.sort(Instant::compareTo);
        for (Instant c : candidates) {
            if (free(machineId, c, c.plus(length), null)) return c;
        }
        return null;
    }

    /** Khoảng giữ máy bắt đầu sau {@code t} gần nhất (lượt kế tiếp). */
    public Instant nextStartAfter(UUID machineId, Instant t, UUID ignoreOrderId) {
        Instant best = null;
        for (Busy b : byMachine.getOrDefault(machineId, List.of())) {
            if (ignoreOrderId != null && ignoreOrderId.equals(b.orderId())) continue;
            if (!b.start().isBefore(t) && (best == null || b.start().isBefore(best))) best = b.start();
        }
        return best;
    }

    /** Số máy (trong {@code machineIds}) đang có khoảng giữ của đơn đặt trước chạm vào [s, e). */
    public int advanceBusyMachines(Collection<UUID> machineIds, Instant s, Instant e, UUID ignoreOrderId) {
        Set<UUID> busy = new HashSet<>();
        for (UUID id : machineIds) {
            for (Busy b : byMachine.getOrDefault(id, List.of())) {
                if (b.advance() && b.overlaps(s, e) && (ignoreOrderId == null || !ignoreOrderId.equals(b.orderId()))) {
                    busy.add(id);
                    break;
                }
            }
        }
        return busy.size();
    }
}
