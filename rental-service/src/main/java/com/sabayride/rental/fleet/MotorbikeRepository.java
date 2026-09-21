package com.sabayride.rental.fleet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MotorbikeRepository extends JpaRepository<Motorbike, UUID> {

    /**
     * THE LOCK. Takes a row-level write lock on every ACTIVE unit of this model
     * at this shop, so two concurrent bookings cannot both see the same unit as
     * free.
     *
     * Two details that matter:
     *
     * 1. ORDER BY m.id — a DETERMINISTIC lock order. Without it, two
     *    transactions can grab the same rows in opposite orders and deadlock.
     *    PostgreSQL would detect it and kill one, but a 500 is not the error
     *    the customer should see.
     *
     * 2. This locks ALL units of the model, not just the free ones. Slightly
     *    coarser than strictly necessary, and deliberately so: the alternative
     *    is a lock whose result depends on a subquery that could change between
     *    planning and locking. Locking a handful of extra rows for a few
     *    milliseconds costs nothing at this scale; a subtle race costs a
     *    double-booking.
     *
     * The overlap check happens next, in the service, against these locked rows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT m FROM Motorbike m
            WHERE m.shopId = :shopId
              AND m.model  = :model
              AND m.status = com.sabayride.rental.fleet.MotorbikeStatus.ACTIVE
            ORDER BY m.id
           """)
    List<Motorbike> lockUnitsOfModel(@Param("shopId") UUID shopId,
                                     @Param("model") String model);

    List<Motorbike> findByShopIdAndStatus(UUID shopId, MotorbikeStatus status);

    boolean existsByShopIdAndPlateNumber(UUID shopId, String plateNumber);
}
