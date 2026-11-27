package com.laundry.device;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Lệnh xuống bo (down/command). id = commandId bo gửi lại trong command/ack. */
@Entity
@Table(name = "device_commands")
@Getter
@Setter
public class DeviceCommand extends AssignedIdEntity<UUID> {

    public enum Source { SYSTEM, KIOSK, STAFF }

    public enum Status { PENDING, SENT, ACKED, FAILED, TIMEOUT }

    @Id
    private UUID id = UUID.randomUUID();
    private String deviceId;
    private String commandType;
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;
    private UUID orderLegId;
    private UUID lockerAssignmentId;
    @Enumerated(EnumType.STRING)
    private Source source;
    private UUID requestedBy;
    private UUID kioskId;
    private String reason;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;
    private int attempts;
    private Instant expiresAt;
    private Instant sentAt;
    private Instant ackedAt;
    @JdbcTypeCode(SqlTypes.JSON)
    private String result;
    @CreationTimestamp
    private Instant createdAt;
}
