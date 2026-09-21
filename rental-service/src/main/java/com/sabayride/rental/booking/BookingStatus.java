package com.sabayride.rental.booking;

import java.util.EnumSet;
import java.util.Set;

/**
 * PENDING ──confirm──> CONFIRMED ──handover──> ACTIVE ──return──> COMPLETED
 *    ├──reject──> REJECTED
 *    ├──cancel──> CANCELLED
 *    └──hold expiry──> EXPIRED
 */
public enum BookingStatus {
    PENDING, CONFIRMED, ACTIVE, COMPLETED, REJECTED, CANCELLED, EXPIRED;

    /**
     * The three statuses that occupy a bike. Everything else is terminal and
     * cannot affect availability.
     *
     * This set must stay identical to the WHERE clause of the no_double_booking
     * constraint and of ix_booking_availability. If they ever disagree, the
     * database and the application will disagree about what is bookable.
     */
    public static final Set<BookingStatus> BLOCKING =
            EnumSet.of(PENDING, CONFIRMED, ACTIVE);

    public boolean isTerminal() {
        return !BLOCKING.contains(this);
    }
}
