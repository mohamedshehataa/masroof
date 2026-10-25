#!/usr/bin/env bash
# Seeds one demo household (two members, a cash account, the reference categories) when none exists.
# Run by `make up` after the stack is healthy. Idempotent. Dev data only: never run against production.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1
[ -f .env ] && set -a && . ./.env && set +a
user="${POSTGRES_USER:-masroof}"
db="${POSTGRES_DB:-masroof}"

docker compose --env-file .env -f infra/docker-compose.yml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U "$user" -d "$db" <<'SQL'
DO $$
DECLARE
  h uuid := gen_random_uuid();
  m1 uuid := gen_random_uuid();
  m2 uuid := gen_random_uuid();
BEGIN
  IF EXISTS (SELECT 1 FROM household) THEN
    RAISE NOTICE 'demo seed skipped: a household already exists';
    RETURN;
  END IF;
  INSERT INTO household (id, name) VALUES (h, 'Demo household');
  INSERT INTO member (id, household_id, display_name, role) VALUES (m1, h, 'Mohamed', 'ADMIN'), (m2, h, 'Member 2', 'MEMBER');
  INSERT INTO payment_account (id, household_id, member_id, type, nickname)
    VALUES (gen_random_uuid(), h, m1, 'CASH', 'Cash'), (gen_random_uuid(), h, m2, 'CARD', 'Card');
  PERFORM seed_household_categories(h);
END
$$;
SQL
