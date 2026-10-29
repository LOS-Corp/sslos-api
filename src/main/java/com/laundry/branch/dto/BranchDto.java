package com.laundry.branch.dto;

import com.laundry.branch.entity.BranchStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Representation of a branch returned from the API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BranchDto {

    private UUID id;
    private String name;
    private String address;
    private String phone;
    /** URL embed Google Maps (iframe src) của chi nhánh. Null khi chưa ghi nhận. */
    private String mapEmbedUrl;
    private BranchStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
