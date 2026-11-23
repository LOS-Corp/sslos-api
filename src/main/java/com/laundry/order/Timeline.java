package com.laundry.order;

import java.time.Duration;
import java.time.Instant;

/**
 * Khoảng giữ máy của một lượt (§6.2), hàm thuần:
 * <pre>
 * chặng đầu: T ─[ân hạn]─[bấm START]─[chạy]─[lấy đồ]─[dọn]─> lượt sau được bắt đầu
 * chặng sấy của Giặt + sấy: bắt đầu lúc máy giặt xong muộn nhất ─[chuyển đồ]─[chạy]─[lấy đồ]─[dọn]─>
 * </pre>
 * Khoảng giữ máy đã tính sẵn trường hợp khách đến muộn nhất; máy chạy thật thì thu lại (kiosk bán phần trống).
 */
public final class Timeline {

    private Timeline() {
    }

    public record Window(Instant start, Instant end, Instant unloadEnd) {
    }

    /** Chặng đầu bắt đầu lúc T. unloadEnd = lúc hết phần lấy đồ (phải trước giờ đóng cửa, §6.3). */
    public static Window firstLeg(Instant t, int cycleMinutes, OrderPolicy p) {
        Instant runEnd = t.plus(minutes(p.graceMinutes() + p.startWindowMinutes() + cycleMinutes));
        Instant unloadEnd = runEnd.plus(minutes(p.unloadMinutes()));
        return new Window(t, unloadEnd.plus(minutes(p.cleanupMinutes())), unloadEnd);
    }

    /** Chặng sấy của combo: từ lúc máy giặt xong muộn nhất. */
    public static Window dryerAfterWash(Instant t, int washMinutes, int dryMinutes, OrderPolicy p) {
        Instant washEnd = t.plus(minutes(p.graceMinutes() + p.startWindowMinutes() + washMinutes));
        Instant runEnd = washEnd.plus(minutes(p.transferMinutes() + dryMinutes));
        Instant unloadEnd = runEnd.plus(minutes(p.unloadMinutes()));
        return new Window(washEnd, unloadEnd.plus(minutes(p.cleanupMinutes())), unloadEnd);
    }

    /** Máy đã chạy lúc {@code startedAt}: khoảng giữ thu về đúng thời gian chạy thật + lấy đồ + dọn. */
    public static Instant endAfterStart(Instant startedAt, int cycleMinutes, int extraMinutes, OrderPolicy p) {
        return startedAt.plus(minutes(cycleMinutes + extraMinutes + p.unloadMinutes() + p.cleanupMinutes()));
    }

    /** Máy đã xong lúc {@code finishedAt}. */
    public static Instant endAfterFinish(Instant finishedAt, OrderPolicy p) {
        return finishedAt.plus(minutes(p.unloadMinutes() + p.cleanupMinutes()));
    }

    public static Duration minutes(long m) {
        return Duration.ofMinutes(m);
    }
}
