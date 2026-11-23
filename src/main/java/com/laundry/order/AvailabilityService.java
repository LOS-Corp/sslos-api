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
