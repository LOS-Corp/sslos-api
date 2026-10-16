package com.laundry.transaction.scheduler;

import com.laundry.machine.entity.MachineStartAuthorizationStatus;
import com.laundry.transaction.repository.MachineReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Closes out paid reservations whose window has ended. Nothing else moves a
 * reservation out of CONFIRMED/ACTIVE, so without this sweep old rows linger
 * forever and can be picked up by QR check-in.
 *
 * <p>A grace period after {@code endTime} leaves room for a cycle that
 * started near the end of its window. Used reservations (CONSUMED start
 * authorization) become COMPLETED; the rest are no-shows → EXPIRED.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationWindowCloseScheduler {

    private final MachineReservationRepository reservationRepository;

    @Value("${app.reservation.close-grace-minutes:30}")
    private long graceMinutes;

    @Scheduled(fixedDelayString = "${app.reservation.close-sweep-millis:300000}",
               initialDelay = 30_000)
    @Transactional
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusMinutes(graceMinutes);
        int completed = reservationRepository.completeEnded(
                cutoff, now, MachineStartAuthorizationStatus.CONSUMED);
        int expired = reservationRepository.expireEnded(cutoff, now);
        if (completed + expired > 0) {
            log.info("Reservation window sweep: {} COMPLETED, {} EXPIRED (cutoff={})",
                    completed, expired, cutoff);
        }
    }
}
