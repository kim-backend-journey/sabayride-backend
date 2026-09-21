package com.sabayride.rental.booking;

import com.sabayride.rental.booking.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping("/quote")
    QuoteResponse quote(@Valid @RequestBody BookingRequest req) {
        return service.quote(req);
    }

    /**
     * TEMPORARY: customer identity comes from headers because identity-service
     * does not exist yet. Replace with the JWT subject before this is deployed —
     * as written, any caller can book as anyone.
     */
    @PostMapping
    ResponseEntity<BookingResponse> create(
            @Valid @RequestBody BookingRequest req,
            @RequestHeader(value = "X-Customer-Id") UUID customerId,
            @RequestHeader(value = "X-Customer-Name", defaultValue = "Test Customer")
            String customerName) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(req, customerId, customerName));
    }

    @GetMapping
    List<BookingResponse> mine(@RequestHeader("X-Customer-Id") UUID customerId) {
        return service.myBookings(customerId);
    }
}
