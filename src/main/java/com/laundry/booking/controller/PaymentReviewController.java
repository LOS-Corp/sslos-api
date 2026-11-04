package com.laundry.booking.controller;

import com.laundry.booking.service.PaymentReviewService;
import com.laundry.booking.service.PaymentReviewService.PaymentReview;
import com.laundry.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/bookings/payment-reviews")
@RequiredArgsConstructor
@Tag(name = "Payment review", description = "Payments that arrived for a booking that can no longer take them")
public class PaymentReviewController {

    private final PaymentReviewService reviews;

    @GetMapping
    @Operation(summary = "Bookings waiting for a payment review, oldest first")
    public List<PaymentReview> open() {
        return reviews.open();
    }

    @PostMapping("/{bookingId}/resolve")
    @Operation(summary = "Close a payment review with a reason; anything still owed is credited to the wallet")
    public void resolve(@PathVariable UUID bookingId, @Valid @RequestBody ResolveRequest request, CurrentUser owner) {
        reviews.resolve(bookingId, request.reason(), owner);
    }

    public record ResolveRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
