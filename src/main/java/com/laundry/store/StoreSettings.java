package com.laundry.store;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/** Một dòng duy nhất (id = 1): bảng tham số §18. */
@Entity
@Table(name = "store_settings")
@Getter
@Setter
public class StoreSettings {

    /** Giữ chỗ để thanh toán (§6.1): chủ cửa hàng không chỉnh được. */
    public static final int PAYMENT_HOLD_MINUTES = 5;

    @Id
    private Integer id = 1;
    private String name;
    private String address;
    private String phone;
    private String timezone;

    private boolean washEnabled;
    private boolean dryEnabled;
    private boolean washDryEnabled;
    private boolean laundryServiceEnabled;

    private int maxAdvanceBookingHours;
    private int earlyCheckinMinutes;
    private int graceMinutes;
    private int startWindowMinutes;
    private int unloadMinutes;
    private int cleanupMinutes;
    private int transferMinutes;
    private int transferExtensionMinutes;
    private int advanceQuotaPercent;
    private int kioskMaxWaitMinutes;
    private int dryerExtensionMaxMinutes;

    private int depositPercent;
    private int depositRoundingVnd;
    private int freeCancelBeforeMinutes;
    private int freeCancelAfterBookingMinutes;
    private int violationThreshold;
    private int violationWindowDays;
    private int violationPenaltyDays;

    private int lostItemFreeHours;
    private long lostItemFeePerDay;
    private int lostItemDisposeDays;
    private int lostItemNoticeCount;

    private int laundryDropOffWindowMinutes;
    private BigDecimal laundryMinWeightKg;
    private BigDecimal laundryWeightStepKg;
    private int laundryStandardHours;
    private int laundryExpressHours;
    private int laundryExpressSurchargePercent;
    private int laundryFreeStorageHours;
    private long laundryStorageFeePerDay;
    private int laundryCompensationMultiplier;
    private int laundryComplaintWindowHours;

    private long staffRefundLimitVnd;

    @UpdateTimestamp
    private Instant updatedAt;
    private UUID updatedBy;
    @Version
    private long version;

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }
}
