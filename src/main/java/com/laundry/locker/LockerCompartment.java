package com.laundry.locker;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "locker_compartments")
@Getter
@Setter
public class LockerCompartment extends AssignedIdEntity<UUID> {
    @Id
    private UUID id = UUID.randomUUID();
    private UUID lockerId;
    private int compartmentNo;
    /** ACTIVE, OUT_OF_SERVICE */
    private String adminStatus = "ACTIVE";
    private Boolean doorClosed;
    private Boolean unlocked;
    private Instant stateUpdatedAt;
}
