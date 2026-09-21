package com.sabayride.rental.booking.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.sabayride.rental.booking.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The customer-facing view of a booking.
 *
 * Deliberately absent: assignedMotorbikeId and assignedPlateNumber. A customer
 * books a MODEL; exposing the plate would let anyone enumerate a shop's fleet,
 * and it is information the customer has no use for until handover.
 */
public record BookingResponse(
        UUID id,
        String reference,
        BookingStatus status,
        PaymentStatus paymentStatus,
        UUID shopId,
        String model,
        LocalDate startDate,
        LocalDate endDate,
        int rentalDays,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal subtotal,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal deliveryFee,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal platformFee,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal shopPayout,
        PickupMethod pickupMethod,
        PaymentOption paymentOption,
        Instant holdExpiresAt,
        Instant createdAt
) {
    public static BookingResponse from(Booking b, String model) {
        return new BookingResponse(
                b.getId(), b.getReference(), b.getStatus(), b.getPaymentStatus(),
                b.getShopId(), model, b.getStartDate(), b.getEndDate(), b.rentalDays(),
                b.getSubtotal(), b.getDeliveryFee(), b.getTotal(), b.getCurrency(),
                b.getPlatformFee(), b.getShopPayout(),
                b.getPickupMethod(), b.getPaymentOption(),
                b.getHoldExpiresAt(), b.getCreatedAt());
    }
}
