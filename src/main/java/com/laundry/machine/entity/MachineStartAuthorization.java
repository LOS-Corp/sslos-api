package com.laundry.machine.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Short-lived, single-use authorization for a customer to press the
 * physical START button on a specific machine.
 *
 * <p>Lifecycle:
 * <pre>
 *   PENDING ──&gt; GRANTED ──&gt; START_PENDING ──&gt; CONSUMED
 *       │           │              │
 *       │           ▼              ▼
 *       └─────► EXPIRED / REVOKED ◄───── ESP32 reports start failure
 * </pre>
 *
 * <p>An authorization is the contract between:
 * <ol>
 *   <li>The customer (identified by {@code userId}) who scanned a QR.</li>
 *   <li>The reservation (identified by {@code reservationId}) they paid for.</li>
 *   <li>The machine (identified by {@code machineId}) they want to use.</li>
 *   <li>The device boot session (identified by {@code deviceBootId}) so
 *       an ESP32 reboot invalidates the unused authorization.</li>
 * </ol>
 *
 * <p>Concurrency:
 * <ul>
 *   <li>Only one ACTIVE ({@code PENDING} / {@code GRANTED} / {@code START_PENDING})
 *       row is allowed per machine — enforced by a unique partial index.</li>
 *   <li>State transitions use optimistic locking via {@link #version} and
 *       explicit {@code SELECT … FOR UPDATE} in the service.</li>
 * </ul>
 *
 * <p>Audit:
 * <ul>
 *   <li>{@code createdAt} — when the QR check-in succeeded.</li>
 *   <li>{@code grantedAt} — when the device ACKed {@code AUTH_ACK}.</li>
 *   <li>{@code consumedAt} — when the device confirmed {@code STARTED}.</li>
 *   <li>{@code revokedAt} — when the backend or customer cancelled it.</li>
 *   <li>{@code expiresAt} — hard deadline (typically 120 s after creation).</li>
 * </ul>
 */
@Entity
@Table(
    name = "machine_start_authorizations",
    indexes = {
        @Index(name = "idx_msa_machine", columnList = "machine_id"),
        @Index(name = "idx_msa_user", columnList = "user_id"),
        @Index(name = "idx_msa_reservation", columnList = "reservation_id"),
        @Index(name = "idx_msa_status_expires", columnList = "status, expires_at"),
        @Index(name = "idx_msa_boot", columnList = "machine_id, device_boot_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MachineStartAuthorization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** The reservation (booking) that paid for this machine slot; null for admin test runs. */
    @Column(name = "reservation_id")
    private UUID reservationId;

    /** True when an admin unlocked START from the console to test the machine (no booking, no QR). */
    @Column(name = "test_run", nullable = false)
    @Builder.Default
    private boolean testRun = false;

    /** Transaction this reservation belongs to (audit). */
    @Column(name = "transaction_id")
    private UUID transactionId;

    /** The customer's user UUID (denormalized for fast lookup). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Machine this authorization grants access to. */
    @Column(name = "machine_id", nullable = false)
    private UUID machineId;

    /** Machine code (e.g. "WM001") — denormalized for MQTT/log. */
    @Column(name = "machine_code", nullable = false, length = 50)
    private String machineCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MachineStartAuthorizationStatus status = MachineStartAuthorizationStatus.PENDING;

    /**
     * Monotonic expiry wall-clock. Set on creation to
     * {@code createdAt + 120 s} by default.
     */
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "granted_at")
    private OffsetDateTime grantedAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** Reason populated whenever the authorization leaves ACTIVE → terminal. */
    @Column(name = "revoke_reason", length = 200)
    private String revokeReason;

    /**
     * ESP32 boot session ID at the moment {@code AUTH_ACK} was received.
     * Stored ONLY after GRANTED — used to detect stale boot sessions on
     * subsequent {@code START_REQUEST} events.
     */
    @Column(name = "device_boot_id", length = 100)
    private String deviceBootId;

    /** Run ID assigned on START_APPROVAL; null until the cycle actually starts. */
    @Column(name = "run_id")
    private UUID runId;

    /** Optimistic-lock guard against concurrent transitions. */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @PreUpdate
    void onUpdate() {
        // updated_at is implicit through @Version bumping; no-op here.
    }

    public boolean isActive() {
        return status == MachineStartAuthorizationStatus.PENDING
                || status == MachineStartAuthorizationStatus.GRANTED
                || status == MachineStartAuthorizationStatus.START_PENDING;
    }

    public boolean isTerminal() {
        return status == MachineStartAuthorizationStatus.CONSUMED
                || status == MachineStartAuthorizationStatus.EXPIRED
                || status == MachineStartAuthorizationStatus.REVOKED;
    }
}
