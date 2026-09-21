package com.sabayride.rental.booking;

import com.sabayride.rental.booking.dto.BookingRequest;
import com.sabayride.rental.common.ApiException;
import com.sabayride.rental.fleet.*;
import com.sabayride.rental.shop.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE TEST THIS PROJECT EXISTS TO PASS.
 *
 * One bike. Twenty customers pressing Confirm at the same instant.
 * Exactly one must win. Nobody must see a 500.
 *
 * Runs against REAL PostgreSQL via Testcontainers, never H2. H2 does not
 * implement SELECT ... FOR UPDATE the same way and has no EXCLUDE constraint at
 * all — a green test on H2 would be worse than no test, because it would tell
 * you the thing works when it does not.
 *
 * The test class is deliberately NOT @Transactional. A test-managed transaction
 * would wrap every thread's work in one outer transaction and the race would
 * never happen — the commonest way this kind of test quietly tests nothing.
 */
@SpringBootTest
@Testcontainers
class BookingConcurrencyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("sabayride")
                    .withUsername("test")
                    .withPassword("test")
                    // btree_gist and the rental schema must exist before Flyway
                    // runs, exactly as infra/db/init-schemas.sql does in dev.
                    .withInitScript("test-init.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",
              () -> POSTGRES.getJdbcUrl() + "?currentSchema=rental");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired BookingService bookingService;
    @Autowired ShopRepository shops;
    @Autowired MotorbikeRepository motorbikes;
    @Autowired BookingRepository bookings;

    private UUID shopId;

    @BeforeEach
    void seed() {
        bookings.deleteAll();
        motorbikes.deleteAll();
        shops.deleteAll();

        Shop shop = new ShopBuilder()
                .owner(UUID.randomUUID())
                .name("Angkor Moto Rent")
                .build();
        shops.save(shop);
        shopId = shop.getId();
    }

    private void addUnits(int count) {
        for (int i = 0; i < count; i++) {
            motorbikes.save(Motorbike.of(shopId, "Honda", "Honda Dream 125",
                    MotorbikeType.SCOOTER, "1AB-" + (1000 + i),
                    new BigDecimal("8.00")));
        }
    }

    private BookingRequest request() {
        LocalDate start = LocalDate.now().plusDays(7);
        return new BookingRequest(shopId, "Honda Dream 125",
                start, start.plusDays(2),
                PickupMethod.SHOP_PICKUP, null, PaymentOption.PAY_AT_SHOP, null);
    }

    /** Fires `threads` bookings simultaneously and reports how each one ended. */
    private Result raceFor(int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    // Every thread blocks here, so they all hit the lock in the
                    // same millisecond. Without this they would queue up
                    // naturally and never actually race.
                    startGun.await();
                    bookingService.create(request(), UUID.randomUUID(), "Customer");
                    created.incrementAndGet();
                } catch (ApiException e) {
                    if ("NO_UNITS_AVAILABLE".equals(e.getCode())) conflicts.incrementAndGet();
                    else unexpected.add(e);
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    finished.countDown();
                }
            });
        }

        startGun.countDown();
        assertTrue(finished.await(60, TimeUnit.SECONDS), "threads did not finish");
        pool.shutdown();

        return new Result(created.get(), conflicts.get(), unexpected);
    }

    record Result(int created, int conflicts, List<Throwable> unexpected) { }

    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("20 simultaneous bookings, 1 bike -> exactly 1 succeeds")
    void onlyOneWins() throws Exception {
        addUnits(1);

        Result r = raceFor(20);

        r.unexpected().forEach(Throwable::printStackTrace);
        assertTrue(r.unexpected().isEmpty(),
                "no request may fail with anything other than a clean conflict");
        assertEquals(1, r.created(), "exactly one booking must be created");
        assertEquals(19, r.conflicts(), "the other 19 must get NO_UNITS_AVAILABLE");
        assertEquals(1, bookings.count(), "the database must hold exactly one booking");
    }

    @Test
    @DisplayName("20 simultaneous bookings, 3 bikes -> exactly 3 succeed")
    void threeBikesThreeWinners() throws Exception {
        addUnits(3);

        Result r = raceFor(20);

        assertTrue(r.unexpected().isEmpty(), "unexpected failures");
        assertEquals(3, r.created());
        assertEquals(17, r.conflicts());
        assertEquals(3, bookings.count());
    }

    @Test
    @DisplayName("back-to-back rentals are allowed (end date is exclusive)")
    void adjacentRangesDoNotOverlap() {
        addUnits(1);
        LocalDate start = LocalDate.now().plusDays(7);

        bookingService.create(new BookingRequest(shopId, "Honda Dream 125",
                start, start.plusDays(2), PickupMethod.SHOP_PICKUP, null,
                PaymentOption.PAY_AT_SHOP, null), UUID.randomUUID(), "A");

        // starts the day the first one ends — must be accepted
        assertDoesNotThrow(() -> bookingService.create(new BookingRequest(
                shopId, "Honda Dream 125",
                start.plusDays(2), start.plusDays(4), PickupMethod.SHOP_PICKUP, null,
                PaymentOption.PAY_AT_SHOP, null), UUID.randomUUID(), "B"));

        assertEquals(2, bookings.count());
    }

    /** Small helper so the test does not depend on Shop's setters staying stable. */
    static class ShopBuilder {
        private final Shop shop = newShop();
        private static Shop newShop() {
            try {
                var c = Shop.class.getDeclaredConstructor();
                c.setAccessible(true);
                return c.newInstance();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        ShopBuilder owner(UUID id) { shop.setOwnerId(id); return this; }
        ShopBuilder name(String n) { shop.setName(n); return this; }
        Shop build() {
            shop.setPhone("+85512345678");
            shop.setAddress("Wat Bo Road, Siem Reap");
            shop.setLatitude(new BigDecimal("13.361000"));
            shop.setLongitude(new BigDecimal("103.860000"));
            shop.setStatus(ShopStatus.VERIFIED);
            return shop;
        }
    }
}
