package com.laundry.machine.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persisted record of an important event from or about a device.
 *
 * <p>Not every MQTT message generates an event — only significant state changes
 * (started, completed, error, offline, etc.) are recorded here. High-frequency
 * telemetry is handled separately and only the current state is stored in Machine.
 */
@Entity
@Table(
    name = "device_events",
    indexes = {
        @Index(name = "idx_device_event_machine_time", columnList = "machine_id, created_at"),
        @Index(name = "idx_device_event_type", columnList = "event_type")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * The machine that generated this event.
     * Not a JPA FK relationship to keep the IoT layer independent of domain relationships.
     */
    @Column(name = "machine_id", nullable = false)
    private UUID machineId;

    /** The machine's string code at the time of the event (denormalized for fast lookup). */
    @Column(name = "machine_code", nullable = false, length = 50)
    private String machineCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private DeviceEventType eventType;

    /**
     * Optional free-text detail (error description, payload snippet, etc.).
     * Stored as TEXT to allow arbitrary length.
     */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /**
     * Raw JSON payload that caused this event (truncated to 2000 chars for storage efficiency).
     * Useful for debugging without re-subscribing to MQTT.
     */
    @Column(name = "raw_payload", length = 2000)
    private String rawPayload;

    /** Optional reference to the booking/transaction context. */
    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
