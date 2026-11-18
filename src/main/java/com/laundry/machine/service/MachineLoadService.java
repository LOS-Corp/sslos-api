package com.laundry.machine.service;

import com.laundry.booking.domain.LoadPolicy;
import com.laundry.booking.service.LoadMoveService;
import com.laundry.booking.service.LoadMoveService.LargerWasher;
import com.laundry.booking.service.LoadTopUpService;
import com.laundry.booking.service.LoadTopUpService.BookingLoad;
import com.laundry.machine.dto.LoadStatusResponse;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStartAuthorization;
import com.laundry.machine.entity.MachineStartAuthorizationStatus;
import com.laundry.machine.exception.CheckInRejectedException;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.repository.MachineStartAuthorizationRepository;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.payment.service.PaymentService;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What the customer does in the app after the washer weighed their laundry (V61): see the result, pay for the
 * extra kilograms, or move to a larger washer when the laundry is over the machine's capacity. Taking laundry
 * out needs no call: the customer presses START again and the washer weighs again.
 */
@Service
@RequiredArgsConstructor
public class MachineLoadService {

    private final MachineStartAuthorizationRepository authorizations;
    private final MachineRepository machines;
    private final LoadTopUpService topUps;
    private final LoadMoveService moves;
    private final PaymentService payments;
    private final PaymentRepository paymentRepository;
    private final MachineStartAuthorizationService authorizationService;

    /** Load section of the authorization status; null for admin test runs. */
    @Transactional(readOnly = true)
    public LoadStatusResponse describe(MachineStartAuthorization auth) {
        if (auth.getBookingId() == null) {
            return null;
        }
        BookingLoad load = topUps.bookingLoad(auth.getBookingId());
        BigDecimal capacity = machines.findById(auth.getMachineId()).map(Machine::getCapacityKg).orElse(null);
        Integer extraKg = auth.getLoadExtraKg();
        BigDecimal extraAmount = extraKg != null && extraKg > 0 && load.pricePerKg() != null
                ? load.pricePerKg().multiply(BigDecimal.valueOf(extraKg)) : null;
        LoadStatusResponse.TopUp topUp = topUps.pending(auth.getId()).map(pending -> {
            var payment = paymentRepository.findByTransactionIdOrderByCreatedAtDesc(pending.transactionId())
                    .stream().findFirst();
            return new LoadStatusResponse.TopUp(pending.transactionId(), pending.extraKg(), pending.amount(),
                    pending.status(), payment.map(p -> p.getId()).orElse(null),
                    payment.map(p -> p.getStatus().name()).orElse(null),
                    payment.map(p -> p.getCheckoutUrl()).orElse(null));
        }).orElse(null);
        return new LoadStatusResponse(auth.getMeasuredLoadKg(), load.paidKg(), capacity, LoadPolicy.FREE_TOLERANCE_KG,
                auth.getLoadVerdict(), extraKg, load.pricePerKg(), extraAmount, auth.getLoadCheckedAt(), topUp);
    }

    /** Pays for the extra kilograms of the last reading (wallet first, then payOS). */
    @Transactional
    public PaymentResponse payExtra(UUID authorizationId, UUID customerId, boolean useWallet) {
        MachineStartAuthorization auth = openWeighing(authorizationId, customerId);
        if (!"OVER_BOOKED".equals(auth.getLoadVerdict()) || auth.getLoadExtraKg() == null || auth.getLoadExtraKg() <= 0) {
            throw BusinessException.of(ErrorCode.CONFLICT, "OVER_CAPACITY".equals(auth.getLoadVerdict())
                    ? "The laundry is over this machine's capacity: take some out or move to a larger washer"
                    : "Nothing extra to pay for this weight");
        }
        var topUp = topUps.prepare(auth.getBookingId(), auth.getId(), customerId, auth.getLoadExtraKg(),
                auth.getExpiresAt());
        return payments.createLoadTopUpPayment(topUp.transactionId(), customerId, useWallet);
    }

    /** Free washers large enough for the weighed laundry. */
    @Transactional(readOnly = true)
    public List<LargerWasher> largerMachines(UUID authorizationId, UUID customerId) {
        MachineStartAuthorization auth = openWeighing(authorizationId, customerId);
        return moves.largerWashers(auth.getAllocationId(), auth.getMachineId(), auth.getMeasuredLoadKg());
    }

    /**
     * Moves the slot to a larger free washer and locks START on this one. The customer unlocks the new washer
     * (scan or the app) and weighs again there; extra kilograms, if any, are paid there.
     */
    @Transactional
    public LoadStatusResponse.MoveResponse move(UUID authorizationId, UUID customerId, UUID targetMachineId) {
        MachineStartAuthorization auth = openWeighing(authorizationId, customerId);
        if (!"OVER_CAPACITY".equals(auth.getLoadVerdict())) {
            throw BusinessException.of(ErrorCode.CONFLICT, "The laundry fits this machine; no need to move");
        }
        String code = moves.move(auth.getAllocationId(), auth.getMachineId(), auth.getMeasuredLoadKg(), targetMachineId);
        authorizationService.revokeForMove(auth.getId(), code);
        return new LoadStatusResponse.MoveResponse(code);
    }

    /** The customer's open authorization whose laundry was weighed. */
    private MachineStartAuthorization openWeighing(UUID authorizationId, UUID customerId) {
        MachineStartAuthorization auth = authorizations.findByIdAndUserId(authorizationId, customerId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Authorization not found"));
        if (auth.getStatus() != MachineStartAuthorizationStatus.GRANTED
                || !auth.getExpiresAt().isAfter(OffsetDateTime.now())) {
            throw new CheckInRejectedException("The machine is no longer unlocked for you; unlock it again");
        }
        if (auth.getBookingId() == null || auth.getMeasuredLoadKg() == null) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Put the laundry on the scale and press START first");
        }
        return auth;
    }
}
