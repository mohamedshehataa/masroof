---
id: STORY-182
epic: EPIC-17
title: Bring the server's expenses onto the phone once, then forget the server
phase: 7
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-173, STORY-181]
reqs: [REQ-OPS-174, REQ-OPS-175, REQ-DEDUP-066, REQ-SEC-065]
implements: ["ADR-0009 D6 (one-time import, upsert by serverId, forget credentials)", "ADR-0009 D3 (ambiguous match -> PENDING_REVIEW with merge suggestion)", "ADR-0009 Threats L4"]
owner_agent: android-engineer
---

# STORY-182 - Bring the server's expenses onto the phone once, then forget the server

## Goal
The v0.1.x server holds the household's history. If the phone is still signed in, Settings offers "Import from server"
once: it downloads expenses, categories, accounts, members and budgets through the existing generated client, merges
them into Room without duplicates, then signs out and deletes every server credential, after which the app makes no
network calls (ADR-0009 D6). Budgets and the household month start day / ATM rule are added to the D6 list because the
owner's MVP includes budgets (EPIC-17 README).

## Requirements and acceptance criteria
### REQ-OPS-174 - The import is offered only while a session exists
**Given** a phone with a valid session **When** Settings opens **Then** a card "Import from server" explains what happens
("Downloads your N months of history once; then this phone stops talking to the server") with an Import button.
**Given** no session (fresh install, or after a finished import) **Then** the card is not shown.
Unhappy path: **Given** a session whose refresh token the server rejects **When** Import is tapped **Then** "Sign in again
to import" is shown with a sign-in form (the existing login screen, reached only from this card); local data is
untouched.

### REQ-OPS-175 - The import is complete, idempotent and resumable
**Given** the server holds 1,240 expenses (incl. soft-deleted ones, INCOME, TRANSFER, PENDING_REVIEW), 31 categories (2
custom), 3 accounts, 2 members, 4 budgets, month start day 25 **When** the import runs **Then** it pages
`GET /api/expenses` to the end plus categories, accounts, members, budgets and settings; every server expense is upserted
by `serverId` with its type, status, amount (minor units), currency, occurred instant, category, account, merchant,
notes and paid-by member, plus an `expense_source` row with `channel = SERVER_IMPORT`; soft-deleted server rows are not
imported; categories/accounts/members/budgets are upserted by server id; the "Me" member is renamed to the signed-in
member's display name; the month start day and the ATM-withdrawal rule are copied to the phone settings; a progress line
shows "Imported 600 of 1,240".
**Given** the import is run a second time (e.g. after a crash) **Then** no row is duplicated and rows changed on the phone
since the first run are not overwritten.
Unhappy path: **Given** the network drops, the server answers 5xx, or the app is killed after page 3 **When** the user
taps Import again **Then** it continues and ends with exactly 1,240 imported expenses; the credentials are kept until a
run completes.
Unhappy path: **Given** a server row that fails validation (amount not an integer, unknown currency, unknown enum,
category id that is not in the category list) **Then** that row is skipped, counted in the final message ("3 entries
could not be imported") and the rest continue; nothing invalid is stored.

### REQ-DEDUP-066 - A server row that looks like a local one is a suggestion, never a silent duplicate
**Given** a local expense without `serverId` (a converted queued capture, REQ-SMS-099, or a manual entry) of 1.00 EGP at
21:04:10 UTC and a server expense of 1.00 EGP at 21:05:30 UTC **When** imported **Then** the server row is stored with
`status = PENDING_REVIEW` and a "Possible duplicate" card (STORY-173 card, Merge / Keep both) pairs it with the local
row; nothing is merged automatically. Match rule: same amount and currency, `occurredAt` at most 2 minutes apart.
Unhappy path: **Given** the same amounts 2 minutes 1 second apart, or a different currency **Then** no card and the server
row keeps its own status.

### REQ-SEC-065 - After a completed import the server is forgotten
**Given** an import that completed **Then** the app calls logout once (best effort, its failure is ignored), deletes the
session blob, its Keystore key and the stored server URL, the Settings card disappears, and from then on no HTTP request
of any kind is made (asserted with a recording fake HTTP client over app start, capture, add, Insights and Budgets).
**Given** the session expires or the user signs out without importing **Then** local data is untouched (REQ-AND-131) and
the import is no longer possible until the user signs in from the card.
Unhappy path: **Given** a server response that violates the contract (oversized string, negative page size) **Then** the
generated client's validation rejects it, the run stops with "The server sent unexpected data" and nothing from that
page is stored.

## UX states (UI stories only)
- Settings card: idle, running (progress line, Cancel), done ("Imported 1,240 entries. This phone no longer uses the
  server."), partial ("3 entries could not be imported"), error (network: "Couldn't reach the server, try again";
  session: "Sign in again to import").
- offline: Import disabled with "Connect to the internet to import" (the only screen that needs the network).
- RTL / dark: strings in `values` + `values-ar`, counts use the digit setting.

## Contract impact
None. Uses existing `GET /api/expenses`, `/api/categories`, `/api/accounts`, `/api/members`, `/api/budgets`,
`/api/settings` (or the household settings path the client already has) and `POST /auth/logout` through the frozen
generated client; no new endpoint.

## Data / migration impact
No schema change. Writes `serverId` on imported rows; upserts by `serverId`.

## Security / privacy / LLM-safety impact
Trust boundary L4 (temporary): existing HTTPS and pinning, responses treated as untrusted (generated validation, money
as integers), credentials destroyed afterwards. No new endpoint, permission or exported component. security-reviewer
sign-off (ADR-0009 L4).

## Eval impact
None.

## Design notes (architect)
ADR-0009 D6. Adding budgets and settings to the import list is a scope addition recorded in the EPIC-17 README.

## Dependencies
STORY-173 (duplicate card), STORY-181 (local budgets to import into)

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: importer ≈ 150, Settings card ≈ 50, tests ≈ 190 with mockwebserver3)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D6) |
