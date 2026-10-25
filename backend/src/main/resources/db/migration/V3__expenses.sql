-- STORY-020/021: manually entered expenses. Money is BIGINT minor units + CHAR(3); instants are timestamptz.
CREATE TABLE expense (
  id                  uuid        PRIMARY KEY,
  household_id        uuid        NOT NULL REFERENCES household (id),
  type                text        NOT NULL DEFAULT 'EXPENSE'
                                  CHECK (type IN ('EXPENSE', 'INCOME', 'REFUND', 'TRANSFER', 'CASH_WITHDRAWAL')),
  amount_minor        bigint      NOT NULL CHECK (amount_minor > 0),
  currency            char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  occurred_at         timestamptz NOT NULL,
  category_id         uuid        REFERENCES category (id),
  payment_account_id  uuid        REFERENCES payment_account (id),
  paid_by_member_id   uuid        REFERENCES member (id),
  payment_method      text        NOT NULL DEFAULT 'UNKNOWN'
                                  CHECK (payment_method IN ('CASH', 'CARD', 'WALLET', 'INSTAPAY', 'BANK_TRANSFER', 'UNKNOWN')),
  merchant_raw        text        CHECK (char_length(merchant_raw) <= 200),
  note                text        CHECK (char_length(note) <= 2000),
  tags                text[]      NOT NULL DEFAULT '{}',
  status              text        NOT NULL DEFAULT 'CONFIRMED'
                                  CHECK (status IN ('CONFIRMED', 'PENDING_REVIEW', 'POSSIBLE_DUPLICATE', 'IGNORED')),
  source              text        NOT NULL DEFAULT 'MANUAL'
                                  CHECK (source IN ('WHATSAPP', 'SMS', 'PUSH', 'IOS_SHORTCUT', 'UPLOAD', 'MANUAL')),
  deleted_at          timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);

-- Keyset pagination: one index per sort key, each ending in id as the tiebreaker.
CREATE INDEX expense_household_occurred_idx ON expense (household_id, occurred_at DESC, id DESC);
CREATE INDEX expense_household_created_idx  ON expense (household_id, created_at DESC, id DESC);
CREATE INDEX expense_household_amount_idx   ON expense (household_id, amount_minor DESC, id DESC);
CREATE INDEX expense_category_idx ON expense (category_id);

CREATE TRIGGER expense_set_updated_at BEFORE UPDATE ON expense
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- POST /api/expenses Idempotency-Key (REQ-EXP-020): same key + same body within 24 h returns the original row.
CREATE TABLE idempotency_key (
  household_id  uuid        NOT NULL REFERENCES household (id),
  key           text        NOT NULL CHECK (char_length(key) BETWEEN 1 AND 128),
  fingerprint   char(64)    NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
  expense_id    uuid        NOT NULL REFERENCES expense (id),
  created_at    timestamptz NOT NULL,
  PRIMARY KEY (household_id, key)
);
