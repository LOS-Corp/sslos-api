package com.laundry.branch.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents a physical laundry branch or store location.
 */
@Entity
@Table(
    name = "branches",
    indexes = {
        @Index(name = "idx_branch_name", columnList = "name"),
        @Index(name = "idx_branch_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(length = 255)
    private String address;

    @Column(length = 20)
    private String phone;

    /**
     * URL embed Google Maps (iframe src) của chi nhánh.
     * Ví dụ: https://www.google.com/maps/embed?pb=...
     * Có thể NULL khi chưa ghi nhận.
     */
    @Column(name = "map_embed_url", length = 500)
    private String mapEmbedUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BranchStatus status = BranchStatus.ACTIVE;

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
