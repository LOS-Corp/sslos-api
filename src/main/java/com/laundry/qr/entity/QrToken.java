package com.laundry.qr.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QR Token entity
 */
@Entity
@Table(name = "qr_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QrTokenType type;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(name = "reference_type", nullable = false)
    private String referenceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private QrTokenStatus status = QrTokenStatus.ACTIVE;

    @Column(name = "issued_at")
    @Builder.Default
    private LocalDateTime issuedAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "used_by")
    private String usedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_reason")
    private String revokedReason;

    public boolean isValid() {
        return status == QrTokenStatus.ACTIVE && expiresAt.isAfter(LocalDateTime.now());
    }

    public void markAsUsed(String usedBy) {
        this.status = QrTokenStatus.USED;
        this.usedAt = LocalDateTime.now();
        this.usedBy = usedBy;
    }

    public void revoke(String reason) {
        this.status = QrTokenStatus.REVOKED;
        this.revokedAt = LocalDateTime.now();
        this.revokedReason = reason;
    }

    public void markAsExpired() {
        this.status = QrTokenStatus.EXPIRED;
    }
}
