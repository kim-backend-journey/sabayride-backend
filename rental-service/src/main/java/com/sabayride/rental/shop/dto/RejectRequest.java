package com.sabayride.rental.shop.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A reason is REQUIRED.
 *
 * An unexplained rejection is what makes a customer stop trusting the platform,
 * and the rejection rate is the number that tells you which shops are not
 * really participating. Neither is possible if the field is optional.
 */
public record RejectRequest(
        @NotNull Reason reason,
        @Size(max = 500) String note
) {
    public enum Reason { BIKE_UNAVAILABLE, SHOP_CLOSED, CUSTOMER_UNREACHABLE, OTHER }
}
