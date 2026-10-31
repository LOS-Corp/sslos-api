package com.laundry.machine.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * When a customer may check in at the machine (BR-06, BR-07). Pure function.
 * <ul>
 *   <li>Opens at T−15 (book-later customers may arrive early).</li>
 *   <li>Closes 10 minutes after the later of T and the payment: T+10 for a book-later order,
 *       PAID+10 for a use-now order paid just after its start minute.</li>
 * </ul>
 * A payment can only confirm a booking inside its hold, so the payment time used here is capped at
 * T + hold; anything later is a review case and must not reopen the window.
 */
public final class CheckInWindowPolicy {

    private final Duration opensBefore;
    private final Duration closesAfter;
    private final Duration holdDuration;

    public CheckInWindowPolicy(Duration opensBefore, Duration closesAfter, Duration holdDuration) {
        this.opensBefore = opensBefore;
        this.closesAfter = closesAfter;
        this.holdDuration = holdDuration;
    }

    public Window windowFor(Instant bookedStart, Instant paidAt) {
        Instant effectivePaid = paidAt == null ? bookedStart : earliest(paidAt, bookedStart.plus(holdDuration));
        Instant anchor = effectivePaid.isAfter(bookedStart) ? effectivePaid : bookedStart;
        return new Window(bookedStart.minus(opensBefore), anchor.plus(closesAfter));
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    public enum Position { NOT_YET_OPEN, OPEN, CLOSED }

    public record Window(Instant opensAt, Instant closesAt) {

        public Position positionOf(Instant now) {
            if (now.isBefore(opensAt)) {
                return Position.NOT_YET_OPEN;
            }
            return now.isAfter(closesAt) ? Position.CLOSED : Position.OPEN;
        }
    }
}
