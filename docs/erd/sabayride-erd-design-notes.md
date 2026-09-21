# SabayRide ERD — design notes

The reasoning behind each table. The `.dbml` file carries the structure; this carries the *why*. Read both together.

---

## `users`

No password column here. Credentials live in auth_identity so that one person
  can sign in several ways without this table growing a column per provider.

---

## `auth_identity`

One user, many ways in. Adding Facebook later is new ROWS, not a migration.

  uq_identity_provider_subject is what makes `this Google account is already
  linked to someone else` a database guarantee rather than a check somebody has
  to remember to write.

  Application rule the DB cannot express: never delete a user's last remaining
  identity row. A Google-only user who unlinks Google is locked out forever with
  no recovery path short of editing the database by hand.

---

## `refresh_token`

Refresh tokens are stored server-side so that logout and password reset can
  actually revoke them. A stateless refresh token cannot be revoked, which makes
  `password reset ends other sessions` impossible — and that is the entire point
  of a password reset.

---

## `shop_member`

Staff may confirm, reject, hand over and return. Staff may NOT change the
  payout account or manage other staff — the two actions that move money or
  grant access stay with the owner.

---

## `payout_account`

THE FULL ACCOUNT NUMBER IS NOT STORED. It is sent to PayWay once for
  beneficiary registration and discarded; only the returned reference persists.

  Tokenization: if this database leaks, an attacker gets a token that works only
  with our own PayWay account, not 20 shops` bank account numbers. (FR-74)

  A shop needs only a PERSONAL ABA account — no merchant account, no business
  registration. That is the platform`s core value to a small shop.

---

## `motorbike`

ONE ROW PER PHYSICAL BIKE. Five Honda Dreams are five rows with five plates.

  status is ACTIVE | MAINTENANCE | INACTIVE — never BOOKED, never RENTED.
  Whether this bike is out today is a question about the booking table, not a
  column a human maintains.

  These are the rows the booking transaction takes SELECT ... FOR UPDATE on.
  You cannot lock a model, only rows — which is why a concrete unit is assigned
  at booking creation rather than at pickup.

---

## `booking`

Only PENDING, CONFIRMED and ACTIVE block availability.

  THE OVERLAP CONDITION, the one expression this whole project turns on:
      existing.start_date <  :requested_end_date
  AND existing.end_date   >  :requested_start_date

  ── The availability query ───────────────────────────────────────────────
  SELECT m.id FROM motorbike m
   WHERE m.shop_id = :shopId AND m.model = :model AND m.status = `ACTIVE`
     AND NOT EXISTS (
       SELECT 1 FROM booking b
        WHERE b.assigned_motorbike_id = m.id
          AND b.status IN (`PENDING`,`CONFIRMED`,`ACTIVE`)
          AND b.period && daterange(:startDate, :endDate, `[)`))
   ORDER BY m.id
     FOR UPDATE OF m;          -- deterministic order avoids deadlock

  ── Write this as a real partial index in Flyway ─────────────────────────
  CREATE INDEX ix_booking_availability ON booking (start_date, end_date)
    WHERE status IN (`PENDING`,`CONFIRMED`,`ACTIVE`);

  Terminal bookings accumulate forever but can never affect availability, so
  keeping them out of the index keeps it permanently small. dbdiagram cannot
  express a WHERE clause on an index — this note is the specification.

  ── THE CONSTRAINT THAT MAKES DOUBLE-BOOKING IMPOSSIBLE ──────────────────
  CREATE EXTENSION IF NOT EXISTS btree_gist;

  ALTER TABLE booking
    ADD CONSTRAINT no_double_booking
    EXCLUDE USING gist (assigned_motorbike_id WITH =, period WITH &&)
    WHERE (status IN (`PENDING`,`CONFIRMED`,`ACTIVE`));

  PostgreSQL now REFUSES, at the storage layer, to hold two non-terminal
  bookings for the same unit whose periods overlap. Not `unlikely` — impossible.
  Violations raise 23P01 exclusion_violation, which the service maps to
  409 NO_UNITS_AVAILABLE.

  This does NOT replace the pessimistic lock. The lock is what lets the code
  CHOOSE a free unit and return a clean error; the constraint is what guarantees
  correctness if the lock is ever wrong, removed in a refactor, or bypassed by a
  script, a migration, or a second application instance. Two independent
  mechanisms for the one invariant the whole product depends on.

  MySQL has no equivalent. This is the concrete answer to `why PostgreSQL`.
  dbdiagram cannot express EXCLUDE — write it in the Flyway migration by hand.

---

## `booking_item`

THE SNAPSHOT TABLE. brand, model, price_per_day and delivery_fee are COPIED
  here at creation and never updated. When a shop raises its price next month,
  this booking`s history stays true. Without the copy, every past receipt
  silently rewrites itself and the revenue report stops matching the bank.

  PURELY the price snapshot. The unit ID moved to booking so the EXCLUDE
  constraint could see it next to status and period — correctness beat purity,
  which is the right way for that argument to go.

  Still 1:1 with booking in V1. Kept separate because `one booking, several
  bikes` (a family renting three) is a plausible V2 and this keeps that change
  additive. When that day comes, the EXCLUDE constraint moves to this table
  along with a copy of period.

---

## `booking_extension`

An extension is a SEPARATE payment and a separate payout, not an edit of the
  original. The first payment has settled and its payout may already have
  reached the shop; rewriting the original total would leave money that moved
  no longer matching the booking that justified it.

  Same lock as creation, but with exactly ONE candidate unit — the bike in the
  customer`s hands. It cannot be swapped, so if that unit is taken for the
  extension window the extension is simply refused. No fallback.

---

## `idempotency_key`

Required on POST /bookings and POST /bookings/{id}/extend.

  Cambodian mobile data drops responses; the customer taps Confirm again. Without
  this table you create two bookings and lock two bikes. The unique constraint on
  `key` is what makes the guarantee real rather than a race in application code.

---

## `payment`

A payment becomes PAID only when the provider`s SIGNED callback arrives. The
  client never reports success — a client that says `user paid` is trivially
  forged.

  If the provider`s amount does not match the booking total, status becomes
  AMOUNT_MISMATCH and the booking stays unconfirmed for admin review. Trust the
  provider`s number, never the client`s.

---

## `payment_callback_log`

Every callback is logged BEFORE it is acted on, valid or not. Two jobs: the
  duplicate check that makes the handler idempotent (the provider retries), and
  the evidence trail when a payment is disputed.

---

## `payout`

Created at HANDOVER, not at payment. Charge at booking, hold, release when the
  bike is physically handed over.

  The transfer is fired AFTER the state change and must not be able to fail the
  handover request — the bike is already in the customer`s hands and a gateway
  timeout cannot un-hand it. On failure: status FAILED, a scheduled job retries,
  and /admin/payouts/failed is the queue. This ordering is a decision, not an
  oversight.

  These rows ARE the month-end statement. Nobody sends an invoice; nobody
  transfers money by hand.

---

## `audit_log`

Append-only. Never updated, never deleted.

  Must cover, at minimum: every manual refund, every payout retry, every
  payout-account change, every shop verification or suspension. A manual money
  movement with no record of who authorised it is the first thing an auditor
  asks about — and payout_account_changed is the one an attacker would want
  unlogged.

---

