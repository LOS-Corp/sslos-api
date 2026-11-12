package com.laundry.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * The store this server runs (V58). The system is installed on a local server inside one store, so
 * there is exactly one row ({@code id = 1}): contact details shown to customers and the booking
 * settings (timezone, turnover buffer).
 */
@Entity
@Table(name = "store_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoreSettings {

    public static final short ID = 1;

    @Id
    @Builder.Default
    private Short id = ID;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 255)
    private String address;

    @Column(length = 20)
    private String phone;

    /** Google Maps embed URL (iframe src); null until the Owner sets it. */
    @Column(name = "map_embed_url", length = 500)
    private String mapEmbedUrl;

    /** IANA timezone of the booking calendar; null until the Owner sets it (not bookable before). */
    @Column(length = 80)
    private String timezone;

    @Column(name = "turnover_buffer_minutes", nullable = false)
    @Builder.Default
    private int turnoverBufferMinutes = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
