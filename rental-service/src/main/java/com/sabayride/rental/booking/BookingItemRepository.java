package com.sabayride.rental.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface BookingItemRepository extends JpaRepository<BookingItem, UUID> {
    Optional<BookingItem> findByBookingId(UUID bookingId);
}
