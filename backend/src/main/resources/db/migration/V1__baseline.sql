-- STORY-002 baseline; immutable once on main
CREATE TABLE household (
  id              uuid        PRIMARY KEY,
  name            text        NOT NULL,
  base_currency   char(3)     NOT NULL DEFAULT 'EGP',
  timezone        text        NOT NULL DEFAULT 'Africa/Cairo',
  month_start_day int         NOT NULL DEFAULT 1 CHECK (month_start_day BETWEEN 1 AND 28),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
