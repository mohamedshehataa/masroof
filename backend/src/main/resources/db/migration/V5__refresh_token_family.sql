-- STORY-009: refresh-token families (rotation + reuse detection) and the audit log. Expand-only.
-- family_id defaults to a fresh id, so a token inserted without one is its own family (existing rows too).
ALTER TABLE refresh_token
  ADD COLUMN family_id  uuid NOT NULL DEFAULT gen_random_uuid(),
  ADD COLUMN rotated_at timestamptz;
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);

CREATE TABLE audit_log (
  id            uuid        PRIMARY KEY,
  household_id  uuid        REFERENCES household (id),
  member_id     uuid        REFERENCES member (id),
  action        text        NOT NULL CHECK (action ~ '^[A-Z_]{1,40}$'),
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_household_idx ON audit_log (household_id, created_at);
