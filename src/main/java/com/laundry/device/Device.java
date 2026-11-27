package com.laundry.device;

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

import java.time.Instant;
import java.util.UUID;

/** Bo mạch ESP32 (id "ESP-<MAC>"). PENDING -> VERIFIED (admin duyệt khóa) -> BOUND (gắn máy/tủ); REVOKED. */
@Entity
@Table(name = "devices")
@Getter
@Setter
public class Device extends AssignedIdEntity<String> {

    public enum Type { WASHER, DRYER, LOCKER }

    public enum LinkState { PENDING, VERIFIED, BOUND, REVOKED }

    @Id
    private String id;
    @Enumerated(EnumType.STRING)
    private Type deviceType;
    private String firmwareVersion;
    /** base64 điểm P-256 không nén 65 byte. */
    private String publicKey;
    private String keyFingerprint;
    @Enumerated(EnumType.STRING)
    private LinkState linkState = LinkState.PENDING;
    private UUID machineId;
    private UUID lockerId;
    private UUID approvedBy;
    private Instant approvedAt;
    private UUID revokedBy;
    private Instant revokedAt;
    private String revokeReason;
    private boolean online;
    private Instant lastSeenAt;
    private String lastIp;
    private Integer lastRssi;
    @JdbcTypeCode(SqlTypes.JSON)
    private String lastStatus;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
    @Version
    private long version;
}
