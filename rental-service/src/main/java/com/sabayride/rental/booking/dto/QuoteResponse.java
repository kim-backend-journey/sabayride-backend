package com.sabayride.rental.booking.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Every money field is serialised as a STRING, not a JSON number.
 *
 * JSON numbers are IEEE-754 doubles. "18.00" survives the round trip; 18.00 as
 * a number can come back as 18.000000000000004. Flutter parses these with
 * Decimal.parse, never double.parse.
 */
public record QuoteResponse(
        UUID shopId,
        String model,
        LocalDate startDate,
        LocalDate endDate,
        int rentalDays,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pricePerDay,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal subtotal,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal deliveryFee,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
        String currency,
        BigDecimal commissionRate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal platformFee,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal shopPayout,
        /** Advisory only. POST /bookings is the only authority on availability. */
        int availableUnits
) { }
