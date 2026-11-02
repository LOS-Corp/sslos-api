package com.laundry.locker.controller;

import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Roles;
import com.laundry.locker.dto.LockerDtos.CustomerLockerAccess;
import com.laundry.locker.dto.LockerDtos.LockerCabinetDto;
import com.laundry.locker.dto.LockerDtos.StaffOpenRequest;
import com.laundry.locker.dto.LockerDtos.SyncCompartmentsRequest;
import com.laundry.locker.service.LockerService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Smart locker: the customer's PIN for a booking, and the staff console. */
@RestController
@RequiredArgsConstructor
public class LockerController {

    private final LockerService lockerService;

    @GetMapping("/api/bookings/{bookingId}/locker")
    @Operation(summary = "Locker compartment and current PIN (drop-off or pickup) for the customer's assisted-laundry booking")
    public CustomerLockerAccess customerAccess(@PathVariable UUID bookingId, CurrentUser customer) {
        return lockerService.customerAccess(customer.id(), bookingId);
    }

    @GetMapping("/api/staff/lockers")
    @Operation(summary = "Locker cabinets with compartments, door state and the booking in each")
    public List<LockerCabinetDto> cabinets(@RequestParam(required = false) UUID branchId, CurrentUser viewer) {
        return lockerService.cabinets(branchId, viewer);
    }

    @PostMapping("/api/staff/lockers/{machineId}/compartments/{no}/open")
    @Operation(summary = "Staff opens a compartment: COLLECT (take laundry), RETURN (put it back), INSPECT")
    public void open(@PathVariable UUID machineId, @PathVariable int no,
                     @Valid @RequestBody StaffOpenRequest request, CurrentUser staff) {
        lockerService.staffOpen(machineId, no, request.action(), staff);
    }

    @PostMapping("/api/staff/lockers/{machineId}/compartments")
    @PreAuthorize(Roles.OWNER)
    @Operation(summary = "Create the cabinet's compartments 1..count as bookable LOCKER resources")
    public LockerCabinetDto syncCompartments(@PathVariable UUID machineId,
                                             @Valid @RequestBody SyncCompartmentsRequest request) {
        return lockerService.syncCompartments(machineId, request.count());
    }

    }
