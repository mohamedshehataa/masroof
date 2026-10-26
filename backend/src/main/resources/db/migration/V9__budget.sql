-- REQ-AN-008: monthly/weekly budgets, per category or for the whole household. Expand-only.
CREATE TABLE budget (
  id                uuid        PRIMARY KEY,
  household_id      uuid        NOT NULL REFERENCES household (id),
  category_id       uuid        REFERENCES category (id),
  period            text        NOT NULL DEFAULT 'MONTHLY' CHECK (period IN ('MONTHLY', 'WEEKLY')),
  amount_minor      bigint      NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 1000000000000),
  currency          char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  rollover          boolean     NOT NULL DEFAULT false,
  alert_thresholds  int[]       NOT NULL DEFAULT ARRAY[50, 80, 100],
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX budget_household_idx ON budget (household_id);
-- One budget per household, category (or the total) and period kind.
CREATE UNIQUE INDEX budget_scope_uq
  ON budget (household_id, coalesce(category_id, '00000000-0000-0000-0000-000000000000'::uuid), period);
CREATE TRIGGER budget_set_updated_at BEFORE UPDATE ON budget
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
