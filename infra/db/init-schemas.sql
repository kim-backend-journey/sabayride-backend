-- infra/db/init-schemas.sql
-- Runs once, on first boot of the postgres container.
--
-- TWO schemas, because the architecture is three deployables:
--   gateway (stateless) · identity-service · rental-service
--
-- Each service gets a schema AND a user that can only see that schema, so a
-- cross-service query fails with a permission error instead of quietly working
-- and becoming load-bearing. A boundary you cannot accidentally cross is worth
-- more than a boundary everyone has agreed to respect.

-- btree_gist lets an EXCLUDE constraint mix an equality test on a uuid with an
-- overlap test on a daterange. Without it, no_double_booking cannot exist.
-- Installed into public so every schema's search_path can reach the operators.
CREATE EXTENSION IF NOT EXISTS btree_gist SCHEMA public;

-- ── identity-service ────────────────────────────────────────────────────
CREATE SCHEMA identity;
CREATE USER identity_user WITH PASSWORD 'devpassword';
GRANT USAGE, CREATE ON SCHEMA identity TO identity_user;
GRANT USAGE ON SCHEMA public TO identity_user;
ALTER ROLE identity_user SET search_path = identity, public;

-- ── rental-service ──────────────────────────────────────────────────────
-- shop + motorbike + booking + payment all live here, together, on purpose.
-- The availability lock and the no_double_booking constraint both require
-- motorbike and booking in ONE database and ONE transaction. Splitting them is
-- the single thing this architecture may not do.
CREATE SCHEMA rental;
CREATE USER rental_user WITH PASSWORD 'devpassword';
GRANT USAGE, CREATE ON SCHEMA rental TO rental_user;
GRANT USAGE ON SCHEMA public TO rental_user;
ALTER ROLE rental_user SET search_path = rental, public;

-- ── the boundary, enforced ──────────────────────────────────────────────
-- No GRANT is issued across schemas. rental_user reading identity.users gets:
--   ERROR: permission denied for schema identity
--
-- Design around it, do not work around it: rental-service holds a SNAPSHOT of
-- the customer's name and phone on the booking row, copied at creation. That is
-- the same snapshot principle already used for price and model — and it means a
-- booking still renders when identity-service is down.

-- Nobody should drift into using public for tables.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- ── not created yet ─────────────────────────────────────────────────────
-- content (Explore Siem Reap) is documented in the API contract but not built
-- in V1. When it is extracted, add its schema and user here.
