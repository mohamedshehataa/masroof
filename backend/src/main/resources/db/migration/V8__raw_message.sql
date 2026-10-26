-- STORY-043/056 (trimmed): the immutable-ish audit trail of every ingested message, doubling as the job queue.
-- A row is claimed with FOR UPDATE SKIP LOCKED while processing_status = 'QUEUED'. Expand-only.
-- body holds the message text; encryption at rest (APP_ENCRYPTION_KEY, AES-GCM) is a follow-up (STORY-122).
CREATE TABLE raw_message (
  id                uuid        PRIMARY KEY,
  household_id      uuid        NOT NULL REFERENCES household (id),
  member_id         uuid        REFERENCES member (id),
  source            text        NOT NULL
                                CHECK (source IN ('WHATSAPP', 'SMS', 'PUSH', 'IOS_SHORTCUT', 'UPLOAD', 'MANUAL')),
  device_id         uuid,
  external_id       text        CHECK (char_length(external_id) <= 128),
  sender            text        CHECK (char_length(sender) <= 40),
  body              text        NOT NULL CHECK (char_length(body) <= 4000),
  received_at       timestamptz NOT NULL,
  processing_status text        NOT NULL DEFAULT 'QUEUED'
                                CHECK (processing_status IN ('RECEIVED', 'QUEUED', 'DONE', 'IGNORED', 'NEEDS_REVIEW', 'FAILED')),
  error_code        text        CHECK (char_length(error_code) <= 60),
  attempts          int         NOT NULL DEFAULT 0,
  next_attempt_at   timestamptz NOT NULL DEFAULT now(),
  template_id       text        CHECK (char_length(template_id) <= 60),
  expense_id        uuid        REFERENCES expense (id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);

-- Idempotency of device uploads: the same (device, sms id) is stored once per household.
CREATE UNIQUE INDEX raw_message_device_external_uq
  ON raw_message (household_id, source, device_id, external_id)
  WHERE device_id IS NOT NULL AND external_id IS NOT NULL;

CREATE INDEX raw_message_queue_idx ON raw_message (next_attempt_at, id) WHERE processing_status = 'QUEUED';
CREATE INDEX raw_message_expense_idx ON raw_message (expense_id) WHERE expense_id IS NOT NULL;
CREATE INDEX raw_message_household_idx ON raw_message (household_id, received_at DESC);

CREATE TRIGGER raw_message_set_updated_at BEFORE UPDATE ON raw_message
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
