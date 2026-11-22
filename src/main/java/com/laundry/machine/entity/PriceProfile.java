package com.laundry.machine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * How a machine is priced (V64): {@code basePrice} per cycle (V65). A machine without a profile is
 * priced by the store's service offering.
 */
@Entity
@Table(name = "price_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "machine_type", nullable = false, length = 20)
    private MachineType machineType;

    /** Price of one cycle (V65: self-service is no longer priced by weight). */
    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(length = 300)
    private String description;

    /**
     * Minutes of one cycle (V69). A washer runs its own firmware program: this only sizes the booking. A dryer runs
     * exactly this long (the basic package), and customers can buy {@link #extraMinutes} more.
     */
    @Column(name = "cycle_minutes", nullable = false)
    private Integer cycleMinutes;

    /** Dryers only (V66): minutes a customer can buy while drying; null = no extra time on sale. */
    @Column(name = "extra_minutes")
    private Integer extraMinutes;

    /** Price of {@link #extraMinutes}; set together with it. */
    @Column(name = "extra_price", precision = 10, scale = 2)
    private BigDecimal extraPrice;

    /** Extra drying time is on sale. */
    public boolean sellsExtraTime() {
        return extraMinutes != null && extraPrice != null;
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
