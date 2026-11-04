package com.laundry.branch.controller;

import com.laundry.booking.service.BranchBookingReadinessService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import java.util.Set;
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
    private final BranchBookingReadinessService readiness;

    private static boolean isOperator(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(Set.of("ROLE_STAFF", "ROLE_OWNER", "ROLE_ADMIN")::contains);
    }

    @GetMapping
    @Operation(summary = "Active branches: customers see only ready ones (BR-28); operators see all, to set them up")
    public ResponseEntity<?> getActiveBranches(
            @RequestParam(required = false) String serviceId, Authentication authentication) {
        if (serviceId != null && !serviceId.isBlank()) {
            List<BranchServiceDto> offerings = availabilityService.listBranches(serviceId);
            return ResponseEntity.ok(offerings);
        }
        List<BranchDto> active = branchService.getActiveBranches();
        return ResponseEntity.ok(isOperator(authentication) ? active
                : active.stream().filter(branch -> readiness.evaluate(branch.getId()).ready()).toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get branch details by ID")
    public ResponseEntity<BranchDto> getBranchById(@PathVariable UUID id) {
        return ResponseEntity.ok(branchService.getBranchById(id));
    }
}
