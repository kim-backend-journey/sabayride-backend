package com.sabayride.rental.discovery;

import com.sabayride.rental.booking.BookingRepository;
import com.sabayride.rental.booking.BookingStatus;
import com.sabayride.rental.common.ApiException;
import com.sabayride.rental.common.PageEnvelope;
import com.sabayride.rental.discovery.dto.*;
import com.sabayride.rental.fleet.Motorbike;
import com.sabayride.rental.fleet.MotorbikeRepository;
import com.sabayride.rental.fleet.MotorbikeType;
import com.sabayride.rental.shop.Shop;
import com.sabayride.rental.shop.ShopRepository;
import com.sabayride.rental.shop.ShopStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class SearchService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Phnom_Penh");
    private static final int MAX_RANGE_DAYS = 30;

    private final ShopRepository shops;
    private final MotorbikeRepository motorbikes;
    private final BookingRepository bookings;

    public SearchService(ShopRepository shops, MotorbikeRepository motorbikes,
                         BookingRepository bookings) {
        this.shops = shops;
        this.motorbikes = motorbikes;
        this.bookings = bookings;
    }

    public enum Sort { PRICE_ASC, PRICE_DESC, RATING_DESC, RELEVANCE }

    /**
     * THE CORE READ. One call fills the search results screen.
     *
     * The shape of the answer is the whole design:
     *
     *   1. Only VERIFIED shops are searchable. An unverified shop is invisible.
     *   2. Read every ACTIVE unit of those shops.
     *   3. Ask the booking table which of those units are busy in this range,
     *      using the same overlap condition the booking transaction uses.
     *   4. Group the free ones by (shopId, model) and count them.
     *   5. Drop any group with zero free units — a model with nothing available
     *      is simply absent from the results, never present with a zero.
     *
     * Nothing is read from an `available` column, because there isn't one. The
     * answer depends entirely on which dates were asked about, which is why the
     * date range is REQUIRED rather than optional. A results screen that shows
     * bikes without saying when they are free is the problem this platform
     * exists to solve.
     */
    @Transactional(readOnly = true)
    public PageEnvelope<ModelSearchResult> search(
            LocalDate startDate, LocalDate endDate,
            List<MotorbikeType> types,
            BigDecimal minPrice, BigDecimal maxPrice,
            Boolean offersDelivery, UUID shopId,
            Sort sort, int page, int size) {

        validateRange(startDate, endDate);

        // 1. verified shops only
        List<Shop> verified = shops.findByStatus(ShopStatus.VERIFIED).stream()
                .filter(s -> shopId == null || s.getId().equals(shopId))
                .filter(s -> offersDelivery == null || s.isOffersDelivery() == offersDelivery)
                .toList();
        if (verified.isEmpty()) return PageEnvelope.of(List.of(), page, size);

        Map<UUID, Shop> shopById = new HashMap<>();
        verified.forEach(s -> shopById.put(s.getId(), s));

        // 2. their bookable units
        List<Motorbike> units = motorbikes.findBookableInShops(shopById.keySet());
        if (units.isEmpty()) return PageEnvelope.of(List.of(), page, size);

        // 3. which are busy in this range
        Set<UUID> busy = new HashSet<>(bookings.findBusyUnitIds(
                units.stream().map(Motorbike::getId).toList(),
                BookingStatus.BLOCKING, startDate, endDate));

        // 4. group the free ones by (shop, model)
        Map<String, List<Motorbike>> grouped = new LinkedHashMap<>();
        for (Motorbike m : units) {
            if (busy.contains(m.getId())) continue;
            if (types != null && !types.isEmpty() && !types.contains(m.getType())) continue;
            if (minPrice != null && m.getPricePerDay().compareTo(minPrice) < 0) continue;
            if (maxPrice != null && m.getPricePerDay().compareTo(maxPrice) > 0) continue;
            grouped.computeIfAbsent(m.getShopId() + "|" + m.getModel(),
                                    k -> new ArrayList<>()).add(m);
        }

        // 5. one row per group; groups with nothing free never got created
        List<ModelSearchResult> results = new ArrayList<>();
        for (List<Motorbike> group : grouped.values()) {
            Motorbike sample = group.get(0);
            Shop shop = shopById.get(sample.getShopId());
            results.add(new ModelSearchResult(
                    shop.getId(), shop.getName(),
                    shop.getRating() == null ? null : shop.getRating().doubleValue(),
                    shop.getReviewCount(), true,
                    sample.getModel(), sample.getBrand(), sample.getType(),
                    sample.getPricePerDay(), sample.getCurrency(),
                    group.size(),
                    shop.isOffersDelivery(), shop.getDeliveryFee()));
        }

        results.sort(comparatorFor(sort));
        return PageEnvelope.of(results, page, size);
    }

    /**
     * Model detail for one shop, priced for the chosen dates.
     *
     * Addressed by (shopId, model) rather than a unit id, because the customer
     * is looking at a model a shop offers — not at one physical bike. A URL
     * containing a unit id would break the moment that unit went into
     * maintenance.
     */
    @Transactional(readOnly = true)
    public ModelDetail modelDetail(UUID shopId, String model,
                                   LocalDate startDate, LocalDate endDate) {
        validateRange(startDate, endDate);

        Shop shop = shops.findById(shopId)
                .filter(s -> s.getStatus() == ShopStatus.VERIFIED)
                .orElseThrow(() -> ApiException.notFound("SHOP_NOT_FOUND", "Shop not found."));

        List<Motorbike> units = motorbikes
                .findByShopIdAndStatus(shopId, com.sabayride.rental.fleet.MotorbikeStatus.ACTIVE)
                .stream().filter(m -> m.getModel().equals(model)).toList();

        if (units.isEmpty()) {
            throw ApiException.notFound("MODEL_NOT_FOUND",
                    "That shop does not offer this model.");
        }

        Set<UUID> busy = new HashSet<>(bookings.findBusyUnitIds(
                units.stream().map(Motorbike::getId).toList(),
                BookingStatus.BLOCKING, startDate, endDate));

        int free = (int) units.stream().filter(u -> !busy.contains(u.getId())).count();

        Motorbike sample = units.get(0);
        int days = (int) ChronoUnit.DAYS.between(startDate, endDate);
        BigDecimal subtotal = sample.getPricePerDay().multiply(BigDecimal.valueOf(days));
        BigDecimal deliveryFee = shop.isOffersDelivery() && shop.getDeliveryFee() != null
                ? shop.getDeliveryFee() : new BigDecimal("0.00");

        return new ModelDetail(
                shop.getId(), sample.getModel(), sample.getBrand(), sample.getType(),
                sample.getPricePerDay(), sample.getCurrency(), free,
                startDate, endDate, days,
                subtotal, deliveryFee, subtotal.add(deliveryFee),
                List.of(), ShopSummary.from(shop));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void validateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw ApiException.badRequest("DATE_RANGE_REQUIRED",
                    "Please choose pickup and return dates.", "startDate");
        }
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE",
                    "Return date must be after the pickup date.", "endDate");
        }
        if (start.isBefore(LocalDate.now(ZONE))) {
            throw ApiException.badRequest("INVALID_DATE_RANGE",
                    "Pickup date cannot be in the past.", "startDate");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("RENTAL_TOO_LONG",
                    "Rentals are limited to " + MAX_RANGE_DAYS + " days.", "endDate");
        }
    }

    private Comparator<ModelSearchResult> comparatorFor(Sort sort) {
        return switch (sort == null ? Sort.RELEVANCE : sort) {
            case PRICE_ASC  -> Comparator.comparing(ModelSearchResult::pricePerDay);
            case PRICE_DESC -> Comparator.comparing(ModelSearchResult::pricePerDay).reversed();
            case RATING_DESC -> Comparator.comparing(
                    (ModelSearchResult r) -> r.shopRating() == null ? 0d : r.shopRating())
                    .reversed();
            // RELEVANCE for V1: rating first, then price. A real relevance score
            // needs booking data we do not have yet — honest placeholder rather
            // than arbitrary ordering dressed up as ranking.
            case RELEVANCE -> Comparator
                    .comparing((ModelSearchResult r) -> r.shopRating() == null ? 0d : r.shopRating())
                    .reversed()
                    .thenComparing(ModelSearchResult::pricePerDay);
        };
    }
}
