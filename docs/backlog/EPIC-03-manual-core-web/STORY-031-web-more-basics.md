---
id: STORY-031
epic: EPIC-03
title: Web More tab basics — language/theme/digits, household & members, categories, accounts & cards
phase: 2
status: DESCOPED
size: M
modules: [web]
depends_on: [STORY-017, STORY-018, STORY-012, STORY-013, STORY-014, STORY-015]
reqs: [REQ-UI-026, REQ-UI-027]
implements: ["PROMPT.md §11 Navigation 5 (More: Categories, Accounts & cards, Household & members, Language/theme/digits)", "PROMPT.md §9 (settings, categories, accounts, invite)"]
owner_agent: web-engineer
---

# STORY-031 — Web More tab basics

## Goal
The household manages its own master data and preferences from the PWA: language, theme and digits; members and invites; categories; accounts and cards (PROMPT §11 More). Other More entries (Review inbox, Budgets, Sources, Parsing rules, Export/Import, Security, AI usage, About) are added by their phases and appear as rows only once their story ships.

## Requirements and acceptance criteria
### REQ-UI-026 — Preferences and Household & members
**Given** `/more` **Then** a list of sections; **Given** `/more/preferences` **Then** controls for Language (ar/en), Theme (system/light/dark), Digits (Western/Arabic-Indic) that apply immediately and persist (REQ-UI-002/003/005), plus a Log out row.
**Given** `/more/household` **Then** the household name (admin-editable → `PATCH /api/settings`), month start day (1–28 picker, admin-only, helper text "e.g. 25 if you get paid on the 25th"), "Count ATM withdrawals as spending" toggle, and the member list with colour chips, role badges and (admin) "Edit", "Remove" and "Invite member" (opens a dialog collecting display name and phone, calls `POST /auth/invite`, shows the link once with Copy and Share buttons).
Unhappy path: **Given** a MEMBER **Then** admin controls are hidden and a `403` from the API shows "Only the admin can change this"; **Given** the household is full **Then** Invite is disabled with "Two members max"; **Given** Remove **Then** a confirm dialog naming the member; `409` renders the Problem title.

### REQ-UI-027 — Categories and Accounts & cards screens
**Given** `/more/categories` **Then** the tree (parents with collapsible children) with icon, bilingual names (the other language shown as secondary text), drag-to-reorder (admin; `PATCH sortOrder`), "Add category" dialog (nameEn, nameAr, icon picker, colour, parent, essential toggle) and per-row Edit/Archive with a "Show archived" switch.
**Given** `/more/accounts` **Then** accounts grouped by member with type icon, institution, `•••• 1234`, nickname, currency, shared badge; "Add account" dialog (type, institution autocomplete from the seeded list, last4 with 4-digit mask hidden for CASH, nickname, currency, shared) and Edit/Archive.
Unhappy path: **Given** `409` duplicate name/last4 **Then** inline error on the field; **Given** archive of a parent with children **Then** the Problem title "Archive children first" shows inline; **Given** the list is empty (new household before seeds — should not happen) **Then** an empty state with the Add button.

## UX states (UI stories only)
empty: per list · loading: skeleton rows · error: section error with Retry; dialog errors inline · offline: read-only with banner · RTL: tree indentation mirrored, `•••• ١٢٣٤` digits per setting, dialogs mirrored · dark: tokens; drag handles keyboard-operable (axe clean).

## Contract impact
Uses `GET/PATCH /api/settings`, `/api/members*` (STORY-015), `POST /auth/invite`, `/api/categories*`, `/api/accounts*`. None new.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Invite link is shown once and copied via the Clipboard API; it is never stored client-side after the dialog closes. Phone numbers are masked for non-admins by the API.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-017, STORY-018, STORY-012, STORY-013, STORY-014, STORY-015.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (two REQs, each ~150 lines of components; split at design if the architect finds it larger)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
