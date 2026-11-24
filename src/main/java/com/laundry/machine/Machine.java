package com.laundry.machine;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "machines")
@Getter
@Setter
public class Machine extends AssignedIdEntity<UUID> {

    public enum Type { WASHER, DRYER }

    /** Trạng thái do người đặt; trạng thái hiển thị suy ra thêm từ thiết bị và lượt đang mở. */
    public enum AdminStatus { ACTIVE, ERROR, MAINTENANCE, RETIRED }

    /** Bước lắp đặt (V4): gắn bo -> self-test -> đưa vào hoạt động; bảo trì quay lại bằng self-test. */
    public enum DeploymentStatus { UNASSIGNED, PROVISIONED, TESTING, TEST_FAILED, TEST_PASSED, ACTIVE, MAINTENANCE }

    @Id
    private UUID id = UUID.randomUUID();
    private String code;
    @Enumerated(EnumType.STRING)
    private Type machineType;
    private BigDecimal capacityKg;
    private int cycleMinutes;
    /** null = chưa có giá, không bán (§4.1). */
    private Long priceVnd;
    /** Profile giá đang gắn; khi có thì priceVnd/cycleMinutes luôn bằng profile (V3). null = giá riêng của máy. */
    private UUID priceProfileId;
    private boolean autoDosing;
    @Enumerated(EnumType.STRING)
    private AdminStatus adminStatus = AdminStatus.ACTIVE;
    private String statusReason;
    private Instant statusChangedAt;
    private UUID statusChangedBy;
    private int displayOrder;
    @Enumerated(EnumType.STRING)
    private DeploymentStatus deploymentStatus = DeploymentStatus.UNASSIGNED;
    private UUID selfTestCommandId;
    private Instant selfTestRequestedAt;
    private Instant lastSelfTestAt;
    private Boolean lastSelfTestPassed;
    @JdbcTypeCode(SqlTypes.JSON)
    private String lastSelfTestDetail;
    private Instant maintenanceSince;
    private Instant commissionedAt;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
    @Version
    private long version;

    /** Bán được: đang dùng, đã đưa vào hoạt động (V4) và có giá. */
    public boolean sellable() {
        return adminStatus == AdminStatus.ACTIVE && deploymentStatus == DeploymentStatus.ACTIVE && priceVnd != null;
    }

    /** Vừa gắn bo mới: bắt đầu lại từ self-test, kết quả cũ (của bo khác) bỏ đi. */
    public void provisioned() {
        deploymentStatus = DeploymentStatus.PROVISIONED;
        clearSelfTest();
        maintenanceSince = null;
        commissionedAt = null;
    }

    /** Gỡ / thu hồi bo: máy về chưa gán thiết bị. */
    public void unassigned() {
        deploymentStatus = DeploymentStatus.UNASSIGNED;
        clearSelfTest();
        maintenanceSince = null;
        commissionedAt = null;
    }

    private void clearSelfTest() {
        selfTestCommandId = null;
        selfTestRequestedAt = null;
        lastSelfTestAt = null;
        lastSelfTestPassed = null;
        lastSelfTestDetail = null;
    }
}
