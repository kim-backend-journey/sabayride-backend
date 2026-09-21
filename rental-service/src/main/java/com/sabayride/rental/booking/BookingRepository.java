package com.sabayride.rental.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /**
     * THE OVERLAP QUERY — the one expression this whole project turns on.
     *
     *     existing.startDate <  requestedEnd
     * AND existing.endDate   >  requestedStart
     *
     * Strictly less-than and greater-than, because end dates are EXCLUSIVE.
     * A booking 05->07 and a booking 07->09 do NOT overlap: the first ends the
     * morning the second begins. Using <= or >= would reject perfectly valid
     * back-to-back rentals and quietly cost the shop money.
     *
     * Only BLOCKING statuses count. A cancelled booking occupies nothing.
     */
    @Query("""
           SELECT b.assignedMotorbikeId FROM Booking b
            WHERE b.assignedMotorbikeId IN :unitIds
              AND b.status IN :blocking
              AND b.startDate < :endDate
              AND b.endDate   > :startDate
           """)
    List<UUID> findBusyUnitIds(@Param("unitIds") Collection<UUID> unitIds,
                               @Param("blocking") Collection<BookingStatus> blocking,
                               @Param("startDate") LocalDate startDate,
                               @Param("endDate") LocalDate endDate);

    /** Holds the shop never answered. Matches ix_booking_hold_sweep. */
    @Query("""
           SELECT b FROM Booking b
            WHERE b.status = com.sabayride.rental.booking.BookingStatus.PENDING
              AND b.holdExpiresAt IS NOT NULL
              AND b.holdExpiresAt < :now
           """)
    List<Booking> findExpiredHolds(@Param("now") Instant now);

    List<Booking> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    List<Booking> findByShopIdAndStatus(UUID shopId, BookingStatus status);

    Optional<Booking> findByReference(String reference);

    /** Does this customer already hold a booking over these dates? */
    @Query("""
           SELECT COUNT(b) > 0 FROM Booking b
            WHERE b.customerId = :customerId
              AND b.status IN :blocking
              AND b.startDate < :endDate
              AND b.endDate   > :startDate
           """)
    boolean customerHasOverlapping(@Param("customerId") UUID customerId,
                                   @Param("blocking") Collection<BookingStatus> blocking,
                                   @Param("startDate") LocalDate startDate,
                                   @Param("endDate") LocalDate endDate);
}
