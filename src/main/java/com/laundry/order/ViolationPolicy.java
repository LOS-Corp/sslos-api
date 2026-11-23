package com.laundry.order;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Vi phạm lặp lại (§11.1): đủ {@code threshold} lần không đến / hủy muộn trong {@code windowDays} ngày
 * thì {@code penaltyDays} ngày tiếp theo (tính từ lần vi phạm làm đủ số) khách phải trả 100% khi đặt trước.
 */
public final class ViolationPolicy {

    private ViolationPolicy() {
    }

    /** {@code times} tăng dần. */
    public static boolean penaltyActive(List<Instant> times, Instant now, int threshold, int windowDays, int penaltyDays) {
        if (threshold < 1 || times.size() < threshold) return false;
        Duration window = Duration.ofDays(windowDays);
        Duration penalty = Duration.ofDays(penaltyDays);
        for (int i = threshold - 1; i < times.size(); i++) {
            Instant trigger = times.get(i);
            Instant first = times.get(i - threshold + 1);
            if (Duration.between(first, trigger).compareTo(window) <= 0
                    && !now.isAfter(trigger.plus(penalty))) {
                return true;
            }
        }
        return false;
    }
}
