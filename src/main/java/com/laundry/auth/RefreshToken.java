package com.laundry.auth;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Chỉ lưu SHA-256 hex của token. familyId: chuỗi xoay vòng từ một lần đăng nhập; dùng lại token cũ = thu hồi cả chuỗi. */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
public class RefreshToken extends AssignedIdEntity<UUID> {
    @Id
    private UUID id = UUID.randomUUID();
    private UUID userId;
    private String tokenHash;
    private UUID familyId;
    private Instant expiresAt;
    private Instant revokedAt;
    private UUID replacedBy;
    private String userAgent;
    private String ipAddress;
    @CreationTimestamp
    private Instant createdAt;
}
