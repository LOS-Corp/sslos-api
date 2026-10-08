package com.laundry.branch.controller;

import com.laundry.branch.dto.BranchDto;
import com.laundry.branch.service.BranchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Public/authenticated read endpoints for branch locations.
 * Used by frontends for dropdown selection and kiosk store discovery.
 */
@RestController
@RequestMapping("/api/branches")
@RequiredArgsConstructor
@Tag(name = "Branches", description = "Branch locations public read API")
public class BranchController {

    private final BranchService branchService;

    @GetMapping
    @Operation(summary = "List all active branches for selection")
    public ResponseEntity<List<BranchDto>> getActiveBranches() {
        return ResponseEntity.ok(branchService.getActiveBranches());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get branch details by ID")
    public ResponseEntity<BranchDto> getBranchById(@PathVariable UUID id) {
        return ResponseEntity.ok(branchService.getBranchById(id));
    }
}
