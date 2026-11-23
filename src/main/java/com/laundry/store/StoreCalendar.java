package com.laundry.store;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bản chụp lịch của cửa hàng (giờ mở cửa, giờ có nhân viên, ngày nghỉ) để tính nhanh, không chạm DB.
 * Khung giờ không qua đêm (CHECK closes_at > opens_at).
 */
public final class StoreCalendar {

    public record Window(LocalTime opens, LocalTime closes) {
    }

    public record Closure(Instant startsAt, Instant endsAt) {
    }

    private final ZoneId zone;
    private final Map<DayOfWeek, List<Window>> open;
    private final Map<DayOfWeek, List<Window>> staffed;
    private final List<Closure> closures;

    public StoreCalendar(ZoneId zone, List<StoreHour> hours, List<Closure> closures) {
        this.zone = zone;
        this.open = group(hours, StoreHour.Kind.OPEN);
        this.staffed = group(hours, StoreHour.Kind.STAFFED);
        this.closures = List.copyOf(closures);
    }

    public ZoneId zone() {
        return zone;
    }

    public boolean hasOpeningHours() {
        return open.values().stream().anyMatch(l -> !l.isEmpty());
    }

    /**
     * Khoảng [start, end) nằm trọn trong một khung mở cửa của cùng một ngày và không chạm ngày nghỉ.
     * Dùng với end = lúc xong phần lấy đồ (§6.3).
     */
    public boolean fitsOpen(Instant start, Instant end) {
        return windowContaining(start).filter(w -> !end.isAfter(w[1])).isPresent() && !closed(start, end);
    }

    /** Giờ đóng cửa của khung đang mở lúc {@code t}; rỗng khi đang đóng cửa. */
    public Optional<Instant> closingAfter(Instant t) {
        if (closed(t, t.plusSeconds(1))) return Optional.empty();
        return windowContaining(t).map(w -> w[1]);
    }

    public boolean isOpen(Instant t) {
        return closingAfter(t).isPresent();
    }

    /** Có nhân viên trực lúc {@code t}. */
    public boolean isStaffed(Instant t) {
        ZonedDateTime local = t.atZone(zone);
        LocalTime time = local.toLocalTime();
        return staffed.getOrDefault(local.getDayOfWeek(), List.of()).stream()
                .anyMatch(w -> !time.isBefore(w.opens()) && time.isBefore(w.closes()));
    }

    /** Các khung mở cửa của một ngày (theo giờ cửa hàng), đã trừ ngày nghỉ ở mức "cả khung". */
    public List<Instant[]> openWindows(LocalDate day) {
        List<Instant[]> out = new ArrayList<>();
        for (Window w : open.getOrDefault(day.getDayOfWeek(), List.of())) {
            Instant from = day.atTime(w.opens()).atZone(zone).toInstant();
            Instant to = day.atTime(w.closes()).atZone(zone).toInstant();
            out.add(new Instant[]{from, to});
        }
        return out;
    }

    public boolean closed(Instant start, Instant end) {
        return closures.stream().anyMatch(c -> c.startsAt().isBefore(end) && c.endsAt().isAfter(start));
    }

    private Optional<Instant[]> windowContaining(Instant t) {
        ZonedDateTime local = t.atZone(zone);
        LocalTime time = local.toLocalTime();
        for (Window w : open.getOrDefault(local.getDayOfWeek(), List.of())) {
            if (!time.isBefore(w.opens()) && time.isBefore(w.closes())) {
                LocalDate d = local.toLocalDate();
                return Optional.of(new Instant[]{d.atTime(w.opens()).atZone(zone).toInstant(),
                        d.atTime(w.closes()).atZone(zone).toInstant()});
            }
        }
        return Optional.empty();
    }

    private static Map<DayOfWeek, List<Window>> group(List<StoreHour> hours, StoreHour.Kind kind) {
        Map<DayOfWeek, List<Window>> map = new EnumMap<>(DayOfWeek.class);
        for (StoreHour h : hours) {
            if (h.getKind() != kind) continue;
            map.computeIfAbsent(DayOfWeek.of(h.getDayOfWeek()), d -> new ArrayList<>())
                    .add(new Window(h.getOpensAt(), h.getClosesAt()));
        }
        return map;
    }
}
