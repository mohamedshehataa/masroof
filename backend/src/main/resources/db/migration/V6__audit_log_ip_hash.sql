-- STORY-009: login failures and blocks are audited with a hashed client IP (never the address). Expand-only.
ALTER TABLE audit_log
  ADD COLUMN ip_hash char(64) CHECK (ip_hash ~ '^[0-9a-f]{64}$');
