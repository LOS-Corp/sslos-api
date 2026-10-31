package com.laundry.branch.controller;

import com.laundry.branch.dto.BranchDto;
import com.laundry.branch.service.BranchService;
import com.laundry.booking.dto.BookingDtos.BranchServiceDto;
import com.laundry.booking.service.BookingAvailabilityService;
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
    private final BookingAvailabilityService availabilityService;

    @GetMapping
    @Operation(summary = "List all active branches for selection")
    public ResponseEntity<?> getActiveBranches(
            @RequestParam(required = false) String serviceId) {
        if (serviceId != null && !serviceId.isBlank()) {
            List<BranchServiceDto> offerings = availabilityService.listBranches(serviceId);
            return ResponseEntity.ok(offerings);
        }
        return ResponseEntity.ok(branchService.getActiveBranches());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get branch details by ID")
    public ResponseEntity<BranchDto> getBranchById(@PathVariable UUID id) {
        return ResponseEntity.ok(branchService.getBranchById(id));
    }
}
