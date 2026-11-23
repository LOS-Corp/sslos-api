package com.laundry.store;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;
import java.util.UUID;

/** Khung giờ trong ngày. OPEN = mở cửa; STAFFED = có nhân viên trực (§10.1, §12). dayOfWeek ISO 1..7. */
@Entity
@Table(name = "store_hours")
@Getter
@Setter
public class StoreHour extends AssignedIdEntity<UUID> {

    public enum Kind { OPEN, STAFFED }

    @Id
    private UUID id = UUID.randomUUID();
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private int dayOfWeek;
    private LocalTime opensAt;
    private LocalTime closesAt;
}
