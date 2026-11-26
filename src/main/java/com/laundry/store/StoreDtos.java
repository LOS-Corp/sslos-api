package com.laundry.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class StoreDtos {

    private StoreDtos() {
    }

    /** Mọi trường tùy chọn: chỉ trường có giá trị mới được đổi. Lý do bắt buộc (§2). */
    public record UpdateSettings(
            @Size(max = 120) String name, @Size(max = 255) String address, @Size(max = 16) String phone,
            Boolean washEnabled, Boolean dryEnabled, Boolean washDryEnabled, Boolean laundryServiceEnabled,
            @Min(1) @Max(720) Integer maxAdvanceBookingHours,
            @Min(0) @Max(120) Integer earlyCheckinMinutes,
            @Min(0) @Max(60) Integer graceMinutes,
            @Min(1) @Max(30) Integer startWindowMinutes,
            @Min(0) @Max(60) Integer unloadMinutes,
            @Min(0) @Max(60) Integer cleanupMinutes,
            @Min(1) @Max(60) Integer transferMinutes,
            @Min(0) @Max(60) Integer transferExtensionMinutes,
            @Min(0) @Max(100) Integer advanceQuotaPercent,
            @Min(0) @Max(240) Integer kioskMaxWaitMinutes,
            @Min(0) @Max(240) Integer dryerExtensionMaxMinutes,
            @Min(0) @Max(100) Integer depositPercent,
            @Min(1) Integer depositRoundingVnd,
            @Min(0) Integer freeCancelBeforeMinutes,
            @Min(0) Integer freeCancelAfterBookingMinutes,
            @Min(1) Integer violationThreshold,
            @Min(1) Integer violationWindowDays,
            @Min(0) Integer violationPenaltyDays,
            @Min(0) Integer lostItemFreeHours,
            @Min(0) Long lostItemFeePerDay,
            @Min(1) Integer lostItemDisposeDays,
            @Min(0) Integer lostItemNoticeCount,
            @Min(1) Integer laundryDropOffWindowMinutes,
            @DecimalMin("0.1") BigDecimal laundryMinWeightKg,
            @DecimalMin("0.1") BigDecimal laundryWeightStepKg,
            @Min(1) Integer laundryStandardHours,
            @Min(1) Integer laundryExpressHours,
            @Min(0) Integer laundryExpressSurchargePercent,
            @Min(0) Integer laundryFreeStorageHours,
            @Min(0) Long laundryStorageFeePerDay,
            @Min(0) Integer laundryCompensationMultiplier,
            @Min(0) Integer laundryComplaintWindowHours,
            @Min(0) Long staffRefundLimitVnd,
            Boolean ironingEnabled, Boolean washDryIronEnabled,
            @Min(1000) Long ironingPricePerKg,
            @Min(1000) Long washDryIronPricePerKg,
            @DecimalMin("0.1") BigDecimal counterMinWeightKg,
            @Min(1) @Max(168) Integer counterTurnaroundHours,
            @Min(0) Long washCostPerRunVnd,
            @Min(0) Long dryCostPerRunVnd,
            @NotBlank String reason) {
    }

    /**
     * Chính sách hệ thống (Quản trị viên chỉnh, Chủ cửa hàng chỉ xem): quy định đặt lịch, hủy đơn, tiền giữ chỗ,
     * vi phạm và hoàn tiền. Tên trùng trường của {@link UpdateSettings} / {@link StoreSettings}.
     */
    public static final java.util.Set<String> POLICY_FIELDS = java.util.Set.of(
            "maxAdvanceBookingHours", "earlyCheckinMinutes", "graceMinutes", "startWindowMinutes",
            "advanceQuotaPercent", "kioskMaxWaitMinutes",
            "depositPercent", "depositRoundingVnd", "freeCancelBeforeMinutes", "freeCancelAfterBookingMinutes",
            "violationThreshold", "violationWindowDays", "violationPenaltyDays",
            "staffRefundLimitVnd", "laundryCompensationMultiplier", "laundryComplaintWindowHours");

    public record HourSlot(@Min(1) @Max(7) int dayOfWeek, @NotNull LocalTime opensAt, @NotNull LocalTime closesAt) {
    }

    public record ReplaceHours(@NotNull StoreHour.Kind kind, @NotNull List<@Valid HourSlot> slots,
                               @NotBlank String reason) {
    }

    public record AddClosure(@NotNull Instant startsAt, @NotNull Instant endsAt, @NotBlank @Size(max = 255) String reason) {
    }

    public record ClosureView(UUID id, Instant startsAt, Instant endsAt, String reason) {
        static ClosureView of(StoreClosure c) {
            return new ClosureView(c.getId(), c.getStartsAt(), c.getEndsAt(), c.getReason());
        }
    }

    public record HourView(UUID id, StoreHour.Kind kind, int dayOfWeek, LocalTime opensAt, LocalTime closesAt) {
        static HourView of(StoreHour h) {
            return new HourView(h.getId(), h.getKind(), h.getDayOfWeek(), h.getOpensAt(), h.getClosesAt());
        }
    }

    /** Một đơn bị ảnh hưởng khi tắt dịch vụ: mã đơn, tình trạng, giờ bắt đầu / hẹn trả. */
    public record ImpactItem(String code, String state, Instant at) {
    }

    public record ServiceImpact(String service, List<ImpactItem> inUse, List<ImpactItem> upcoming) {
    }

    public record ReadinessCheck(String key, boolean ok, String message) {
    }

    public record Readiness(boolean ready, List<ReadinessCheck> checks) {
    }

    /** Thông tin công khai cho app/web/kiosk. */
    public record PublicStore(String name, String address, String phone, String timezone,
                              boolean washEnabled, boolean dryEnabled, boolean washDryEnabled,
                              boolean laundryServiceEnabled, boolean ready, boolean openNow,
                              List<HourView> openingHours, List<ClosureView> upcomingClosures,
                              int maxAdvanceBookingHours, int depositPercent, int graceMinutes,
                              int earlyCheckinMinutes, int startWindowMinutes, int freeCancelBeforeMinutes,
                              int freeCancelAfterBookingMinutes, int kioskMaxWaitMinutes,
                              boolean ironingEnabled, boolean washDryIronEnabled,
                              long ironingPricePerKg, long washDryIronPricePerKg) {
    }
}
