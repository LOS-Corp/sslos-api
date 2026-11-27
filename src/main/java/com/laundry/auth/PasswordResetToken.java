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

@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
public class PasswordResetToken extends AssignedIdEntity<UUID> {
    @Id
    private UUID id = UUID.randomUUID();
    private UUID userId;
    private String tokenHash;
    private Instant expiresAt;
    private Instant usedAt;
    @CreationTimestamp
    private Instant createdAt;
}
