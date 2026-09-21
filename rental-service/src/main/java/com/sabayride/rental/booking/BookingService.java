package com.sabayride.rental.booking;

import com.sabayride.rental.booking.dto.*;
import com.sabayride.rental.common.ApiException;
import com.sabayride.rental.fleet.Motorbike;
import com.sabayride.rental.fleet.MotorbikeRepository;
import com.sabayride.rental.shop.Shop;
import com.sabayride.rental.shop.ShopRepository;
import com.sabayride.rental.shop.ShopStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class BookingService {

    /** Rental days are calendar days in Cambodia, not 24-hour windows. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Phnom_Penh");
    private static final int MAX_RENTAL_DAYS = 30;

    private final ShopRepository shops;
    private final MotorbikeRepository motorbikes;
    private final BookingRepository bookings;
    private final BookingItemRepository items;

    @Value("${sabayride.commission-rate:0.10}")
    private BigDecimal commissionRate;

    @Value("${sabayride.hold.same-day-minutes:30}")
    private int sameDayHoldMinutes;

    @Value("${sabayride.hold.future-hours:2}")
    private int futureHoldHours;

    public BookingService(ShopRepository shops, MotorbikeRepository motorbikes,
                          BookingRepository bookings, BookingItemRepository items) {
        this.shops = shops;
        this.motorbikes = motorbikes;
        this.bookings = bookings;
        this.items = items;
    }

    // ─────────────────────────────────────────────────────────────────────
    // QUOTE — reserves nothing
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Prices a booking without creating it.
     *
     * Read-only and unlocked on purpose: two customers can hold the same quote
     * at the same time. `availableUnits` is a courtesy so the UI can warn
     * "only 1 left" — it is NOT a reservation, and a successful quote is never
     * a guarantee that the booking will succeed.
     */
    @Transactional(readOnly = true)
    public QuoteResponse quote(BookingRequest req) {
        validateDates(req.startDate(), req.endDate());
        Shop shop = verifiedShop(req.shopId());

        List<Motorbike> units = motorbikes.findByShopIdAndStatus(
                shop.getId(), com.sabayride.rental.fleet.MotorbikeStatus.ACTIVE)
                .stream().filter(m -> m.getModel().equals(req.model())).toList();

        if (units.isEmpty()) {
            throw ApiException.notFound("MODEL_NOT_FOUND",
                    "That shop does not offer this model.");
        }

        Set<UUID> busy = new HashSet<>(bookings.findBusyUnitIds(
                units.stream().map(Motorbike::getId).toList(),
                BookingStatus.BLOCKING, req.startDate(), req.endDate()));

        int free = (int) units.stream().filter(u -> !busy.contains(u.getId())).count();

        Motorbike sample = units.get(0);
        int days = rentalDays(req.startDate(), req.endDate());
        BigDecimal subtotal = sample.getPricePerDay().multiply(BigDecimal.valueOf(days));
        BigDecimal deliveryFee = deliveryFeeFor(shop, req.pickupMethod());
        BigDecimal total = subtotal.add(deliveryFee);
        BigDecimal platformFee = subtotal.multiply(commissionRate)
                                         .setScale(2, RoundingMode.HALF_UP);

        return new QuoteResponse(
                shop.getId(), req.model(), req.startDate(), req.endDate(), days,
                sample.getPricePerDay(), subtotal, deliveryFee, total,
                sample.getCurrency(), commissionRate, platformFee,
                total.subtract(platformFee), free);
    }

    // ─────────────────────────────────────────────────────────────────────
    // CREATE — the concurrency-critical write
    // ─────────────────────────────────────────────────────────────────────

    /**
     * THE transaction this whole project turns on.
     *
     *   1. Validate the range and the shop.
     *   2. SELECT ... FOR UPDATE over this shop's units of this model, in a
     *      deterministic order. From here no other transaction can read these
     *      rows for update until we commit or roll back.
     *   3. Re-run the overlap query against those now-locked rows.
     *   4. No unit free -> roll back, 409 NO_UNITS_AVAILABLE.
     *   5. Otherwise take one, insert the booking PENDING with a hold expiry,
     *      insert the snapshot item, commit.
     *
     * Step 2 is why a concrete unit is assigned HERE and not at pickup: you
     * cannot lock a model, only rows. The model is an abstraction; the lock
     * needs something physical to hold on to.
     *
     * Nothing inside this method makes a network call. A remote call inside an
     * open transaction holds the row locks for the duration of someone else's
     * timeout, and one slow dependency becomes a system-wide stall.
     */
    @Transactional
    public BookingResponse create(BookingRequest req, UUID customerId, String customerName) {
        validateDates(req.startDate(), req.endDate());
        Shop shop = verifiedShop(req.shopId());

        if (req.pickupMethod() == PickupMethod.DELIVERY
                && (req.deliveryAddress() == null || req.deliveryAddress().isBlank())) {
            throw ApiException.badRequest("DELIVERY_ADDRESS_REQUIRED",
                    "Please provide a delivery address.", "deliveryAddress");
        }

        if (bookings.customerHasOverlapping(customerId, BookingStatus.BLOCKING,
                                            req.startDate(), req.endDate())) {
            throw ApiException.conflict("CUSTOMER_HAS_OVERLAPPING_BOOKING",
                    "You already have a booking for those dates.");
        }

        // ── 2. THE LOCK ──────────────────────────────────────────────────
        List<Motorbike> locked = motorbikes.lockUnitsOfModel(shop.getId(), req.model());
        if (locked.isEmpty()) {
            throw ApiException.notFound("MODEL_NOT_FOUND",
                    "That shop does not offer this model.");
        }

        // ── 3. overlap check against the locked rows ─────────────────────
        Set<UUID> busy = new HashSet<>(bookings.findBusyUnitIds(
                locked.stream().map(Motorbike::getId).toList(),
                BookingStatus.BLOCKING, req.startDate(), req.endDate()));

        Motorbike unit = locked.stream()
                .filter(m -> !busy.contains(m.getId()))
                .findFirst()
                // ── 4. lost the race ─────────────────────────────────────
                .orElseThrow(() -> ApiException.conflict("NO_UNITS_AVAILABLE",
                        "That bike was just booked by someone else. Please choose another."));

        // ── 5. write ─────────────────────────────────────────────────────
        int days = rentalDays(req.startDate(), req.endDate());
        BigDecimal subtotal = unit.getPricePerDay().multiply(BigDecimal.valueOf(days));
        BigDecimal deliveryFee = deliveryFeeFor(shop, req.pickupMethod());

        Booking booking = Booking.create(
                customerId, customerName, shop.getId(),
                req.startDate(), req.endDate(), unit.getId(),
                req.pickupMethod(), req.paymentOption(),
                subtotal, deliveryFee, commissionRate,
                holdExpiryFor(req.startDate()));

        booking.setDeliveryAddress(req.deliveryAddress());
        booking.setCustomerNote(req.customerNote());
        booking = bookings.saveAndFlush(booking);

        items.save(BookingItem.snapshotOf(booking.getId(), unit, days, deliveryFee));

        return BookingResponse.from(booking, unit.getModel());
    }

    // ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BookingResponse> myBookings(UUID customerId) {
        return bookings.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(b -> BookingResponse.from(b,
                        items.findByBookingId(b.getId())
                             .map(BookingItem::getModel).orElse("")))
                .toList();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void validateDates(LocalDate start, LocalDate end) {
        // Checked here, before the database, so the customer gets a readable
        // message rather than PostgreSQL's "range lower bound must be less than
        // or equal to range upper bound".
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE",
                    "Return date must be after the pickup date.", "endDate");
        }
        if (start.isBefore(LocalDate.now(ZONE))) {
            throw ApiException.badRequest("INVALID_DATE_RANGE",
                    "Pickup date cannot be in the past.", "startDate");
        }
        if (rentalDays(start, end) > MAX_RENTAL_DAYS) {
            throw ApiException.badRequest("RENTAL_TOO_LONG",
                    "Rentals are limited to " + MAX_RENTAL_DAYS + " days.", "endDate");
        }
    }

    private Shop verifiedShop(UUID shopId) {
        Shop shop = shops.findById(shopId).orElseThrow(() ->
                ApiException.notFound("SHOP_NOT_FOUND", "Shop not found."));
        if (shop.getStatus() != ShopStatus.VERIFIED) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "SHOP_NOT_VERIFIED", "This shop cannot accept bookings yet.");
        }
        return shop;
    }

    private static int rentalDays(LocalDate start, LocalDate end) {
        return (int) ChronoUnit.DAYS.between(start, end);
    }

    private BigDecimal deliveryFeeFor(Shop shop, PickupMethod method) {
        if (method != PickupMethod.DELIVERY) return BigDecimal.ZERO;
        if (!shop.isOffersDelivery()) {
            throw ApiException.badRequest("DELIVERY_NOT_OFFERED",
                    "This shop does not deliver.", "pickupMethod");
        }
        return shop.getDeliveryFee() == null ? BigDecimal.ZERO : shop.getDeliveryFee();
    }

    /**
     * 30 minutes for a same-day pickup, 2 hours otherwise.
     *
     * Short for same-day because the customer is probably standing nearby and
     * the shop needs to answer now; longer for a future booking because there
     * is no urgency and a shop owner may be asleep.
     */
    private Instant holdExpiryFor(LocalDate startDate) {
        boolean sameDay = startDate.equals(LocalDate.now(ZONE));
        return sameDay
                ? Instant.now().plus(sameDayHoldMinutes, ChronoUnit.MINUTES)
                : Instant.now().plus(futureHoldHours, ChronoUnit.HOURS);
    }
}
