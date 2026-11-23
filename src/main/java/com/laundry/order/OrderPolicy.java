package com.laundry.order;

import com.laundry.store.StoreSettings;

/**
 * Bản chụp tham số lúc đặt (orders.policy_snapshot): đổi cấu hình cửa hàng không ảnh hưởng đơn đã xác nhận (§16).
 *
 * @param depositPercent tối thiểu phải trả lúc đặt (20%, hoặc 100% khi khách đang bị phạt vi phạm lặp lại)
 * @param forfeitPercent bị giữ khi hủy muộn / không đến: luôn là % tiền giữ chỗ của cửa hàng (§11.1 "vẫn chỉ mất 20%")
 */
public record OrderPolicy(
        int earlyCheckinMinutes,
        int graceMinutes,
        int startWindowMinutes,
        int unloadMinutes,
        int cleanupMinutes,
        int transferMinutes,
        int transferExtensionMinutes,
        int depositPercent,
        int forfeitPercent,
        int depositRoundingVnd,
        int freeCancelBeforeMinutes,
        int freeCancelAfterBookingMinutes,
        int dryerExtensionMaxMinutes
) {
    public static OrderPolicy from(StoreSettings s, int depositPercent) {
        return new OrderPolicy(s.getEarlyCheckinMinutes(), s.getGraceMinutes(), s.getStartWindowMinutes(),
                s.getUnloadMinutes(), s.getCleanupMinutes(), s.getTransferMinutes(), s.getTransferExtensionMinutes(),
                depositPercent, s.getDepositPercent(), s.getDepositRoundingVnd(), s.getFreeCancelBeforeMinutes(),
                s.getFreeCancelAfterBookingMinutes(), s.getDryerExtensionMaxMinutes());
    }
}
