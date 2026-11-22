package com.laundry.kiosk;

import com.laundry.booking.dto.BookingDtos.BookingDto;
import com.laundry.kiosk.KioskService.KioskHoldRequest;
import com.laundry.kiosk.KioskService.KioskRefundRequest;
import com.laundry.kiosk.KioskService.KioskStartRequest;
import com.laundry.kiosk.KioskService.KioskStartStatus;
import com.laundry.machine.dto.CheckInResponse;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.refund.dto.response.RefundResponse;
import com.laundry.shared.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * In-store kiosk (V72), role KIOSK only. Finding a free machine uses the public availability API
 * ({@code /api/bookings/recommendations}); this controller books, takes the payOS payment and unlocks START.
 */
@RestController
@RequestMapping("/api/kiosk")
@RequiredArgsConstructor
public class KioskController {

    private final KioskService kiosk;

    @PostMapping("/holds")
    public BookingDto hold(@Valid @RequestBody KioskHoldRequest request, CurrentUser device) {
        return kiosk.hold(device, request);
    }

    @GetMapping("/bookings/{bookingId}")
    public BookingDto get(@PathVariable UUID bookingId, CurrentUser device) {
        return kiosk.get(device, bookingId);
    }

    @PostMapping("/bookings/{bookingId}/checkout")
    public PaymentResponse checkout(@PathVariable UUID bookingId, CurrentUser device) {
        return kiosk.checkout(device, bookingId);
    }

    @DeleteMapping("/bookings/{bookingId}")
    public void cancel(@PathVariable UUID bookingId, CurrentUser device) {
        kiosk.cancel(device, bookingId);
    }

    @PostMapping("/bookings/{bookingId}/start")
    public CheckInResponse start(@PathVariable UUID bookingId, @Valid @RequestBody KioskStartRequest request,
                                 CurrentUser device) {
        return kiosk.start(device, bookingId, request.machineCode());
    }

    @GetMapping("/starts/{authorizationId}")
    public KioskStartStatus startStatus(@PathVariable UUID authorizationId, CurrentUser device) {
        return kiosk.startStatus(device, authorizationId);
    }

    @PostMapping("/bookings/{bookingId}/refund-request")
    public RefundResponse refund(@PathVariable UUID bookingId, @Valid @RequestBody KioskRefundRequest request,
                                 CurrentUser device) {
        return kiosk.requestRefund(device, bookingId, request);
    }
}
