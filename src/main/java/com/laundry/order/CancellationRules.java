package com.laundry.order;

import com.laundry.common.Money;
import com.laundry.refund.Refund;

import java.time.Duration;
import java.time.Instant;

/**
 * Hủy / không đến (§11), hàm thuần.
 * <pre>
 * Đơn đặt trước:
 *   hủy trước T − 60', hoặc trong 10' sau khi đặt (và vẫn trước T)  -> hoàn 100% số đã trả
 *   hủy muộn (trước khi hết ân hạn) / không đến                     -> giữ tiền giữ chỗ (20% giá đơn), hoàn phần còn lại
 * Đơn đặt ngay (kiosk):
 *   đã trả, hủy trước khi máy chạy                                   -> hoàn 100%
 *   bỏ không dùng (hết ân hạn mà máy chưa chạy)                      -> giữ 20%, hoàn 80%
 * </pre>
 */
public final class CancellationRules {

    private CancellationRules() {
    }

    public record Outcome(Order.CancellationKind kind, long refund, long forfeit, Refund.Reason reason) {
    }

    /** Khách (hoặc kiosk) chủ động hủy một đơn chưa có chặng nào chạy. */
    public static Outcome cancel(Order o, OrderPolicy p, Instant now) {
        long paid = o.getPaidAmount() - o.getRefundedAmount() - o.getForfeitedAmount();
        if (o.getStatus() == Order.Status.HOLD) {
            return new Outcome(Order.CancellationKind.UNPAID, paid, 0, Refund.Reason.PAYMENT_ABANDONED);
        }
        if (o.getChannel() == Order.Channel.KIOSK) {
            return new Outcome(Order.CancellationKind.FREE, paid, 0, Refund.Reason.KIOSK_CANCEL);
        }
        if (isFree(o, p, now)) return new Outcome(Order.CancellationKind.FREE, paid, 0, Refund.Reason.FREE_CANCEL);
        long forfeit = forfeit(o, p, paid);
        return new Outcome(Order.CancellationKind.LATE, paid - forfeit, forfeit, Refund.Reason.LATE_CANCEL_EXCESS);
    }

    /** Hết ân hạn mà máy chưa chạy. */
    public static Outcome noShow(Order o, OrderPolicy p) {
        long paid = o.getPaidAmount() - o.getRefundedAmount() - o.getForfeitedAmount();
        long forfeit = forfeit(o, p, paid);
        return new Outcome(Order.CancellationKind.NO_SHOW, paid - forfeit, forfeit,
                o.getChannel() == Order.Channel.KIOSK ? Refund.Reason.ABANDONED_80 : Refund.Reason.NO_SHOW_EXCESS);
    }

    public static boolean isFree(Order o, OrderPolicy p, Instant now) {
        Instant t = o.getScheduledStartAt();
        boolean early = now.isBefore(t.minus(Duration.ofMinutes(p.freeCancelBeforeMinutes())));
        boolean justBooked = !now.isAfter(o.getCreatedAt().plus(Duration.ofMinutes(p.freeCancelAfterBookingMinutes())))
                && now.isBefore(t);
        return early || justBooked;
    }

    /** Đổi giờ miễn phí 1 lần, trước T − 60'. */
    public static boolean canReschedule(Order o, OrderPolicy p, Instant now) {
        return o.getChannel() == Order.Channel.APP && o.getStatus() == Order.Status.CONFIRMED && o.getRescheduleCount() == 0
                && now.isBefore(o.getScheduledStartAt().minus(Duration.ofMinutes(p.freeCancelBeforeMinutes())));
    }

    /** Tiền giữ chỗ = % giá đơn (làm tròn lên 1.000 đ), không quá số đã trả. */
    static long forfeit(Order o, OrderPolicy p, long paid) {
        long f = Money.percentRoundedUp(o.getTotalAmount(), p.forfeitPercent(), p.depositRoundingVnd());
        return Math.max(0, Math.min(f, paid));
    }
}
