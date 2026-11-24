package com.laundry.order;

import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.device.DeviceCommandService;
import com.laundry.machine.Machine;
import com.laundry.machine.MachineRepository;
import com.laundry.store.StoreCalendar;
import com.laundry.store.StoreService;
import com.laundry.store.StoreSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Lịch trống.
 * <ul>
 *   <li>Đặt trước (§5, §7.1): máy trống trong cả khoảng giữ máy, nằm trong giờ mở cửa (xong lấy đồ trước giờ đóng),
 *       còn trong hạn mức đặt trước (70% số máy mỗi loại, làm tròn xuống, tối thiểu 1).</li>
 *   <li>Kiosk (§8.2): máy online, không lỗi, lượt mới xong trước lượt đặt trước kế tiếp; rảnh ngay hoặc rảnh lúc
 *       HH:MM (chờ tối đa 30 phút).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    static final int SLOT_MINUTES = 15;

    private final MachineRepository machines;
    private final StoreService store;
    private final BookingSupport support;
    private final DeviceCommandService devices;
    private final Clock clock;

    public record Option(UUID machineId, String code, Machine.Type type, BigDecimal capacityKg, int cycleMinutes,
                         Long priceVnd, boolean available, String reason, Instant availableAt, boolean availableNow) {
    }

    public record AdvanceResult(Instant start, List<Option> washers, List<Option> dryers) {
    }

    public record Slot(Instant start, int washers, int dryers, boolean bookable) {
    }

    /** Một giờ bắt đầu đặt được cùng máy đề xuất (combo: máy sấy tính theo đúng máy giặt đó). */
    public record Suggestion(Instant start, Option washer, Option dryer, long totalVnd) {
    }

    public record SuggestResult(Instant from, Instant to, List<Suggestion> suggestions, String message) {
    }

    /** Một máy và giờ sớm nhất nó còn trống trong khoảng khách chọn; {@code start} null = không còn (lý do ở {@code machine.reason}). */
    public record MachineSlot(Option machine, Instant start) {
    }

    /** Khoảng giờ khách chọn tối đa 24 giờ (quét từng mốc 15 phút như {@link #slots}). */
    static final int MAX_SUGGEST_HOURS = 24;

    /** Máy tốt nhất ở cùng giờ: rẻ nhất, rồi chạy nhanh nhất, rồi theo số hiệu. */
    private static final Comparator<Option> BEST = Comparator
            .comparing((Option o) -> o.priceVnd() == null ? Long.MAX_VALUE : o.priceVnd())
            .thenComparingInt(Option::cycleMinutes)
            .thenComparing(Option::code);

    /** Máy cần cho dịch vụ. */
    public static boolean needs(Order.Service service, Machine.Type type) {
        return switch (service) {
            case WASH -> type == Machine.Type.WASHER;
            case DRY -> type == Machine.Type.DRYER;
            case WASH_DRY -> true;
            case LAUNDRY -> false;
        };
    }

    @Transactional(readOnly = true)
    public AdvanceResult advance(Order.Service service, Instant start, UUID washerHint, UUID ignoreOrderId) {
        StoreSettings s = store.settings();
        OrderPolicy p = OrderPolicy.from(s, s.getDepositPercent());
        List<Machine> sellable = machines.listInService().stream().filter(Machine::sellable).toList();
        ScheduleSnapshot snap = support.snapshot(sellable.stream().map(Machine::getId).toList(),
                start.minus(Duration.ofHours(1)), start.plus(Duration.ofHours(8)));
        StoreCalendar cal = store.calendar();
        String timeProblem = timeProblem(s, start);

        List<Machine> washers = sellable.stream().filter(m -> m.getMachineType() == Machine.Type.WASHER).toList();
        List<Machine> dryers = sellable.stream().filter(m -> m.getMachineType() == Machine.Type.DRYER).toList();
        int washCycle = washers.stream().filter(m -> m.getId().equals(washerHint)).findFirst()
                .or(() -> washers.stream().max(Comparator.comparingInt(Machine::getCycleMinutes)))
                .map(Machine::getCycleMinutes).orElse(0);

        List<Option> w = new ArrayList<>();
        List<Option> d = new ArrayList<>();
        if (needs(service, Machine.Type.WASHER)) {
            for (Machine m : washers) {
                Timeline.Window win = Timeline.firstLeg(start, m.getCycleMinutes(), p);
                w.add(option(m, win, timeProblem, cal, snap, washers, s.getAdvanceQuotaPercent(), ignoreOrderId));
            }
        }
        if (needs(service, Machine.Type.DRYER)) {
            for (Machine m : dryers) {
                Timeline.Window win = service == Order.Service.WASH_DRY
                        ? Timeline.dryerAfterWash(start, washCycle, m.getCycleMinutes(), p)
                        : Timeline.firstLeg(start, m.getCycleMinutes(), p);
                d.add(option(m, win, timeProblem, cal, snap, dryers, s.getAdvanceQuotaPercent(), ignoreOrderId));
            }
        }
        return new AdvanceResult(start, w, d);
    }

    /** Các khung 15 phút của một ngày (giờ cửa hàng) còn đặt được. */
    @Transactional(readOnly = true)
    public List<Slot> slots(Order.Service service, LocalDate day) {
        StoreCalendar cal = store.calendar();
        Instant now = clock.instant();
        List<Slot> out = new ArrayList<>();
        for (Instant[] window : cal.openWindows(day)) {
            Instant t = roundUp(window[0].isBefore(now) ? now : window[0]);
            while (t.isBefore(window[1])) {
                AdvanceResult r = advance(service, t, null, null);
                int wc = (int) r.washers().stream().filter(Option::available).count();
                int dc = (int) r.dryers().stream().filter(Option::available).count();
                boolean ok = switch (service) {
                    case WASH -> wc > 0;
                    case DRY -> dc > 0;
                    case WASH_DRY -> wc > 0 && dc > 0;
                    case LAUNDRY -> false;
                };
                out.add(new Slot(t, wc, dc, ok));
                t = t.plus(Duration.ofMinutes(SLOT_MINUTES));
            }
        }
        return out;
    }

    /**
     * Khách chọn khoảng giờ có thể đến [{@code from}, {@code to}]; trả các giờ bắt đầu sớm nhất (mốc 15 phút) còn đủ máy,
     * mỗi giờ kèm máy tốt nhất, không trùng lượt khác (cùng kiểm tra với {@link #advance}). Tối đa {@code limit} giờ.
     */
    @Transactional(readOnly = true)
    public SuggestResult suggest(Order.Service service, Instant from, Instant to, int limit) {
        return suggest(service, from, to, limit, null, null, null);
    }

    /**
     * Như trên nhưng chỉ xét đúng máy {@code washerId}/{@code dryerId} khi có (đổi giờ giữ nguyên máy), và bỏ qua lượt
     * của đơn {@code ignoreOrderId} (lượt cũ sẽ được nhả khi đổi).
     */
    @Transactional(readOnly = true)
    public SuggestResult suggest(Order.Service service, Instant from, Instant to, int limit, UUID washerId, UUID dryerId,
                                 UUID ignoreOrderId) {
        if (service == Order.Service.LAUNDRY) throw new BusinessException(ErrorCode.SERVICE_DISABLED);
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Giờ kết thúc phải sau giờ bắt đầu");
        }
        if (Duration.between(from, to).toHours() > MAX_SUGGEST_HOURS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Chọn khoảng giờ tối đa " + MAX_SUGGEST_HOURS + " giờ");
        }
        Instant now = clock.instant();
        List<Suggestion> out = new ArrayList<>();
        int max = Math.max(1, Math.min(limit, 10));
        for (Instant t = roundUp(from.isBefore(now) ? now : from); !t.isAfter(to) && out.size() < max;
             t = t.plus(Duration.ofMinutes(SLOT_MINUTES))) {
            Suggestion s = bestAt(service, t, washerId, dryerId, ignoreOrderId);
            if (s != null) out.add(s);
        }
        String message = out.isEmpty() ? "Không còn máy trống trong khoảng giờ này; thử khoảng khác hoặc ngày khác" : null;
        return new SuggestResult(from, to, out, message);
    }

    /**
     * Khách chọn khoảng giờ có thể đến rồi tự chọn máy: mỗi máy cần cho dịch vụ kèm giờ sớm nhất (mốc 15 phút) nó còn trống
     * trong khoảng, không trùng lượt khác. Combo giặt + sấy: máy giặt chỉ tính là trống khi lúc đó còn máy sấy cho nó
     * (máy sấy chọn sau, theo giờ của máy giặt). Máy trống xếp trước theo giờ, rồi tải trọng.
     */
    @Transactional(readOnly = true)
    public List<MachineSlot> machineWindow(Order.Service service, Instant from, Instant to) {
        if (service == Order.Service.LAUNDRY) throw new BusinessException(ErrorCode.SERVICE_DISABLED);
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Giờ kết thúc phải sau giờ bắt đầu");
        }
        if (Duration.between(from, to).toHours() > MAX_SUGGEST_HOURS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Chọn khoảng giờ tối đa " + MAX_SUGGEST_HOURS + " giờ");
        }
        Machine.Type type = service == Order.Service.DRY ? Machine.Type.DRYER : Machine.Type.WASHER;
        java.util.Map<UUID, MachineSlot> found = new java.util.LinkedHashMap<>();
        java.util.Map<UUID, Option> lastSeen = new java.util.LinkedHashMap<>();
        Instant now = clock.instant();
        for (Instant t = roundUp(from.isBefore(now) ? now : from); !t.isAfter(to); t = t.plus(Duration.ofMinutes(SLOT_MINUTES))) {
            AdvanceResult r = advance(service, t, null, null);
            List<Option> list = type == Machine.Type.DRYER ? r.dryers() : r.washers();
            for (Option o : list) {
                if (found.containsKey(o.machineId())) continue;
                lastSeen.put(o.machineId(), o);
                if (!o.available()) continue;
                if (service == Order.Service.WASH_DRY && advance(service, t, o.machineId(), null).dryers().stream()
                        .noneMatch(Option::available)) {
                    continue;
                }
                found.put(o.machineId(), new MachineSlot(o, t));
            }
            if (!lastSeen.isEmpty() && found.size() == lastSeen.size()) break;
        }
        List<MachineSlot> out = new ArrayList<>(found.values());
        out.sort(Comparator.comparing(MachineSlot::start).thenComparing(m -> m.machine().capacityKg())
                .thenComparing(m -> m.machine().code()));
        for (Option o : lastSeen.values()) {
            if (found.containsKey(o.machineId())) continue;
            String reason = o.reason() != null && !o.available() && o.reason().startsWith("Chỉ đặt") ? o.reason()
                    : service == Order.Service.WASH_DRY ? "Không còn máy giặt + máy sấy trống trong khoảng giờ này"
                    : "Không còn trống trong khoảng giờ này";
            out.add(new MachineSlot(new Option(o.machineId(), o.code(), o.type(), o.capacityKg(), o.cycleMinutes(),
                    o.priceVnd(), false, reason, null, false), null));
        }
        return out;
    }

    private Suggestion bestAt(Order.Service service, Instant t, UUID washerId, UUID dryerId, UUID ignoreOrderId) {
        AdvanceResult r = advance(service, t, washerId, ignoreOrderId);
        java.util.function.Predicate<Option> washerOk = o -> o.available() && (washerId == null || o.machineId().equals(washerId));
        java.util.function.Predicate<Option> dryerOk = o -> o.available() && (dryerId == null || o.machineId().equals(dryerId));
        return switch (service) {
            case WASH -> r.washers().stream().filter(washerOk).min(BEST)
                    .map(w -> new Suggestion(t, w, null, price(w))).orElse(null);
            case DRY -> r.dryers().stream().filter(dryerOk).min(BEST)
                    .map(d -> new Suggestion(t, null, d, price(d))).orElse(null);
            case WASH_DRY -> {
                for (Option w : r.washers().stream().filter(washerOk).sorted(BEST).toList()) {
                    Option d = advance(service, t, w.machineId(), ignoreOrderId).dryers().stream()
                            .filter(dryerOk).min(BEST).orElse(null);
                    if (d != null) yield new Suggestion(t, w, d, price(w) + price(d));
                }
                yield null;
            }
            case LAUNDRY -> null;
        };
    }

    private static long price(Option o) {
        return o.priceVnd() == null ? 0 : o.priceVnd();
    }

    /** Kiosk: mỗi máy rảnh ngay hay rảnh lúc nào (trong {@code kioskMaxWaitMinutes}). */
    @Transactional(readOnly = true)
    public List<Option> walkIn(Order.Service service) {
        StoreSettings s = store.settings();
        OrderPolicy p = OrderPolicy.from(s, 100);
        Instant now = clock.instant();
        Instant latest = now.plus(Duration.ofMinutes(s.getKioskMaxWaitMinutes()));
        List<Machine> sellable = machines.listInService().stream().filter(Machine::sellable)
                .filter(m -> needs(service, m.getMachineType())).toList();
        ScheduleSnapshot snap = support.snapshot(sellable.stream().map(Machine::getId).toList(), now.minusSeconds(3600),
                latest.plus(Duration.ofHours(8)));
        StoreCalendar cal = store.calendar();
        List<Option> out = new ArrayList<>();
        for (Machine m : sellable) {
            if (!devices.isOnline(m.getId())) {
                out.add(new Option(m.getId(), m.getCode(), m.getMachineType(), m.getCapacityKg(), m.getCycleMinutes(),
                        m.getPriceVnd(), false, "Máy đang mất kết nối", null, false));
                continue;
            }
            Duration length = walkInLength(m.getCycleMinutes(), p);
            Instant at = snap.earliestFree(m.getId(), now, latest, length);
            String reason = null;
            if (at == null) reason = "Máy bận quá " + s.getKioskMaxWaitMinutes() + " phút";
            else if (!cal.fitsOpen(at, at.plus(length).minus(Timeline.minutes(p.cleanupMinutes())))) {
                reason = "Không kịp xong trước giờ đóng cửa";
            }
            boolean ok = reason == null;
            out.add(new Option(m.getId(), m.getCode(), m.getMachineType(), m.getCapacityKg(), m.getCycleMinutes(),
                    m.getPriceVnd(), ok, reason, ok ? at : null, ok && !at.isAfter(now.plusSeconds(30))));
        }
        return out;
    }

    /** Đơn đặt ngay: thanh toán ≤ 5' + ân hạn + bấm START + chạy + lấy đồ + dọn. */
    public static Duration walkInLength(int cycleMinutes, OrderPolicy p) {
        return Timeline.minutes(com.laundry.store.StoreSettings.PAYMENT_HOLD_MINUTES + p.graceMinutes()
                + p.startWindowMinutes() + cycleMinutes + p.unloadMinutes() + p.cleanupMinutes());
    }

    /** Lý do giờ T không đặt trước được (quá khứ, quá xa); null = được. */
    public String timeProblem(StoreSettings s, Instant start) {
        Instant now = clock.instant();
        if (start.isBefore(now.minusSeconds(60))) return "Giờ bắt đầu đã qua";
        if (start.isAfter(now.plus(Duration.ofHours(s.getMaxAdvanceBookingHours())))) {
            return "Chỉ đặt trước tối đa " + s.getMaxAdvanceBookingHours() + " giờ";
        }
        return null;
    }

    public void requireBookable(Option o) {
        if (!o.available()) throw new BusinessException(ErrorCode.MACHINE_UNAVAILABLE, "Máy " + o.code() + ": " + o.reason());
    }

    private Option option(Machine m, Timeline.Window win, String timeProblem, StoreCalendar cal, ScheduleSnapshot snap,
                          List<Machine> sameType, int quotaPercent, UUID ignoreOrderId) {
        String reason = timeProblem;
        if (reason == null && !cal.fitsOpen(win.start(), win.unloadEnd())) reason = "Ngoài giờ mở cửa hoặc không kịp xong trước giờ đóng";
        if (reason == null && !snap.free(m.getId(), win.start(), win.end(), ignoreOrderId)) reason = "Máy đã có lượt";
        if (reason == null) {
            int cap = Math.max(1, sameType.size() * quotaPercent / 100);
            int busy = snap.advanceBusyMachines(sameType.stream().map(Machine::getId).toList(), win.start(), win.end(),
                    ignoreOrderId);
            if (busy >= cap) reason = "Đã hết chỗ đặt trước khung giờ này (dành cho khách tại cửa hàng)";
        }
        return new Option(m.getId(), m.getCode(), m.getMachineType(), m.getCapacityKg(), m.getCycleMinutes(),
                m.getPriceVnd(), reason == null, reason, reason == null ? win.start() : null, false);
    }

    private static Instant roundUp(Instant t) {
        Instant floor = t.truncatedTo(ChronoUnit.HOURS);
        while (floor.isBefore(t)) floor = floor.plus(Duration.ofMinutes(SLOT_MINUTES));
        return floor;
    }
}
