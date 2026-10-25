-- REQ-AN-001..007: period aggregates scan a household's live expenses by time. A partial covering
-- index lets them run index-only without touching soft-deleted rows. Expand-only.
CREATE INDEX expense_analytics_idx
  ON expense (household_id, occurred_at)
  INCLUDE (amount_minor, currency, type, status, category_id)
  WHERE deleted_at IS NULL;
