package com.sabayride.rental.discovery.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.sabayride.rental.fleet.MotorbikeType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One shop's offering of one model, for the requested dates.
 *
 * Note what is NOT here: plateNumber, and any per-unit identifier. The customer
 * is choosing a MODEL, not a specific bike. Returning plates would let anyone
 * enumerate a shop's entire fleet from a public, unauthenticated endpoint.
 *
 * `availableUnits` is COMPUTED for the requested range. It is not a column and
 * there is nowhere in the database it could be read from.
 */
public record ModelSearchResult(
        UUID shopId,
        String shopName,
        Double shopRating,
        int shopReviewCount,
        boolean shopVerified,
        String model,
        String brand,
        MotorbikeType type,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pricePerDay,
        String currency,
        int availableUnits,
        boolean offersDelivery,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal deliveryFee
) { }
