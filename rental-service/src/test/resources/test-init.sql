-- Mirrors infra/db/init-schemas.sql for the Testcontainers database.
-- Without btree_gist the no_double_booking EXCLUDE constraint cannot be created
-- and V1__init.sql fails.
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE SCHEMA IF NOT EXISTS rental;
