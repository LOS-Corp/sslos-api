package com.laundry.attachment;

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
@Table(name = "attachments")
@Getter
@Setter
public class Attachment extends AssignedIdEntity<UUID> {
    @Id
    private UUID id = UUID.randomUUID();
    /** LAUNDRY_JOB, FOUND_ITEM, WITHDRAWAL, REFUND, LAUNDRY_CLAIM */
    private String ownerType;
    private UUID ownerId;
    /** INTAKE_PHOTO, DAMAGE_PHOTO, ITEM_PHOTO, TRANSFER_PROOF, EVIDENCE */
    private String purpose;
    private String storageKey;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    private UUID uploadedBy;
    @CreationTimestamp
    private Instant createdAt;
}
