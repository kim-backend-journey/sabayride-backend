package com.sabayride.rental.discovery.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.sabayride.rental.shop.Shop;

import java.math.BigDecimal;
import java.util.UUID;

/** The customer-facing view of a shop. No revenue, no payout data, no owner id. */
public record ShopSummary(
        UUID id,
        String name,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        String phone,
        Double rating,
        int reviewCount,
        boolean offersDelivery,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal deliveryFee,
        boolean acceptsPayAtShop
) {
    public static ShopSummary from(Shop s) {
        return new ShopSummary(
                s.getId(), s.getName(), s.getAddress(),
                s.getLatitude(), s.getLongitude(), s.getPhone(),
                s.getRating() == null ? null : s.getRating().doubleValue(),
                s.getReviewCount(), s.isOffersDelivery(),
                s.getDeliveryFee(), s.isAcceptsPayAtShop());
    }
}
