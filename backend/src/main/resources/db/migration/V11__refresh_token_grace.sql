-- STORY-167: counts grace re-issues of a rotated refresh token (bounded retry window). Expand-only.
ALTER TABLE refresh_token
  ADD COLUMN grace_count integer NOT NULL DEFAULT 0;
