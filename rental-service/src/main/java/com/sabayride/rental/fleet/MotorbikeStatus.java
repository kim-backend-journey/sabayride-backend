package com.sabayride.rental.fleet;

/**
 * The STORED state of a physical unit — what the shop controls.
 *
 * There is deliberately no BOOKED and no RENTED. Whether a bike is out today is
 * a conclusion drawn from the booking table for a given date, not a fact about
 * the bike. Two sources of truth for availability is how double-bookings happen.
 *
 * The database enforces this too: ck_bike_status rejects any other value.
 */
public enum MotorbikeStatus {
    ACTIVE,
    MAINTENANCE,
    INACTIVE
}
