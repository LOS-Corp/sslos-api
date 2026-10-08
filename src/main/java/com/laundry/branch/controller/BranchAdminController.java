package com.laundry.branch.controller;

import com.laundry.branch.dto.BranchDto;
import com.laundry.branch.dto.CreateBranchRequest;
import com.laundry.branch.dto.UpdateBranchRequest;
import com.laundry.branch.entity.Branch;
import com.laundry.branch.entity.BranchStatus;
import com.laundry.branch.service.BranchService;
import com.laundry.machine.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Admin-only CRUD endpoints for branches/stores.
 * Authorization is secured via SecurityConfig (ROLE_ADMIN, ROLE_OWNER).
 */
@RestController
@RequestMapping("/api/admin/branches")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Branches (Admin)", description = "Admin-only branch/store CRUD")
public class BranchAdminController {

    private final BranchService branchService;

    @PostMapping
    @Operation(summary = "Create a new branch")
    public ResponseEntity<BranchDto> createBranch(@Valid @RequestBody CreateBranchRequest request) {
        BranchDto created = branchService.createBranch(request);
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping
    @Operation(summary = "Paged search across branches")
    public ResponseEntity<PageResponse<BranchDto>> listBranches(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        BranchStatus statusFilter = parseEnum(BranchStatus.class, status, "status");

        // UI uses 1-based page numbers; convert to 0-based for Spring Data.
        Pageable pageable = PageRequest.of(
                Math.max(0, page - 1),
                pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Branch> result = branchService.searchBranches(q, statusFilter, pageable);

        List<BranchDto> content = result.getContent().stream()
                .map(branchService::toDto)
                .toList();

        PageResponse<BranchDto> response = PageResponse.<BranchDto>builder()
                .content(content)
                .page(page)
                .pageSize(pageSize)
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .first(result.isFirst())
                .last(result.isLast())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get branch by ID")
    public ResponseEntity<BranchDto> getBranch(@PathVariable UUID id) {
        return ResponseEntity.ok(branchService.getBranchById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update branch")
    public ResponseEntity<BranchDto> updateBranch(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateBranchRequest request) {
        return ResponseEntity.ok(branchService.updateBranch(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete branch")
    public ResponseEntity<Void> deleteBranch(@PathVariable UUID id) {
        branchService.deleteBranch(id);
        return ResponseEntity.noContent().build();
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String paramName) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid value for '" + paramName + "': '" + raw + "'. Allowed: "
                            + Arrays.toString(type.getEnumConstants()));
        }
    }
}
