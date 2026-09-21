package com.sabayride.rental.discovery.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.sabayride.rental.fleet.MotorbikeType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Fills the model detail screen: specs, price for the chosen dates, shop card. */
public record ModelDetail(
        UUID shopId,
        String model,
        String brand,
        MotorbikeType type,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pricePerDay,
        String currency,
        int availableUnits,
        LocalDate startDate,
        LocalDate endDate,
        int rentalDays,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal subtotal,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal deliveryFee,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
        List<String> features,
        ShopSummary shop
) { }
