package com.laundry.machine.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persisted record of a command issued from the backend to a device.
 *
 * <p>Commands flow: Frontend → REST API → Backend validates → DeviceCommand created
 * → published to MQTT → (optionally) ACK received from device.
 *
 * <p>Idempotency: Each command has a unique {@code commandId} (UUID). The device
 * should ignore a command with a commandId it has already processed. The backend
 * tracks status so duplicate MQTT deliveries are handled gracefully.
 *
 * <p>Backward compatibility: The current ESP32 prototype only understands
 * {@code {"command": "START"}}. The full payload with commandId/bookingId/issuedAt
 * is included for future-compatible firmware; legacy firmware ignores unknown fields.
 */
@Entity
@Table(
    name = "device_commands",
    indexes = {
        @Index(name = "idx_device_cmd_machine_time", columnList = "machine_id, created_at"),
        @Index(name = "idx_device_cmd_status", columnList = "status"),
        @Index(name = "idx_device_cmd_booking", columnList = "booking_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * Stable command identifier sent to the device in the payload.
     * The device echoes this back in the ACK so we can match responses.
     */
    @Column(name = "command_id", nullable = false, unique = true, length = 60)
    private String commandId;

    @Column(name = "machine_id", nullable = false)
    private UUID machineId;

    @Column(name = "machine_code", nullable = false, length = 50)
    private String machineCode;

    /**
     * The command verb: START, STOP, RESET, PAUSE, RESUME, CANCEL, TARE, STATUS, etc.
     * Stored as a String (not enum) to remain open to future firmware-defined commands.
     */
    @Column(nullable = false, length = 30)
    private String command;

    /** Optional booking/transaction reference for authorization traceability. */
    @Column(name = "booking_id")
    private UUID bookingId;

    /**
     * ID of the user who triggered this command (customer or staff).
     * Used for audit trail.
     */
    @Column(name = "issued_by_user_id")
    private UUID issuedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DeviceCommandStatus status = DeviceCommandStatus.PENDING;

    /** Timestamp when this command was published to MQTT. */
    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    /** Timestamp when the device ACK was received (null if no ACK yet). */
    @Column(name = "acknowledged_at")
    private OffsetDateTime acknowledgedAt;

    /** Result string from device ACK: "ACCEPTED", "REJECTED", etc. */
    @Column(name = "ack_result", length = 30)
    private String ackResult;

    /** Reason from device ACK or backend failure reason. */
    @Column(name = "ack_reason", length = 500)
    private String ackReason;

    /** Deadline after which TIMEOUT is set if no ACK received. */
    @Column(name = "timeout_at")
    private OffsetDateTime timeoutAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
