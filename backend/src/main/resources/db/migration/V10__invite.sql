-- STORY-011: household member invites. Only the SHA-256 of the code is stored; the code is shown once. Expand-only.
CREATE TABLE invite (
  id                 uuid        PRIMARY KEY,
  household_id       uuid        NOT NULL REFERENCES household (id),
  created_by         uuid        NOT NULL REFERENCES member (id),
  code_hash          char(64)    NOT NULL CHECK (code_hash ~ '^[0-9a-f]{64}$'),
  display_name       text        NOT NULL CHECK (btrim(display_name) <> ''),
  email              text,
  phone_e164         text        CHECK (phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
  role               text        NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('MEMBER')),
  status             text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED')),
  expires_at         timestamptz NOT NULL,
  created_at         timestamptz NOT NULL DEFAULT now(),
  accepted_at        timestamptz,
  accepted_member_id uuid        REFERENCES member (id),
  revoked_at         timestamptz
);
CREATE UNIQUE INDEX invite_code_hash_uq ON invite (code_hash);
CREATE INDEX invite_household_status_idx ON invite (household_id, status, expires_at);
