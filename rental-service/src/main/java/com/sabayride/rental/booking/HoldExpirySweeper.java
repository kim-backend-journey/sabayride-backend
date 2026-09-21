package com.sabayride.rental.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Frees bikes held by requests the shop never answered.
 *
 * Without this, every abandoned booking occupies a bike forever and the
 * platform's availability slowly bleeds away — a failure nobody notices for
 * weeks, because each individual case looks like a bike that is simply busy.
 *
 * EXPIRED is not in BookingStatus.BLOCKING, so the moment a row flips the unit
 * is bookable again. No inventory to release, no flag to reset. That is the
 * whole benefit of deriving availability rather than storing it.
 *
 * Runs every minute. At production scale this would need a lock so that two
 * instances do not sweep at once (ShedLock, or SELECT ... FOR UPDATE SKIP
 * LOCKED); with one instance it is not yet a problem, and flipping a row to
 * EXPIRED twice is harmless anyway.
 */
@Component
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

    private final BookingRepository bookings;

    public HoldExpirySweeper(BookingRepository bookings) {
        this.bookings = bookings;
    }

    @Scheduled(fixedDelayString = "${sabayride.hold.sweep-interval-ms:60000}")
    @Transactional
    public void expireAbandonedHolds() {
        List<Booking> expired = bookings.findExpiredHolds(Instant.now());
        if (expired.isEmpty()) return;

        for (Booking b : expired) {
            b.setStatus(BookingStatus.EXPIRED);
            b.setHoldExpiresAt(null);
        }
        bookings.saveAll(expired);

        log.info("Expired {} abandoned booking hold(s): {}", expired.size(),
                 expired.stream().map(Booking::getReference).toList());
    }
}
