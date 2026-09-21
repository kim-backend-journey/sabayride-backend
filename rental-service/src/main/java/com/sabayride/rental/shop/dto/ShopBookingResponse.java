package com.sabayride.rental.shop.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.sabayride.rental.booking.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The SHOP's view of a booking. Different from the customer's in two ways that
 * matter:
 *
 *  - `assignedPlateNumber` IS present. The staff member has to know which bike
 *    to wheel out. It is never in a customer-facing response.
 *
 *  - `customerPhone` is NULL until the booking is CONFIRMED. A shop that could
 *    read the phone number of a PENDING request could reject it and call the
 *    customer directly — and the platform would have handed over the lead for
 *    nothing. The number is released the moment the shop commits. (FR-25)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShopBookingResponse(
        UUID id,
        String reference,
        BookingStatus status,
        PaymentStatus paymentStatus,
        String customerName,
        String customerPhone,
        String model,
        String assignedPlateNumber,
        LocalDate startDate,
        LocalDate endDate,
        int rentalDays,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal subtotal,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal deliveryFee,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal platformFee,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal shopPayout,
        String currency,
        PickupMethod pickupMethod,
        String deliveryAddress,
        PaymentOption paymentOption,
        String customerNote,
        Instant holdExpiresAt,
        Instant confirmedAt,
        Instant createdAt
) {
    public static ShopBookingResponse from(Booking b, BookingItem item) {
        boolean released = b.getStatus() != BookingStatus.PENDING;
        return new ShopBookingResponse(
                b.getId(), b.getReference(), b.getStatus(), b.getPaymentStatus(),
                b.getCustomerName(),
                released ? b.getCustomerPhone() : null,
                item == null ? null : item.getModel(),
                item == null ? null : item.getAssignedPlateNumber(),
                b.getStartDate(), b.getEndDate(), b.rentalDays(),
                b.getSubtotal(), b.getDeliveryFee(), b.getTotal(),
                b.getPlatformFee(), b.getShopPayout(), b.getCurrency(),
                b.getPickupMethod(), b.getDeliveryAddress(), b.getPaymentOption(),
                b.getCustomerNote(),
                b.getHoldExpiresAt(), b.getConfirmedAt(), b.getCreatedAt());
    }
}
