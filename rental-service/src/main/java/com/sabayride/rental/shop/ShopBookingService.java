package com.sabayride.rental.shop;

import com.sabayride.rental.booking.*;
import com.sabayride.rental.common.ApiException;
import com.sabayride.rental.shop.dto.RejectRequest;
import com.sabayride.rental.shop.dto.ShopBookingResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ShopBookingService {

    private final BookingRepository bookings;
    private final BookingItemRepository items;

    public ShopBookingService(BookingRepository bookings, BookingItemRepository items) {
        this.bookings = bookings;
        this.items = items;
    }

    @Transactional(readOnly = true)
    public List<ShopBookingResponse> byStatus(UUID shopId, BookingStatus status) {
        return bookings.findByShopIdAndStatus(shopId, status).stream()
                .map(b -> ShopBookingResponse.from(b,
                        items.findByBookingId(b.getId()).orElse(null)))
                .toList();
    }

    /**
     * PENDING -> CONFIRMED.
     *
     * Clears the hold expiry (the booking is now committed, not held), stamps
     * confirmedAt, and releases the customer's phone number to the shop.
     */
    @Transactional
    public ShopBookingResponse confirm(UUID shopId, UUID bookingId) {
        Booking b = ownedPending(shopId, bookingId);

        b.setStatus(BookingStatus.CONFIRMED);
        b.setConfirmedAt(Instant.now());
        b.setHoldExpiresAt(null);

        return ShopBookingResponse.from(bookings.save(b),
                items.findByBookingId(b.getId()).orElse(null));
    }

    /**
     * PENDING -> REJECTED.
     *
     * The unit becomes free immediately: REJECTED is not in BookingStatus.BLOCKING,
     * so the very next availability query stops counting it. Nothing needs to be
     * "released" because nothing was ever reserved — the booking row WAS the
     * reservation.
     *
     * That is the payoff of deriving availability instead of storing it. With a
     * stored flag, this method would also have to remember to flip the bike back
     * to AVAILABLE, and the day somebody forgot would be the day a bike silently
     * disappeared from the platform.
     */
    @Transactional
    public ShopBookingResponse reject(UUID shopId, UUID bookingId, RejectRequest req) {
        Booking b = ownedPending(shopId, bookingId);

        b.setStatus(BookingStatus.REJECTED);
        b.setHoldExpiresAt(null);
        // TODO when payment lands: refund in full if paymentStatus == PAID.

        return ShopBookingResponse.from(bookings.save(b),
                items.findByBookingId(b.getId()).orElse(null));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Loads a booking that belongs to THIS shop and is still PENDING.
     *
     * A booking belonging to another shop returns 404, not 403. A 403 would
     * confirm the id exists, which is all an attacker needs to start mapping
     * other shops' business.
     */
    private Booking ownedPending(UUID shopId, UUID bookingId) {
        Booking b = bookings.findById(bookingId)
                .filter(x -> x.getShopId().equals(shopId))
                .orElseThrow(() -> ApiException.notFound(
                        "BOOKING_NOT_FOUND", "Booking not found."));

        if (b.getStatus() != BookingStatus.PENDING) {
            throw ApiException.conflict("ILLEGAL_STATE_TRANSITION",
                    "This request is already " + b.getStatus() + ".");
        }
        if (b.getHoldExpiresAt() != null && b.getHoldExpiresAt().isBefore(Instant.now())) {
            // The sweep job has not run yet, but the hold is gone. Say so
            // honestly rather than confirming a booking the customer has been
            // told is dead.
            throw ApiException.conflict("BOOKING_ALREADY_EXPIRED",
                    "This request expired before it was answered.");
        }
        return b;
    }
}
