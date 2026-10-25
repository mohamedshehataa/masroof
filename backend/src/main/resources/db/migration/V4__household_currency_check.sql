-- REQ-OPS-047: base_currency must be a three-letter upper-case ISO-4217 code (V1 only fixed the width).
ALTER TABLE household
  ADD CONSTRAINT household_base_currency_iso CHECK (base_currency ~ '^[A-Z]{3}$');
