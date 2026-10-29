package com.laundry.machine.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Optional filter for admin list endpoint. Every field is optional.
 *
 * <p>Used by {@code GET /api/admin/machines?status=...&type=...&branchId=...&q=...}.
 * Free-text {@code q} matches case-insensitively against machineCode and
 * displayName. Page/pageSize are 1-based to match admin UIs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MachineListFilter {

    @Min(value = 1, message = "page must be >= 1")
    @Max(value = 100_000, message = "page must be <= 100000")
    private Integer page;

    @Min(value = 1, message = "pageSize must be >= 1")
    @Max(value = 200, message = "pageSize must be <= 200")
    private Integer pageSize;

    /** Free-text search across machineCode + displayName (case-insensitive). */
    private String q;

    /** Exact status filter. */
    private String status;

    /** Exact type filter. */
    private String type;

    /** Exact branchId filter. */
    private UUID branchId;
}
