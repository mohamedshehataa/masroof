---
id: STORY-013
epic: EPIC-01
title: Bilingual category tree CRUD
phase: 1
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-010]
reqs: [REQ-EXP-012, REQ-EXP-013]
implements: ["PROMPT.md §9 (CRUD /api/categories)", "PROMPT.md §8 (category, default categories)"]
owner_agent: backend-engineer
---

# STORY-013 — Bilingual category tree CRUD

## Goal
Members list the household's bilingual category tree and the admin adds, edits and archives categories (PROMPT §8, §9).

## Requirements and acceptance criteria
### REQ-EXP-012 — List and create categories
**Given** an authenticated member **When** `GET /api/categories` **Then** `200 {items: Category[]}` with the household's non-archived categories (`id, nameEn, nameAr, icon, color, parentId, isEssential, sortOrder, archived`) ordered by `sortOrder` then `nameEn`; `?includeArchived=true` adds archived ones.
**Given** an ADMIN **When** `POST /api/categories` with `{nameEn: "Pets", nameAr: "حيوانات", icon: "paw", color: "#0F766E", parentId: null, isEssential: false}` **Then** `201 Category` with `sortOrder` = max + 1.
Unhappy path: **Given** `nameEn` or `nameAr` is blank or > 40 chars **Then** `422`; **Given** a category with the same `nameEn` (case-insensitive) exists in the household **Then** `409`; **Given** `parentId` belongs to another household or is itself a child (depth > 2) **Then** `404` / `422`; **Given** a MEMBER posts **Then** `403`.

### REQ-EXP-013 — Update and archive categories
**Given** an ADMIN **When** `PATCH /api/categories/{id}` with any subset of `nameEn, nameAr, icon, color, parentId, isEssential, sortOrder` **Then** `200` with the updated category and an `audit_log` row.
**Given** an ADMIN **When** `DELETE /api/categories/{id}` **Then** `204` and the category is archived (`archived = true`, `deleted_at` set), excluded from the default list, and existing expenses keep their `category_id`.
Unhappy path: **Given** the category has non-archived children **Then** `409 title: "Archive children first"`; **Given** the seeded `Other` category **Then** `409` (cannot archive the fallback); **Given** an id of another household **Then** `404`.

## UX states (UI stories only)
N/A (screens in STORY-031 / STORY-040).

## Contract impact
`/api/categories`, `/api/categories/{categoryId}` and `Category`, `CategoryInput` in v0; add `includeArchived` query parameter (non-breaking).

## Data / migration impact
None beyond V2/V3 (`category` table, seeds).

## Security / privacy / LLM-safety impact
Admin-only writes; household scoping via STORY-010. No LLM.

## Eval impact
None now; category names feed the parser's allowed-category list in Phase 3 (REQ-SEC-022).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-010.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
