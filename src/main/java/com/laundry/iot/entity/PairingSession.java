package com.laundry.iot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Time-limited window in which a scanned ESP32 may activate itself for a
 * specific machine. Opened by an admin, closed by a successful activation,
 * a new pairing, or expiry.
 */
@Entity
@Table(name = "device_pairing_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PairingSession {

    public enum Status { OPEN, COMPLETED, EXPIRED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "machine_id", nullable = false)
    private UUID machineId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public boolean isUsable(OffsetDateTime now) {
        return status == Status.OPEN && now.isBefore(expiresAt);
    }
}
