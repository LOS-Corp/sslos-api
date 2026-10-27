package com.laundry.booking.controller;

import com.laundry.booking.dto.BookingDtos.BookingDto;
import com.laundry.booking.dto.BookingDtos.CheckoutDto;
import com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.booking.service.BookingAvailabilityService;
import com.laundry.booking.service.BookingCancellationService;
import com.laundry.booking.service.BookingCancellationService.CancellationPreview;
import com.laundry.shared.security.CurrentUser;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.laundry.booking.dto.BookingDtos.MachineSearchResponse;
import static com.laundry.booking.dto.BookingDtos.RecommendationsResponse;
import static com.laundry.booking.dto.BookingDtos.TimelineAvailabilityResponse;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class CustomerBookingController {
    private final ScheduledBookingService bookingService;
    private final BookingAvailabilityService availabilityService;
    private final BookingCancellationService cancellationService;
    private final PaymentService paymentService;

    @GetMapping("/timeline-availability")
    public TimelineAvailabilityResponse timelineAvailability(@RequestParam UUID branchId,
                                                               @RequestParam String serviceCode,
                                                               @RequestParam LocalDate date,
                                                               @RequestParam(required = false) String washProgram,
                                                               @RequestParam(required = false) String dryProgram) {
        return availabilityService.timelineAvailability(branchId, serviceCode, date, washProgram, dryProgram);
    }

    /**
     * Smart machine search: every compatible machine's earliest valid start
     * inside [{@code from}, {@code to}] (start times, minute precision). The
     * window may cross midnight; it is capped at 48 hours.
     */
    @GetMapping("/machine-search")
    public MachineSearchResponse machineSearch(@RequestParam UUID branchId,
                                               @RequestParam String serviceCode,
                                               @RequestParam Instant from,
                                               @RequestParam Instant to,
                                               @RequestParam(required = false) String washProgram,
                                               @RequestParam(required = false) String dryProgram,
                                               @RequestParam(required = false) BigDecimal declaredLoadKg) {
        return availabilityService.machineSearch(branchId, serviceCode, from, to, washProgram, dryProgram,
                declaredLoadKg);
    }

    @GetMapping("/recommendations")
    public RecommendationsResponse recommendations(@RequestParam UUID branchId,
                                                    @RequestParam String serviceCode,
                                                    @RequestParam Instant desiredStartTime,
                                                    @RequestParam(required = false) Instant preferredEndTime,
                                                    @RequestParam(required = false) String washProgram,
                                                    @RequestParam(required = false) String dryProgram) {
        return availabilityService.recommendations(branchId, serviceCode, desiredStartTime, preferredEndTime,
                washProgram, dryProgram);
    }

    @PostMapping("/holds")
    public BookingDto createHold(@Valid @RequestBody CreateHoldRequest request,
                                 CurrentUser customer) {
        return bookingService.createHold(customer.id(), request);
    }

    @PostMapping("/{bookingId}/checkout")
    public PaymentResponse checkout(@PathVariable UUID bookingId,
                                    @Valid @RequestBody CheckoutDto request,
                                    CurrentUser customer) {
        UUID ownerId = customer.id();
        String key = request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? "booking:" + bookingId : request.idempotencyKey();
        return paymentService.createBookingPayment(
                bookingId, request.transactionId(), key, ownerId, !Boolean.FALSE.equals(request.useWallet()));
    }

    @GetMapping("/me")
    public List<BookingDto> myBookings(CurrentUser customer) {
        return bookingService.getCustomerBookings(customer.id());
    }

    @GetMapping("/{bookingId}")
    public BookingDto get(@PathVariable UUID bookingId, CurrentUser customer) {
        return bookingService.getBooking(customer.id(), bookingId);
    }

    /** MSG17: refund amount and rate if the booking were cancelled now (UC-08 step 2). */
    @GetMapping("/{bookingId}/cancellation-preview")
    public CancellationPreview cancellationPreview(@PathVariable UUID bookingId, CurrentUser customer) {
        return cancellationService.preview(customer.id(), bookingId);
    }

    /** Releases a hold, or cancels a confirmed booking and credits the refund to the wallet (UC-08, BR-11/12). */
    @DeleteMapping("/{bookingId}")
    public CancellationPreview cancel(@PathVariable UUID bookingId, CurrentUser customer) {
        return cancellationService.cancel(customer, bookingId);
    }

    }
