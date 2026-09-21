-- identity-service/src/main/resources/db/migration/V1__init.sql
--
-- WHY VARCHAR + CHECK INSTEAD OF POSTGRESQL NATIVE ENUMS
--
-- Native `CREATE TYPE ... AS ENUM` is more elegant, and it costs you:
--   1. Hibernate needs @JdbcTypeCode(SqlTypes.NAMED_ENUM) or a custom type;
--      plain @Enumerated(EnumType.STRING) maps to varchar with no ceremony.
--   2. ALTER TYPE ... ADD VALUE cannot run inside a transaction on older
--      PostgreSQL, which makes Flyway migrations awkward.
--   3. Removing or renaming a value is genuinely painful.
--
-- varchar + CHECK gives the same guarantee — an invalid value is rejected by
-- the database — with none of that. A CHECK is also a one-line migration to
-- change. This is a deliberate trade of elegance for maintainability.

SET search_path = identity, public;

-- ═══════════════════════════════════════════════════════════════════════
-- users
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE users (
    id                  uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name           varchar(120) NOT NULL,
    -- Nullable: a Google-created account has an identity but no phone yet.
    phone               varchar(20),
    email               varchar(255),
    -- Gates booking. rental-service refuses POST /bookings when false.
    phone_verified      boolean      NOT NULL DEFAULT false,
    -- True only when PROVED (Google, or a confirmation link). A typed-in
    -- address is never verified — this flag is what Google sign-in consults
    -- before auto-linking, and auto-linking on an unverified match is the
    -- pre-hijacking vulnerability.
    email_verified      boolean      NOT NULL DEFAULT false,
    preferred_language  varchar(2)   NOT NULL DEFAULT 'en',
    status              varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    suspension_reason   text,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    last_active_at      timestamptz,

    CONSTRAINT ck_users_language CHECK (preferred_language IN ('km','en')),
    CONSTRAINT ck_users_status   CHECK (status IN ('ACTIVE','SUSPENDED'))
);

-- Case-insensitive uniqueness. sok@gmail.com and Sok@Gmail.com are one account;
-- a plain UNIQUE would let both exist and the Google linking logic would then
-- match the wrong row.
CREATE UNIQUE INDEX uq_users_phone       ON users (phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email)) WHERE email IS NOT NULL;

COMMENT ON TABLE users IS
  'No password column. Credentials live in auth_identity so one person can sign in several ways without a column per provider.';


-- ═══════════════════════════════════════════════════════════════════════
-- auth_identity — one user, many ways in
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE auth_identity (
    id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider          varchar(20)  NOT NULL,
    provider_subject  varchar(255),          -- Google `sub`; NULL for PASSWORD
    password_hash     varchar(72),           -- BCrypt;       NULL for GOOGLE
    created_at        timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_identity_provider CHECK (provider IN ('PASSWORD','GOOGLE')),
    -- A PASSWORD row must carry a hash; a GOOGLE row must carry a subject.
    -- Making the impossible state unrepresentable beats validating it later.
    CONSTRAINT ck_identity_shape CHECK (
        (provider = 'PASSWORD' AND password_hash IS NOT NULL AND provider_subject IS NULL)
     OR (provider = 'GOOGLE'   AND provider_subject IS NOT NULL AND password_hash IS NULL)
    )
);

-- THIS is what makes "that Google account is already linked to someone else" a
-- database guarantee rather than a check somebody has to remember to write.
CREATE UNIQUE INDEX uq_identity_provider_subject
    ON auth_identity (provider, provider_subject) WHERE provider_subject IS NOT NULL;
CREATE UNIQUE INDEX uq_identity_user_provider
    ON auth_identity (user_id, provider);
CREATE INDEX ix_identity_user ON auth_identity (user_id);

COMMENT ON TABLE auth_identity IS
  'APPLICATION RULE THE DB CANNOT EXPRESS: never delete a user''s last remaining row. A Google-only user who unlinks Google is locked out forever.';


-- ═══════════════════════════════════════════════════════════════════════
-- roles
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE user_role (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        varchar(20) NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_user_role CHECK (role IN ('CUSTOMER','ADMIN')),
    CONSTRAINT uq_user_role UNIQUE (user_id, role)
);

COMMENT ON TABLE user_role IS
  'CUSTOMER and ADMIN only. SHOP_OWNER and SHOP_STAFF are scoped to a shop and live in rental.shop_member.';


-- ═══════════════════════════════════════════════════════════════════════
-- sessions and codes
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE refresh_token (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- SHA-256 of the token, never the token. A database leak must not be a
    -- session leak.
    token_hash  varchar(64) NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_user ON refresh_token (user_id) WHERE revoked_at IS NULL;

COMMENT ON TABLE refresh_token IS
  'Stored server-side so logout and password reset can actually revoke. A stateless refresh token cannot be revoked, which makes "reset ends other sessions" impossible — and that is the entire point of a reset.';


CREATE TABLE otp_code (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    phone          varchar(20) NOT NULL,
    purpose        varchar(30) NOT NULL,
    -- Hashed like a password. A leak must not hand over live codes.
    code_hash      varchar(64) NOT NULL,
    expires_at     timestamptz NOT NULL,
    -- Destroy the code at 5. Six digits is only a million guesses; without a
    -- cap a script is through in minutes.
    attempt_count  smallint    NOT NULL DEFAULT 0,
    consumed_at    timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_otp_purpose CHECK (
        purpose IN ('PHONE_VERIFICATION','PASSWORD_RESET','PHONE_CHANGE'))
);
CREATE INDEX ix_otp_lookup ON otp_code (phone, purpose) WHERE consumed_at IS NULL;
-- The rate-limit count: 3/hour, 10/day per phone.
CREATE INDEX ix_otp_ratelimit ON otp_code (phone, created_at);


CREATE TABLE password_reset_token (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   varchar(64) NOT NULL UNIQUE,
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz,      -- single use; replay returns 400
    created_at   timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE password_reset_token IS
  'Scoped to password reset only. Cannot be used as an access token.';


CREATE TABLE device (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    fcm_token     varchar(255) NOT NULL UNIQUE,
    platform      varchar(10)  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    last_seen_at  timestamptz,

    CONSTRAINT ck_device_platform CHECK (platform IN ('ANDROID','IOS'))
);
CREATE INDEX ix_device_user ON device (user_id);

COMMENT ON TABLE device IS
  'Deleted on logout, so the next user of a shared phone does not receive the previous user''s booking notifications.';


-- ═══════════════════════════════════════════════════════════════════════
-- updated_at, maintained by the database
-- ═══════════════════════════════════════════════════════════════════════
-- In the database rather than in Java, so a direct SQL fix or a second service
-- cannot leave a stale timestamp behind.

CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_users_updated
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
