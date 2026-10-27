---
id: STORY-040
epic: EPIC-04
title: Android More tab basics — language/theme/digits, household & members, categories, accounts & cards
phase: 2
status: DESCOPED
size: M
modules: [android]
depends_on: [STORY-033, STORY-012, STORY-013, STORY-014, STORY-015]
reqs: [REQ-AND-017]
implements: ["PROMPT.md §11 Navigation 5 (More)", "PROMPT.md §9 (settings, categories, accounts, invite)"]
owner_agent: android-engineer
---

# STORY-040 — Android More tab basics

## Goal
Same management screens as the PWA (STORY-031) natively; later More rows (Review inbox, Budgets, Sources, Parsing rules, Export/Import, Security, AI usage, About) appear with their phases.

## Requirements and acceptance criteria
### REQ-AND-017 — More: preferences, household & members, categories, accounts
**Given** More **Then** sections Preferences (Language ar/en via per-app locales, Theme system/light/dark, Digits Western/Arabic-Indic — applied immediately, persisted in DataStore), Household & members (name and month start day 1–28 editable by admin → `PATCH /api/settings`, ATM-as-spending toggle, members with colour chips/roles, admin actions Edit/Remove/Invite — Invite collects display name + phone, calls `POST /auth/invite`, shows the link once with Copy and the system share sheet), Categories (tree with bilingual names, drag-to-reorder for admin, Add/Edit/Archive dialogs, Show archived switch) and Accounts & cards (grouped by member; Add/Edit/Archive with last4 mask, institution autocomplete), plus Log out.
Unhappy path: **Given** a MEMBER **Then** admin controls are hidden and `403` shows "Only the admin can change this"; **Given** `409` (duplicate name/last4, archive with children, household full) **Then** the Problem title appears inline; **Given** offline **Then** lists render from cache read-only with a banner.

## UX states (UI stories only)
empty: per list · loading: shimmer rows · error: section Retry; dialog inline errors · offline: cached read-only · RTL: tree indentation mirrored; `•••• ١٢٣٤` per digit setting · dark: theme; drag handles with accessibility actions; Roborazzi matrix.

## Contract impact
Uses `GET/PATCH /api/settings`, `/api/members*`, `POST /auth/invite`, `/api/categories*`, `/api/accounts*`. None new.

## Data / migration impact
Room cache tables from STORY-038 extended with `member`, `category`, `payment_account` if not already present (Room schema v2 with migration test).

## Security / privacy / LLM-safety impact
Invite link shown once; not persisted. Phone numbers masked by the API for non-admins.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-033, STORY-012, STORY-013, STORY-014, STORY-015.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (four list screens sharing one CRUD list component; architect may split Categories+Accounts into a follow-up if the design exceeds M)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
