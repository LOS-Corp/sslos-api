package com.laundry.branch.dto;

import com.laundry.branch.entity.BranchStatus;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for updating an existing branch.
 * All fields are optional; only non-null values will be applied.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateBranchRequest {

    @Size(min = 2, max = 120, message = "Branch name must be between 2 and 120 characters")
    private String name;

    @Size(max = 255, message = "Address cannot exceed 255 characters")
    private String address;

    @Size(max = 20, message = "Phone cannot exceed 20 characters")
    private String phone;

    /**
     * URL embed Google Maps (iframe src) cho chi nhánh.
     * Tùy chọn, tối đa 500 ký tự. Phải là URL http(s) hợp lệ nếu cung cấp.
     */
    @Size(max = 500, message = "Map embed URL cannot exceed 500 characters")
    @Pattern(
        regexp = "^(https?://.+)?$",
        message = "Map embed URL must be a valid http(s) URL"
    )
    private String mapEmbedUrl;

    private BranchStatus status;
}
