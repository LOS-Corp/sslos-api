package com.laundry.store;

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
@Table(name = "store_closures")
@Getter
@Setter
public class StoreClosure extends AssignedIdEntity<UUID> {
    @Id
    private UUID id = UUID.randomUUID();
    private Instant startsAt;
    private Instant endsAt;
    private String reason;
    private UUID createdBy;
    @CreationTimestamp
    private Instant createdAt;
}
