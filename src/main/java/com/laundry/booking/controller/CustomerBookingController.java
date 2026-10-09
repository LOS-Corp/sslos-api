package com.laundry.booking.controller;

import com.laundry.booking.dto.BookingDtos.BookingDto;
import com.laundry.booking.dto.BookingDtos.CheckoutDto;
import com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.laundry.booking.dto.BookingDtos.RecommendationsResponse;
import static com.laundry.booking.dto.BookingDtos.TimelineAvailabilityResponse;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class CustomerBookingController {
    private final ScheduledBookingService bookingService;
    private final PaymentService paymentService;

    @GetMapping("/timeline-availability")
    public TimelineAvailabilityResponse timelineAvailability(@RequestParam UUID branchId,
                                                               @RequestParam String serviceCode,
                                                               @RequestParam LocalDate date) {
        return bookingService.timelineAvailability(branchId, serviceCode, date);
    }

    @GetMapping("/recommendations")
    public RecommendationsResponse recommendations(@RequestParam UUID branchId,
                                                    @RequestParam String serviceCode,
                                                    @RequestParam Instant desiredStartTime,
                                                    @RequestParam(required = false) Instant preferredEndTime) {
        return bookingService.recommendations(branchId, serviceCode, desiredStartTime, preferredEndTime);
    }

    @PostMapping("/holds")
    public BookingDto createHold(@Valid @RequestBody CreateHoldRequest request,
                                 Authentication authentication) {
        return bookingService.createHold(customerId(authentication), request);
    }

    @PostMapping("/{bookingId}/checkout")
    public PaymentResponse checkout(@PathVariable UUID bookingId,
                                    @Valid @RequestBody CheckoutDto request,
                                    Authentication authentication) {
        UUID ownerId = customerId(authentication);
        String key = request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? "booking:" + bookingId : request.idempotencyKey();
        return paymentService.createBookingPayment(
                bookingId, request.transactionId(), key, ownerId);
    }

    @GetMapping("/me")
    public List<BookingDto> myBookings(Authentication authentication) {
        return bookingService.getCustomerBookings(customerId(authentication));
    }

    @GetMapping("/{bookingId}")
    public BookingDto get(@PathVariable UUID bookingId, Authentication authentication) {
        return bookingService.getBooking(customerId(authentication), bookingId);
    }

    @DeleteMapping("/{bookingId}")
    public void cancel(@PathVariable UUID bookingId, Authentication authentication) {
        bookingService.cancelBooking(customerId(authentication), bookingId);
    }

    private UUID customerId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null
                || authentication.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_CUSTOMER".equals(authority.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer account is required");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid customer identity");
        }
    }
}
