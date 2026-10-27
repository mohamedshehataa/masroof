---
id: STORY-185
epic: EPIC-17
title: Export entries to a CSV file that opens correctly in Excel and Sheets
phase: 9
status: READY
risk_tier: high
size: S
modules: [android]
depends_on: [STORY-170]
reqs: [REQ-EXP-048, REQ-SEC-069]
implements: ["ADR-0009 D5 (CSV export: UTF-8 BOM, decimal strings from minor units, Cairo + UTC time, formula escaping, no raw text)", "PROMPT.md §9/§12 export (superseded server endpoint)"]
owner_agent: android-engineer
---

# STORY-185 - Export entries to a CSV file that opens correctly in Excel and Sheets

## Goal
Mohamed can take his data anywhere: Settings -> "Export CSV" writes the entries of a chosen date range to a file through
the system picker. Arabic shows correctly in Excel, amounts are exact, and a merchant label that came from an SMS can
never run as a spreadsheet formula. Replaces the server export of STORY-125 (descoped).

## Requirements and acceptance criteria
### REQ-EXP-048 - Columns, money and time are exact
**Given** entries in October 2026 incl. `paid 1,450 Carrefour groceries` (1,450 EGP), a refund, an income, a transfer, a
USD 12.99 entry and a soft-deleted entry **When** the user exports "This month" (period honours the month start day) or
a custom Cairo date range to a file chosen with `CreateDocument` (default `masroof-2026-10.csv`) **Then** the file is
UTF-8 with a BOM, comma-separated, CRLF line ends, with the header `date_cairo,time_cairo,occurred_at_utc,type,status,
amount,currency,category,parent_category,merchant,account,paid_by,notes,sources,receipts` and one row per not-deleted
entry: amount as a decimal string built from minor units (`1450.00`, `12.99`; amounts are never negative, a refund is a positive
amount with type `REFUND`), `time_cairo` empty when `timeKnown = 0`, `occurred_at_utc` ISO-8601 `Z`, `sources` like `SMS;PUSH`,
`receipts` the receipt count. Arabic names (`بنزين`) appear as typed.
Unhappy path: **Given** a range with no entries **Then** a file with only the header is written and the message says
"0 entries exported".
Unhappy path: **Given** the user cancels the picker or the provider fails mid-write **Then** "Export failed" (or nothing on
cancel) and no success message; app data unchanged.
Unhappy path: **Given** an end date before the start date **Then** Export is disabled with "End date is before start".

### REQ-SEC-069 - No formula injection and no message text
**Given** a merchant label or note starting with `=`, `+`, `-`, `@`, a tab or a carriage return (e.g. `=HYPERLINK("x")`,
`+20 100`, `@SUM(A1)`) **When** exported **Then** the cell is prefixed with `'` and quoted per RFC 4180 (embedded quotes
doubled, commas and new lines inside quotes).
**Given** review items and captured messages exist **Then** no SMS/notification text, no full account number (only a last
four if it is a stored field) and no receipt content appears in the file (test greps for fixture strings).
Unhappy path: **Given** a note containing a new line and a comma **Then** it stays one cell (one CSV record per entry).

## UX states (UI stories only)
- Export dialog: range (This month, Last month, This year, Custom), Export button, progress, done ("1,240 entries
  exported"), failure.
- empty: header-only message above. offline: works offline.
- RTL / dark: dialog strings in `values` + `values-ar`; the CSV header stays English (stable for tools).

## Contract impact
None.

## Data / migration impact
None (read-only).

## Security / privacy / LLM-safety impact
CSV formula injection from SMS-derived labels (ADR-0009 Threats "CSV") mitigated by escaping; no raw message text;
export is an explicit user action to a user-chosen location.

## Eval impact
None.

## Design notes (architect)
ADR-0009 D5 "CSV export".

## Dependencies
STORY-170

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate S: writer ≈ 60, dialog ≈ 30, tests ≈ 90)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D5), replaces STORY-125 |
