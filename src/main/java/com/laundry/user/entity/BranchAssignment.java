package com.laundry.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A Staff account assigned by the Owner to work in one branch (FR-AUTH-03). */
@Entity
@Table(name = "branch_assignments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchAssignment {

    @EmbeddedId
    private Key id;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public BranchAssignment(UUID userId, UUID branchId, UUID assignedBy, OffsetDateTime createdAt) {
        this.id = new Key(userId, branchId);
        this.assignedBy = assignedBy;
        this.createdAt = createdAt;
    }

    public UUID userId() {
        return id.userId;
    }

    public UUID branchId() {
        return id.branchId;
    }

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {
        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(name = "branch_id", nullable = false)
        private UUID branchId;

        public Key(UUID userId, UUID branchId) {
            this.userId = userId;
            this.branchId = branchId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && userId.equals(other.userId) && branchId.equals(other.branchId);
        }

        @Override
        public int hashCode() {
            return 31 * userId.hashCode() + branchId.hashCode();
        }
    }
}
