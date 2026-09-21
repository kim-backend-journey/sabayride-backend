package com.sabayride.rental.shop;

import com.sabayride.rental.booking.BookingStatus;
import com.sabayride.rental.shop.dto.RejectRequest;
import com.sabayride.rental.shop.dto.ShopBookingResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Everything under /shops/me resolves the shop from the CALLER, never from the
 * request body or a path variable. That is what makes horizontal access between
 * shops impossible by construction rather than by a check somebody has to
 * remember to write on every endpoint.
 *
 * TEMPORARY: the shop comes from a header because identity-service does not
 * exist yet. It becomes the `shopId` claim in the JWT. As written, any caller
 * can act as any shop.
 */
@RestController
@RequestMapping("/api/v1/shops/me")
public class ShopBookingController {

    private final ShopBookingService service;

    public ShopBookingController(ShopBookingService service) {
        this.service = service;
    }

    /** `?status=PENDING` is the requests screen — the one with the 30-minute clock. */
    @GetMapping("/bookings")
    List<ShopBookingResponse> bookings(
            @RequestHeader("X-Shop-Id") UUID shopId,
            @RequestParam(defaultValue = "PENDING") BookingStatus status) {
        return service.byStatus(shopId, status);
    }

    @PostMapping("/bookings/{bookingId}/confirm")
    ShopBookingResponse confirm(@RequestHeader("X-Shop-Id") UUID shopId,
                                @PathVariable UUID bookingId) {
        return service.confirm(shopId, bookingId);
    }

    @PostMapping("/bookings/{bookingId}/reject")
    ShopBookingResponse reject(@RequestHeader("X-Shop-Id") UUID shopId,
                               @PathVariable UUID bookingId,
                               @Valid @RequestBody RejectRequest req) {
        return service.reject(shopId, bookingId, req);
    }
}
