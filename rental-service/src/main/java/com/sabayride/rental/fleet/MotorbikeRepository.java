package com.sabayride.rental.fleet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MotorbikeRepository extends JpaRepository<Motorbike, UUID> {

    /**
     * THE LOCK. Takes a row-level write lock on every ACTIVE unit of this model
     * at this shop, so two concurrent bookings cannot both see the same unit as
     * free.
     *
     * 1. ORDER BY m.id gives a DETERMINISTIC lock order. Without it two
     *    transactions can grab the same rows in opposite orders and deadlock.
     * 2. It locks ALL units of the model, not only the free ones — deliberately
     *    coarser, because a lock whose row set depends on a subquery is a lock
     *    with a race inside it.
     *
     * The overlap check runs next, in the service, against these locked rows.
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

    /**
     * Every bookable unit across a set of shops, for search.
     *
     * Deliberately simple: no price, type or delivery filters in SQL. Those are
     * applied in Java afterwards.
     *
     * The trade-off, stated plainly: this reads every active bike of every
     * verified shop into memory. At SabayRide's scale — tens of shops, hundreds
     * of bikes — that is a few hundred small rows and costs nothing, while the
     * grouping logic stays readable enough to verify by eye. Past a few thousand
     * units it should move into SQL with the filters pushed down. That is a
     * change to make when a measurement says so, not before.
     */
    @Query("""
           SELECT m FROM Motorbike m
            WHERE m.shopId IN :shopIds
              AND m.status = com.sabayride.rental.fleet.MotorbikeStatus.ACTIVE
            ORDER BY m.shopId, m.model, m.id
           """)
    List<Motorbike> findBookableInShops(@Param("shopIds") Collection<UUID> shopIds);

    List<Motorbike> findByShopIdAndStatus(UUID shopId, MotorbikeStatus status);

    boolean existsByShopIdAndPlateNumber(UUID shopId, String plateNumber);
}
