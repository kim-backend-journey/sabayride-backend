package com.sabayride.rental.booking.dto;

import com.sabayride.rental.booking.PaymentOption;
import com.sabayride.rental.booking.PickupMethod;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Same body for /bookings/quote and /bookings.
 *
 * Note `model` — a model NAME, not a motorbike id. The server picks the unit
 * under a lock. Letting the client choose the unit would reintroduce exactly
 * the race the lock exists to prevent.
 */
public record BookingRequest(

        @NotNull UUID shopId,

        @NotBlank String model,

        @NotNull @FutureOrPresent LocalDate startDate,

        /** EXCLUSIVE. 05 -> 07 is a two-day rental. */
        @NotNull @Future LocalDate endDate,

        @NotNull PickupMethod pickupMethod,

        String deliveryAddress,

        @NotNull PaymentOption paymentOption,

        @Size(max = 500) String customerNote
) { }
