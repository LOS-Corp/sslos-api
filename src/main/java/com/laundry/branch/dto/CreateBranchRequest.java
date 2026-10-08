package com.laundry.branch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for creating a new branch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateBranchRequest {

    @NotBlank(message = "Branch name is required")
    @Size(min = 2, max = 120, message = "Branch name must be between 2 and 120 characters")
    private String name;

    @Size(max = 255, message = "Address cannot exceed 255 characters")
    private String address;

    @Size(max = 20, message = "Phone cannot exceed 20 characters")
    private String phone;
}
