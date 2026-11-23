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
import org.hibernate.annotations.UpdateTimestamp;

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

    @Id
    private UUID id = UUID.randomUUID();
    private String code;
    @Enumerated(EnumType.STRING)
    private Type machineType;
    private BigDecimal capacityKg;
    private int cycleMinutes;
    /** null = chưa có giá, không bán (§4.1). */
    private Long priceVnd;
    private boolean autoDosing;
    @Enumerated(EnumType.STRING)
    private AdminStatus adminStatus = AdminStatus.ACTIVE;
    private String statusReason;
    private Instant statusChangedAt;
    private UUID statusChangedBy;
    private int displayOrder;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
    @Version
    private long version;

    public boolean sellable() {
        return adminStatus == AdminStatus.ACTIVE && priceVnd != null;
    }
}
