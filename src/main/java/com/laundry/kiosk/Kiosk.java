package com.laundry.kiosk;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kiosks")
@Getter
@Setter
public class Kiosk extends AssignedIdEntity<UUID> {

    @Id
    private UUID id = UUID.randomUUID();
    private String code;
    private String name;
    @Column(name = "api_key_hash")
    private String apiKeyHash;
    private String status = "ACTIVE";
    private Instant lastSeenAt;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
