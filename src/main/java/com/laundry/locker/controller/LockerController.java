package com.laundry.locker.controller;

import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Roles;
import com.laundry.locker.dto.LockerDtos.CustomerLockerAccess;
import com.laundry.locker.dto.LockerDtos.LockerCabinetDto;
import com.laundry.booking.service.AssistedWeightService;
import com.laundry.locker.dto.LockerDtos.RecordWeightRequest;
import com.laundry.locker.dto.LockerDtos.StaffOpenRequest;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.service.PaymentService;
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
    private final AssistedWeightService weightCharges;
    private final PaymentService payments;

    @GetMapping("/api/bookings/{bookingId}/locker")
    @Operation(summary = "Locker compartment and current PIN (drop-off or pickup) for the customer's assisted-laundry booking")
    public CustomerLockerAccess customerAccess(@PathVariable UUID bookingId, CurrentUser customer) {
        return lockerService.customerAccess(customer.id(), bookingId);
    }

    @GetMapping("/api/staff/lockers")
    @Operation(summary = "Locker cabinets with compartments, door state and the booking in each")
    public List<LockerCabinetDto> cabinets(CurrentUser viewer) {
        return lockerService.cabinets(viewer);
    }

    @PostMapping("/api/staff/lockers/{machineId}/compartments/{no}/open")
    @Operation(summary = "Staff opens a compartment: COLLECT (take laundry), RETURN (put it back), INSPECT (needs a reason)")
    public void open(@PathVariable UUID machineId, @PathVariable int no,
                     @Valid @RequestBody StaffOpenRequest request, CurrentUser staff) {
        lockerService.staffOpen(machineId, no, request.action(), request.reason(), staff);
    }

    @PostMapping("/api/staff/lockers/{machineId}/compartments")
    @PreAuthorize(Roles.OWNER)
    @Operation(summary = "Create the cabinet's compartments 1..count as bookable LOCKER resources")
    public LockerCabinetDto syncCompartments(@PathVariable UUID machineId,
                                             @Valid @RequestBody SyncCompartmentsRequest request) {
        return lockerService.syncCompartments(machineId, request.count());
    }

    @PostMapping("/api/staff/lockers/bookings/{bookingId}/weight")
    @Operation(summary = "Staff weighed an assisted booking's laundry (V65): the customer pays kg × price per kg before pickup")
    public AssistedWeightService.WeightCharge recordWeight(@PathVariable UUID bookingId,
                                                           @Valid @RequestBody RecordWeightRequest request,
                                                           CurrentUser staff) {
        return weightCharges.recordWeight(bookingId, request.weightKg(), staff.id());
    }

    @PostMapping("/api/bookings/{bookingId}/weight-charge/payment")
    @Operation(summary = "Customer pays the weight charge of an assisted booking (wallet first, then payOS)")
    public PaymentResponse payWeightCharge(@PathVariable UUID bookingId,
                                           @RequestParam(defaultValue = "true") boolean useWallet,
                                           CurrentUser customer) {
        return payments.createWeightChargePayment(bookingId, customer.id(), useWallet);
    }
}
