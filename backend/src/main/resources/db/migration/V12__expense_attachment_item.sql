-- STORY-168 (REQ-RCPT-020..026, REQ-RCPT-030..032): receipt attachments (bytes live in S3/MinIO, the key
-- is derived from ids only) and purchased items on an expense. Expand-only.
CREATE TABLE expense_attachment (
  id                      uuid        PRIMARY KEY,
  household_id            uuid        NOT NULL REFERENCES household (id),
  expense_id              uuid        NOT NULL REFERENCES expense (id),
  storage_key             text        NOT NULL,
  content_type            text        NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
  size_bytes              bigint      NOT NULL CHECK (size_bytes BETWEEN 1 AND 8388608),
  sha256                  char(64)    NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  uploaded_by_member_id   uuid        REFERENCES member (id),
  created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX expense_attachment_expense_idx ON expense_attachment (expense_id, created_at, id);
CREATE INDEX expense_attachment_household_idx ON expense_attachment (household_id);

CREATE TABLE expense_item (
  id            uuid          PRIMARY KEY,
  household_id  uuid          NOT NULL REFERENCES household (id),
  expense_id    uuid          NOT NULL REFERENCES expense (id),
  position      int           NOT NULL CHECK (position >= 0),
  name          text          NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
  qty           numeric(12,3) CHECK (qty > 0),
  total_minor   bigint        CHECK (total_minor >= 0),
  currency      char(3)       CHECK (currency ~ '^[A-Z]{3}$'),
  created_at    timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT expense_item_money_both_or_neither CHECK ((total_minor IS NULL) = (currency IS NULL))
);
CREATE UNIQUE INDEX expense_item_position_uq ON expense_item (expense_id, position);
CREATE INDEX expense_item_household_idx ON expense_item (household_id);
