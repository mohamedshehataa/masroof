-- STORY-004 (trimmed): members, users, refresh tokens, payment accounts, categories; household hardening.
-- Immutable once on main. Expand-only: no existing column is dropped or retyped.

-- Household hardening: a real name, and the only supported business timezone.
ALTER TABLE household
  ADD CONSTRAINT household_name_not_blank CHECK (btrim(name) <> ''),
  ADD CONSTRAINT household_timezone_cairo CHECK (timezone = 'Africa/Cairo');

-- Keeps updated_at honest without every writer remembering to set it.
CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER household_set_updated_at BEFORE UPDATE ON household
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE member (
  id            uuid        PRIMARY KEY,
  household_id  uuid        NOT NULL REFERENCES household (id),
  display_name  text        NOT NULL CHECK (btrim(display_name) <> ''),
  role          text        NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('ADMIN', 'MEMBER')),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX member_household_idx ON member (household_id);
CREATE TRIGGER member_set_updated_at BEFORE UPDATE ON member
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE app_user (
  id             uuid        PRIMARY KEY,
  member_id      uuid        NOT NULL UNIQUE REFERENCES member (id),
  email          text        NOT NULL CHECK (btrim(email) <> '' AND position('@' IN email) > 1),
  password_hash  text        NOT NULL CHECK (password_hash ~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$'),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX app_user_email_lower_uq ON app_user (lower(email));
CREATE TRIGGER app_user_set_updated_at BEFORE UPDATE ON app_user
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE refresh_token (
  id          uuid        PRIMARY KEY,
  user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
  token_hash  char(64)    NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  expires_at  timestamptz NOT NULL,
  revoked_at  timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);

CREATE TABLE payment_account (
  id            uuid        PRIMARY KEY,
  household_id  uuid        NOT NULL REFERENCES household (id),
  member_id     uuid        NOT NULL REFERENCES member (id),
  type          text        NOT NULL CHECK (type IN ('CARD', 'BANK', 'WALLET', 'CASH')),
  institution   text        CHECK (char_length(institution) <= 80),
  last4         char(4)     CHECK (last4 ~ '^[0-9]{4}$'),
  nickname      text        CHECK (char_length(nickname) <= 60),
  currency      char(3)     NOT NULL DEFAULT 'EGP' CHECK (currency ~ '^[A-Z]{3}$'),
  is_shared     boolean     NOT NULL DEFAULT false,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_account_household_idx ON payment_account (household_id);
CREATE TRIGGER payment_account_set_updated_at BEFORE UPDATE ON payment_account
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE category (
  id             uuid        PRIMARY KEY,
  household_id   uuid        NOT NULL REFERENCES household (id),
  parent_id      uuid        REFERENCES category (id),
  reference_key  text,
  name_en        text        NOT NULL CHECK (btrim(name_en) <> '' AND char_length(name_en) <= 60),
  name_ar        text        NOT NULL CHECK (btrim(name_ar) <> '' AND char_length(name_ar) <= 60),
  icon           text        CHECK (char_length(icon) <= 40),
  color          text        CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
  is_essential   boolean     NOT NULL DEFAULT false,
  sort_order     int         NOT NULL DEFAULT 0,
  archived       boolean     NOT NULL DEFAULT false,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX category_household_idx ON category (household_id);
CREATE INDEX category_parent_idx ON category (parent_id);
-- The seed copies the reference set once per household, keyed by reference_key (user-made rows have none).
CREATE UNIQUE INDEX category_household_reference_uq ON category (household_id, reference_key)
  WHERE reference_key IS NOT NULL;
CREATE TRIGGER category_set_updated_at BEFORE UPDATE ON category
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
