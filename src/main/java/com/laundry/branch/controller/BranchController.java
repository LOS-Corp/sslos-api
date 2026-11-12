package com.laundry.branch.controller;

import com.laundry.booking.service.BranchBookingReadinessService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import java.util.Set;
import com.laundry.branch.dto.BranchDto;
import com.laundry.branch.service.BranchService;
import com.laundry.branch.service.StoreService;
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
 * Old read endpoints for branch locations, kept for clients that still ask for a branch list
 * (the mobile app). Single-store mode: the list holds only the store ({@link StoreController}).
 */
@RestController
@RequestMapping("/api/branches")
@RequiredArgsConstructor
@Tag(name = "Branches", description = "Branch locations public read API")
public class BranchController {

    private final BranchService branchService;
    private final BookingAvailabilityService availabilityService;
    private final BranchBookingReadinessService readiness;
    private final StoreService store;

    private static boolean isOperator(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(Set.of("ROLE_STAFF", "ROLE_OWNER", "ROLE_ADMIN")::contains);
    }

    @GetMapping
    @Operation(summary = "The store as a one-item list; customers see it only once it is ready to book (BR-28)")
    public ResponseEntity<?> getActiveBranches(
            @RequestParam(required = false) String serviceId, Authentication authentication) {
        UUID storeId = store.id();
        if (serviceId != null && !serviceId.isBlank()) {
            List<BranchServiceDto> offerings = availabilityService.listBranches(serviceId).stream()
                    .filter(offering -> storeId.equals(offering.branchId())).toList();
            return ResponseEntity.ok(offerings);
        }
        List<BranchDto> active = List.of(branchService.toDto(store.current()));
        return ResponseEntity.ok(isOperator(authentication) ? active
                : active.stream().filter(branch -> readiness.evaluate(branch.getId()).ready()).toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get branch details by ID")
    public ResponseEntity<BranchDto> getBranchById(@PathVariable UUID id) {
        return ResponseEntity.ok(branchService.getBranchById(id));
    }
}
