package com.laundry.locker;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Tủ locker giặt hộ. Khách mở ô bằng QR vé tại kiosk, không thao tác trên tủ. */
@Entity
@Table(name = "lockers")
@Getter
@Setter
public class Locker extends AssignedIdEntity<UUID> {
    @Id
    private UUID id = UUID.randomUUID();
    private String code;
    private String name;
    /** ACTIVE, MAINTENANCE, RETIRED */
    private String adminStatus = "ACTIVE";
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
    @Version
    private long version;
}
