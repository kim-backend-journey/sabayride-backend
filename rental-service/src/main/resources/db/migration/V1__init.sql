-- rental-service/src/main/resources/db/migration/V1__init.sql
--
-- Shops, fleet, bookings, payments, reviews. The core service.
--
-- THE RULE THIS SCHEMA EXISTS TO ENFORCE:
--   There is no `available` column anywhere. Availability is DERIVED from the
--   booking table for a requested date range. motorbike.status has no BOOKED
--   and no RENTED value — whether a bike is out today is a question about
--   bookings, not a column a human maintains.
--
-- Cross-service note: user IDs here are plain uuids with NO foreign key.
-- identity.users lives in another schema this user cannot read. Customer name
-- and phone are SNAPSHOTTED onto booking at creation, which is why a booking
-- still renders when identity-service is down.

SET search_path = rental, public;

CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS trigger AS $$
BEGIN NEW.updated_at := now(); RETURN NEW; END;
$$ LANGUAGE plpgsql;


-- ═══════════════════════════════════════════════════════════════════════
-- SHOP
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE shop (
    id                   uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id             uuid          NOT NULL,          -- identity.users.id, no FK
    name                 varchar(150)  NOT NULL,
    description          text,
    phone                varchar(20)   NOT NULL,
    email                varchar(255),
    address              text          NOT NULL,
    latitude             numeric(9,6)  NOT NULL,
    longitude            numeric(9,6)  NOT NULL,
    status               varchar(30)   NOT NULL DEFAULT 'PENDING_VERIFICATION',
    verified_at          timestamptz,
    verification_note    text,
    suspension_reason    text,
    offers_delivery      boolean       NOT NULL DEFAULT false,
    delivery_fee         numeric(10,2),
    accepts_pay_at_shop  boolean       NOT NULL DEFAULT true,
    cancellation_policy  text,
    -- Denormalised from review. Recomputed on review insert/delete. Justified:
    -- read on every single search result, written rarely.
    rating               numeric(2,1),
    review_count         integer       NOT NULL DEFAULT 0,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_shop_status CHECK (
        status IN ('PENDING_VERIFICATION','VERIFIED','REJECTED','SUSPENDED')),
    CONSTRAINT ck_shop_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
    -- A shop that delivers must say what it charges. Unrepresentable beats
    -- validated-somewhere-in-a-service.
    CONSTRAINT ck_shop_delivery CHECK (offers_delivery = false OR delivery_fee IS NOT NULL)
);
CREATE INDEX ix_shop_owner  ON shop (owner_id);
CREATE INDEX ix_shop_status ON shop (status);
CREATE INDEX ix_shop_geo    ON shop (latitude, longitude);
CREATE TRIGGER tr_shop_updated BEFORE UPDATE ON shop
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();


CREATE TABLE shop_opening_hours (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id      uuid        NOT NULL REFERENCES shop(id) ON DELETE CASCADE,
    day_of_week  smallint    NOT NULL,         -- 1 = Monday .. 7 = Sunday
    opens_at     time,
    closes_at    time,
    closed       boolean     NOT NULL DEFAULT false,

    CONSTRAINT ck_hours_dow CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT uq_hours UNIQUE (shop_id, day_of_week)
);

CREATE TABLE shop_rental_requirement (
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id      uuid         NOT NULL REFERENCES shop(id) ON DELETE CASCADE,
    requirement  varchar(300) NOT NULL,
    sort_order   smallint     NOT NULL DEFAULT 0
);
CREATE INDEX ix_requirement_shop ON shop_rental_requirement (shop_id);

CREATE TABLE shop_photo (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id        uuid         NOT NULL REFERENCES shop(id) ON DELETE CASCADE,
    url            varchar(500) NOT NULL,
    thumbnail_url  varchar(500),
    sort_order     smallint     NOT NULL DEFAULT 0,
    uploaded_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_shop_photo ON shop_photo (shop_id);


CREATE TABLE shop_member (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id     uuid        NOT NULL REFERENCES shop(id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL,          -- identity.users.id, no FK
    role        varchar(20) NOT NULL,
    status      varchar(20) NOT NULL DEFAULT 'INVITED',
    invited_at  timestamptz NOT NULL DEFAULT now(),
    joined_at   timestamptz,

    CONSTRAINT ck_member_role   CHECK (role IN ('SHOP_OWNER','SHOP_STAFF')),
    CONSTRAINT ck_member_status CHECK (status IN ('INVITED','ACTIVE','DISABLED')),
    CONSTRAINT uq_shop_member   UNIQUE (shop_id, user_id)
);
-- Resolves shop_id from the JWT on every /shops/me/** call. Hot path.
CREATE INDEX ix_member_user ON shop_member (user_id) WHERE status = 'ACTIVE';

COMMENT ON TABLE shop_member IS
  'Staff may confirm, reject, hand over, return. Staff may NOT change the payout account or manage staff — the two actions that move money or grant access stay with the owner.';


CREATE TABLE payout_account (
    id                     uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id                uuid         NOT NULL UNIQUE REFERENCES shop(id),
    bank                   varchar(20)  NOT NULL,
    account_holder_name    varchar(150) NOT NULL,
    last4                  char(4)      NOT NULL,
    beneficiary_reference  varchar(255) NOT NULL,
    verified               boolean      NOT NULL DEFAULT false,
    registered_at          timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_payout_bank  CHECK (bank IN ('ABA','ACLEDA','WING','OTHER')),
    CONSTRAINT ck_payout_last4 CHECK (last4 ~ '^[0-9]{4}$')
);

COMMENT ON TABLE payout_account IS
  'THE FULL ACCOUNT NUMBER IS NOT STORED. Sent to PayWay once for beneficiary registration, then discarded; only the returned reference persists. If this DB leaks, an attacker gets a token that works only with our own PayWay account.';


-- ═══════════════════════════════════════════════════════════════════════
-- FLEET
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE motorbike (
    id             uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id        uuid          NOT NULL REFERENCES shop(id),
    brand          varchar(60)   NOT NULL,
    -- Units sharing this string group into ONE search result, so spelling
    -- matters. Trim and collapse whitespace on write; the shop UI should
    -- suggest previously used values.
    model          varchar(120)  NOT NULL,
    type           varchar(20)   NOT NULL,
    transmission   varchar(20),
    engine_cc      smallint,
    year           smallint,
    color          varchar(40),
    plate_number   varchar(20)   NOT NULL,
    -- NUMERIC, never FLOAT. Affects FUTURE bookings only; past bookings keep
    -- the snapshot on booking_item.
    price_per_day  numeric(10,2) NOT NULL,
    currency       char(3)       NOT NULL DEFAULT 'USD',
    -- ACTIVE | MAINTENANCE | INACTIVE.  NO 'BOOKED'.  NO 'RENTED'.
    -- This CHECK is the enforcement of the project's central design rule.
    status         varchar(20)   NOT NULL DEFAULT 'ACTIVE',
    description    text,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_bike_type CHECK (
        type IN ('SCOOTER','SEMI_AUTOMATIC','MANUAL','DIRT_BIKE','ELECTRIC')),
    CONSTRAINT ck_bike_transmission CHECK (
        transmission IS NULL OR transmission IN ('AUTOMATIC','MANUAL','SEMI_AUTOMATIC')),
    CONSTRAINT ck_bike_status CHECK (status IN ('ACTIVE','MAINTENANCE','INACTIVE')),
    CONSTRAINT ck_bike_price  CHECK (price_per_day > 0),
    CONSTRAINT uq_bike_plate  UNIQUE (shop_id, plate_number)
);
-- THE search index — results group by (shop_id, model).
CREATE INDEX ix_bike_shop_model  ON motorbike (shop_id, model) WHERE status = 'ACTIVE';
CREATE INDEX ix_bike_shop_status ON motorbike (shop_id, status);
CREATE TRIGGER tr_bike_updated BEFORE UPDATE ON motorbike
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

COMMENT ON TABLE motorbike IS
  'ONE ROW PER PHYSICAL BIKE. Five Honda Dreams are five rows with five plates. These are the rows the booking transaction takes SELECT ... FOR UPDATE on — you cannot lock a model, only rows, which is why a concrete unit is assigned at booking creation rather than at pickup.';


CREATE TABLE motorbike_photo (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    motorbike_id   uuid         NOT NULL REFERENCES motorbike(id) ON DELETE CASCADE,
    url            varchar(500) NOT NULL,
    thumbnail_url  varchar(500),
    is_primary     boolean      NOT NULL DEFAULT false,
    sort_order     smallint     NOT NULL DEFAULT 0,
    uploaded_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_bike_photo ON motorbike_photo (motorbike_id);
-- At most one primary photo per bike.
CREATE UNIQUE INDEX uq_bike_primary_photo
    ON motorbike_photo (motorbike_id) WHERE is_primary;

CREATE TABLE motorbike_feature (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    motorbike_id  uuid         NOT NULL REFERENCES motorbike(id) ON DELETE CASCADE,
    feature       varchar(100) NOT NULL
);
CREATE INDEX ix_bike_feature ON motorbike_feature (motorbike_id);


-- ═══════════════════════════════════════════════════════════════════════
-- BOOKING — the heart of the system
-- ═══════════════════════════════════════════════════════════════════════

CREATE SEQUENCE booking_reference_seq START 1;

CREATE TABLE booking (
    id                     uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    -- SR-000128, read aloud at the shop counter.
    reference              varchar(20)   NOT NULL UNIQUE
                             DEFAULT 'SR-' || lpad(nextval('booking_reference_seq')::text, 6, '0'),

    -- identity.users.id — no FK, different schema, different service.
    customer_id            uuid          NOT NULL,
    -- SNAPSHOT of the customer, so rendering a booking never calls
    -- identity-service. Same principle as the price snapshot, applied to the
    -- service boundary.
    customer_name          varchar(120)  NOT NULL,
    customer_phone         varchar(20),

    shop_id                uuid          NOT NULL REFERENCES shop(id),
    status                 varchar(20)   NOT NULL DEFAULT 'PENDING',

    start_date             date          NOT NULL,   -- INCLUSIVE
    end_date               date          NOT NULL,   -- EXCLUSIVE: 05->07 is 2 days

    -- Lives here, not only on booking_item, so the EXCLUDE constraint below can
    -- see it alongside status and period. An exclusion constraint works within
    -- one table only.
    assigned_motorbike_id  uuid          NOT NULL REFERENCES motorbike(id),

    -- Derived, so it can never drift from the two date columns.
    period                 daterange     GENERATED ALWAYS AS
                             (daterange(start_date, end_date, '[)')) STORED,

    pickup_method          varchar(20)   NOT NULL,
    delivery_address       text,
    delivery_latitude      numeric(9,6),
    delivery_longitude     numeric(9,6),

    payment_option         varchar(20)   NOT NULL,
    payment_status         varchar(30)   NOT NULL DEFAULT 'UNPAID',
    payout_status          varchar(20)   NOT NULL DEFAULT 'NOT_DUE',

    subtotal               numeric(10,2) NOT NULL,
    delivery_fee           numeric(10,2) NOT NULL DEFAULT 0,
    total                  numeric(10,2) NOT NULL,
    currency               char(3)       NOT NULL DEFAULT 'USD',

    -- SNAPSHOT, e.g. 0.1000. Raising the platform rate later must not rewrite
    -- past bookings.
    commission_rate        numeric(5,4)  NOT NULL,
    -- 10% x subtotal. The delivery fee is NOT commissionable — it reimburses
    -- the shop for petrol and labour.
    platform_fee           numeric(10,2) NOT NULL,
    shop_payout            numeric(10,2) NOT NULL,

    late_fee               numeric(10,2) NOT NULL DEFAULT 0,
    damage_fee             numeric(10,2) NOT NULL DEFAULT 0,
    refund_amount          numeric(10,2) NOT NULL DEFAULT 0,

    -- 30 min same-day pickup, 2 h otherwise. A scheduled job moves expired
    -- holds to EXPIRED and frees the unit — no payment, no shop action.
    hold_expires_at        timestamptz,
    confirmed_at           timestamptz,
    activated_at           timestamptz,
    completed_at           timestamptz,

    cancellation_reason    text,
    rejection_reason       varchar(30),
    rejection_note         text,
    customer_note          varchar(500),

    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_booking_status CHECK (status IN
        ('PENDING','CONFIRMED','ACTIVE','COMPLETED','REJECTED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_booking_pickup CHECK (pickup_method IN ('SHOP_PICKUP','DELIVERY')),
    CONSTRAINT ck_booking_payopt CHECK (payment_option IN ('PAY_NOW','PAY_AT_SHOP')),
    CONSTRAINT ck_booking_paystatus CHECK (payment_status IN
        ('UNPAID','PENDING','PAID','FAILED','REFUNDED','PARTIALLY_REFUNDED','AMOUNT_MISMATCH')),
    CONSTRAINT ck_booking_payoutstatus CHECK (payout_status IN
        ('NOT_DUE','PENDING','SENT','FAILED')),
    CONSTRAINT ck_booking_reject CHECK (rejection_reason IS NULL OR rejection_reason IN
        ('BIKE_UNAVAILABLE','SHOP_CLOSED','CUSTOMER_UNREACHABLE','OTHER')),

    -- Minimum rental is one day; a zero-length range would break the overlap
    -- test and make the EXCLUDE constraint meaningless.
    CONSTRAINT ck_booking_dates   CHECK (end_date > start_date),
    CONSTRAINT ck_booking_amounts CHECK (
        subtotal >= 0 AND delivery_fee >= 0 AND total >= 0
        AND platform_fee >= 0 AND shop_payout >= 0
        AND late_fee >= 0 AND damage_fee >= 0 AND refund_amount >= 0),
    CONSTRAINT ck_booking_math    CHECK (total = subtotal + delivery_fee),
    CONSTRAINT ck_booking_payout  CHECK (shop_payout = total - platform_fee),
    -- Delivery with no address is unrepresentable, not merely rejected.
    CONSTRAINT ck_booking_delivery CHECK (
        pickup_method <> 'DELIVERY' OR delivery_address IS NOT NULL)
);

-- ─── THE CONSTRAINT THAT MAKES DOUBLE-BOOKING IMPOSSIBLE ────────────────
-- PostgreSQL now REFUSES, at the storage layer, to hold two non-terminal
-- bookings for the same unit whose date ranges overlap. Not unlikely —
-- impossible. Violations raise SQLSTATE 23P01, which the service maps to
-- 409 NO_UNITS_AVAILABLE.
--
-- This does NOT replace the pessimistic lock. The lock lets the code CHOOSE a
-- free unit and return a clean error. The constraint guarantees correctness if
-- the lock is ever wrong, removed in a refactor, or bypassed by a script, a
-- migration, or a second instance of the service.
--
-- MySQL has no equivalent. This is the concrete answer to "why PostgreSQL".
ALTER TABLE booking
    ADD CONSTRAINT no_double_booking
    EXCLUDE USING gist (assigned_motorbike_id WITH =, period WITH &&)
    WHERE (status IN ('PENDING','CONFIRMED','ACTIVE'));

-- Only rows that can block availability live in this index. Terminal bookings
-- accumulate forever but never affect availability, so keeping them out keeps
-- the index permanently small.
CREATE INDEX ix_booking_availability ON booking (assigned_motorbike_id, start_date, end_date)
    WHERE status IN ('PENDING','CONFIRMED','ACTIVE');

CREATE INDEX ix_booking_customer   ON booking (customer_id, status);
CREATE INDEX ix_booking_shop       ON booking (shop_id, status);
CREATE INDEX ix_booking_pickups    ON booking (shop_id, start_date);
CREATE INDEX ix_booking_returns    ON booking (shop_id, end_date);
-- The hold-expiry sweep job.
CREATE INDEX ix_booking_hold_sweep ON booking (hold_expires_at)
    WHERE status = 'PENDING';

CREATE TRIGGER tr_booking_updated BEFORE UPDATE ON booking
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();


CREATE TABLE booking_item (
    id                     uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id             uuid          NOT NULL UNIQUE
                                           REFERENCES booking(id) ON DELETE CASCADE,
    -- ── THE SNAPSHOT ──
    brand                  varchar(60)   NOT NULL,
    model                  varchar(120)  NOT NULL,
    price_per_day          numeric(10,2) NOT NULL,
    rental_days            smallint      NOT NULL,
    subtotal               numeric(10,2) NOT NULL,
    delivery_fee           numeric(10,2) NOT NULL DEFAULT 0,

    -- Copied so the row survives the unit being retired. NEVER returned to a
    -- customer — only to the shop and admin.
    assigned_plate_number  varchar(20)   NOT NULL,
    -- Set at handover when a different bike was physically given out.
    actual_motorbike_id    uuid          REFERENCES motorbike(id),
    actual_plate_number    varchar(20),

    CONSTRAINT ck_item_days  CHECK (rental_days >= 1),
    CONSTRAINT ck_item_price CHECK (price_per_day > 0)
);
CREATE INDEX ix_item_actual ON booking_item (actual_motorbike_id);

COMMENT ON TABLE booking_item IS
  'THE SNAPSHOT. brand, model, price_per_day and delivery_fee are COPIED at creation and never updated. When a shop raises its price next month, this booking''s history stays true — without the copy, every past receipt silently rewrites itself and the revenue report stops matching the bank.';


CREATE TABLE booking_extension (
    id                 uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id         uuid          NOT NULL REFERENCES booking(id) ON DELETE CASCADE,
    previous_end_date  date          NOT NULL,
    new_end_date       date          NOT NULL,
    extra_days         smallint      NOT NULL,
    -- The ORIGINAL snapshot price. A customer extending mid-rental is not
    -- repriced because the shop raised its rate this morning.
    price_per_day      numeric(10,2) NOT NULL,
    extra_amount       numeric(10,2) NOT NULL,
    platform_fee       numeric(10,2) NOT NULL,
    shop_payout        numeric(10,2) NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_ext_dates CHECK (new_end_date > previous_end_date),
    CONSTRAINT ck_ext_days  CHECK (extra_days >= 1)
);
CREATE INDEX ix_ext_booking ON booking_extension (booking_id);

COMMENT ON TABLE booking_extension IS
  'A SEPARATE payment and payout, not an edit of the original. The first payment has settled and its payout may already have reached the shop; rewriting the original total would leave money that moved no longer matching the booking that justified it.';


CREATE TABLE condition_record (
    id                uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id        uuid          NOT NULL REFERENCES booking(id) ON DELETE CASCADE,
    kind              varchar(10)   NOT NULL,
    recorded_by       uuid          NOT NULL,      -- identity.users.id, no FK
    recorded_by_name  varchar(120)  NOT NULL,      -- snapshot
    odometer_reading  integer,
    fuel_level        varchar(20),
    note              text,
    cash_collected    numeric(10,2),               -- HANDOVER + PAY_AT_SHOP only
    recorded_at       timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_cond_kind CHECK (kind IN ('HANDOVER','RETURN')),
    CONSTRAINT ck_cond_fuel CHECK (fuel_level IS NULL OR fuel_level IN
        ('EMPTY','QUARTER','HALF','THREE_QUARTER','FULL')),
    CONSTRAINT ck_cond_odo  CHECK (odometer_reading IS NULL OR odometer_reading >= 0),
    -- One handover and one return per booking.
    CONSTRAINT uq_cond UNIQUE (booking_id, kind)
);

COMMENT ON TABLE condition_record IS
  'The shop''s only evidence in a damage dispute.';


CREATE TABLE condition_photo (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    condition_record_id  uuid         NOT NULL
                           REFERENCES condition_record(id) ON DELETE CASCADE,
    url                  varchar(500) NOT NULL,
    uploaded_at          timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_cond_photo ON condition_photo (condition_record_id);

COMMENT ON TABLE condition_photo IS
  'S3, not the container filesystem — a redeploy would delete the evidence.';


CREATE TABLE idempotency_key (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    key              uuid         NOT NULL UNIQUE,
    user_id          uuid         NOT NULL,
    endpoint         varchar(120) NOT NULL,
    booking_id       uuid         REFERENCES booking(id) ON DELETE SET NULL,
    response_status  smallint,
    created_at       timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE idempotency_key IS
  'Cambodian mobile data drops responses; the customer taps Confirm again. Without this you create two bookings and lock two bikes. The UNIQUE on key is what makes the guarantee real rather than a race in application code.';


-- ═══════════════════════════════════════════════════════════════════════
-- MONEY
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE payment (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id      uuid          NOT NULL REFERENCES booking(id),
    extension_id    uuid          REFERENCES booking_extension(id) ON DELETE SET NULL,
    kind            varchar(20)   NOT NULL DEFAULT 'BOOKING',
    amount          numeric(10,2) NOT NULL,
    currency        char(3)       NOT NULL DEFAULT 'USD',
    method          varchar(20)   NOT NULL,
    status          varchar(30)   NOT NULL DEFAULT 'PENDING',
    -- UNIQUE: the idempotency anchor for the provider callback, which retries.
    transaction_id  varchar(120)  UNIQUE,
    qr_payload      text,
    expires_at      timestamptz,
    paid_at         timestamptz,
    failure_reason  text,
    created_at      timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_pay_kind   CHECK (kind IN ('BOOKING','EXTENSION')),
    CONSTRAINT ck_pay_method CHECK (method IN ('KHQR','ABA_PAY','CASH')),
    CONSTRAINT ck_pay_status CHECK (status IN
        ('UNPAID','PENDING','PAID','FAILED','REFUNDED','PARTIALLY_REFUNDED','AMOUNT_MISMATCH')),
    CONSTRAINT ck_pay_amount CHECK (amount > 0)
);
CREATE INDEX ix_pay_booking ON payment (booking_id);
CREATE INDEX ix_pay_status  ON payment (status);

COMMENT ON TABLE payment IS
  'PAID only when the provider''s SIGNED callback arrives. The client never reports success — a client that says "user paid" is trivially forged. If the provider''s amount does not match the booking total, status becomes AMOUNT_MISMATCH and the booking stays unconfirmed for admin review.';


CREATE TABLE payment_callback_log (
    id               bigserial    PRIMARY KEY,
    transaction_id   varchar(120) NOT NULL,
    signature_valid  boolean      NOT NULL,
    raw_payload      jsonb        NOT NULL,
    outcome          varchar(60)  NOT NULL,
    received_at      timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_cb_outcome CHECK (outcome IN
        ('APPLIED','DUPLICATE_IGNORED','SIGNATURE_REJECTED','AMOUNT_MISMATCH','UNKNOWN_TXN'))
);
CREATE INDEX ix_cb_txn ON payment_callback_log (transaction_id);

COMMENT ON TABLE payment_callback_log IS
  'Every callback logged BEFORE it is acted on, valid or not. Two jobs: the duplicate check that makes the handler idempotent, and the evidence trail when a payment is disputed.';


CREATE TABLE payout (
    id                  uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id          uuid          NOT NULL REFERENCES booking(id),
    extension_id        uuid          REFERENCES booking_extension(id) ON DELETE SET NULL,
    payout_account_id   uuid          NOT NULL REFERENCES payout_account(id),
    gross_amount        numeric(10,2) NOT NULL,
    platform_fee        numeric(10,2) NOT NULL,
    amount              numeric(10,2) NOT NULL,
    currency            char(3)       NOT NULL DEFAULT 'USD',
    status              varchar(20)   NOT NULL DEFAULT 'PENDING',
    attempt_count       smallint      NOT NULL DEFAULT 0,
    failure_reason      text,
    provider_reference  varchar(120),
    sent_at             timestamptz,
    created_at          timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_payout_status CHECK (status IN ('NOT_DUE','PENDING','SENT','FAILED')),
    CONSTRAINT ck_payout_math   CHECK (amount = gross_amount - platform_fee)
);
CREATE INDEX ix_payout_booking ON payout (booking_id);
-- The admin retry queue.
CREATE INDEX ix_payout_failed  ON payout (created_at) WHERE status = 'FAILED';

COMMENT ON TABLE payout IS
  'Created at HANDOVER, not at payment. The transfer fires AFTER the state change and must not be able to fail the handover request — the bike is already in the customer''s hands and a gateway timeout cannot un-hand it. These rows ARE the month-end statement; nobody sends an invoice.';


CREATE TABLE refund (
    id                  uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id          uuid          NOT NULL REFERENCES booking(id),
    payment_id          uuid          NOT NULL REFERENCES payment(id),
    amount              numeric(10,2) NOT NULL,
    reason              text          NOT NULL,
    -- NULL when automatic (cancel/reject); set for a manual admin refund.
    issued_by           uuid,
    provider_reference  varchar(120),
    status              varchar(20)   NOT NULL DEFAULT 'PENDING',
    created_at          timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_refund_status CHECK (status IN ('PENDING','SENT','FAILED')),
    CONSTRAINT ck_refund_amount CHECK (amount > 0)
);
CREATE INDEX ix_refund_booking ON refund (booking_id);


-- ═══════════════════════════════════════════════════════════════════════
-- AFTER THE RENTAL
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE review (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- ONE review per booking. This UNIQUE is the anti-spam mechanism: a
    -- competitor cannot spray one-star reviews without completing rentals.
    booking_id       uuid         NOT NULL UNIQUE REFERENCES booking(id),
    customer_id      uuid         NOT NULL,
    customer_name    varchar(120) NOT NULL,     -- snapshot, shown as "Sokha C."
    shop_id          uuid         NOT NULL REFERENCES shop(id) ON DELETE CASCADE,
    model            varchar(120) NOT NULL,     -- snapshot; the bike may be retired
    rating           smallint     NOT NULL,
    comment          text,
    shop_reply       text,
    shop_replied_at  timestamptz,
    deleted_at       timestamptz,
    deleted_reason   text,
    created_at       timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5)
);
CREATE INDEX ix_review_shop ON review (shop_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_review_customer ON review (customer_id);


CREATE TABLE assistance_request (
    id                        uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id                uuid          NOT NULL REFERENCES booking(id) ON DELETE CASCADE,
    problem_type              varchar(20)   NOT NULL,
    description               text,
    -- Supplied per request. The platform does NOT track customers continuously.
    latitude                  numeric(9,6)  NOT NULL,
    longitude                 numeric(9,6)  NOT NULL,
    status                    varchar(20)   NOT NULL DEFAULT 'OPEN',
    resolution_note           text,
    replacement_motorbike_id  uuid          REFERENCES motorbike(id) ON DELETE SET NULL,
    created_at                timestamptz   NOT NULL DEFAULT now(),
    resolved_at               timestamptz,

    CONSTRAINT ck_assist_problem CHECK (problem_type IN
        ('FLAT_TYRE','WONT_START','ACCIDENT','OUT_OF_FUEL','OTHER')),
    CONSTRAINT ck_assist_status CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED'))
);
CREATE INDEX ix_assist_booking ON assistance_request (booking_id);
CREATE INDEX ix_assist_open    ON assistance_request (created_at) WHERE status <> 'RESOLVED';


CREATE TABLE notification (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid         NOT NULL,          -- identity.users.id, no FK
    type        varchar(40)  NOT NULL,
    title       varchar(200) NOT NULL,
    body        text,
    booking_id  uuid         REFERENCES booking(id) ON DELETE CASCADE,
    read        boolean      NOT NULL DEFAULT false,
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notif_inbox  ON notification (user_id, created_at DESC);
CREATE INDEX ix_notif_unread ON notification (user_id) WHERE read = false;

COMMENT ON TABLE notification IS
  'The durable record. FCM push is separate and best-effort — a phone can be offline or have permission denied.';


-- ═══════════════════════════════════════════════════════════════════════
-- AUDIT — append only, never updated, never deleted
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE audit_log (
    id           bigserial    PRIMARY KEY,
    actor_id     uuid,                       -- NULL for system actions
    action       varchar(80)  NOT NULL,
    entity_type  varchar(40)  NOT NULL,
    entity_id    varchar(64)  NOT NULL,
    before       jsonb,
    after        jsonb,
    ip_address   inet,
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_entity ON audit_log (entity_type, entity_id);
CREATE INDEX ix_audit_actor  ON audit_log (actor_id, created_at DESC);

COMMENT ON TABLE audit_log IS
  'Must cover at minimum: every manual refund, every payout retry, every payout-account change, every shop verification or suspension. PAYOUT_ACCOUNT_CHANGED is the one an attacker would most want unlogged.';
