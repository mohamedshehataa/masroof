---
id: STORY-175
epic: EPIC-17
title: Snap or share a receipt first and turn it into an entry later
phase: 3
status: DONE
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-174]
reqs: [REQ-RCPT-021, REQ-RCPT-022, REQ-RCPT-023]
implements: ["ADR-0009 D4 (camera through a non-exported FileProvider, share target SEND/SEND_MULTIPLE image/* + application/pdf, content:// only; receipt row with expenseId null)", "PROMPT.md §11 Android-specific (share target)"]
owner_agent: android-engineer
---

# STORY-175 - Snap or share a receipt first and turn it into an entry later

## Goal
At the till Mohamed takes a photo of the receipt, or shares a PDF invoice from Gmail or WhatsApp to Masroof, without
typing anything yet. The receipt waits in a "Receipts" list until he attaches it to an existing entry or creates a new
entry from it. Same storage rules as STORY-174 (ADR-0009 D4). Reuses the share-target part of STORY-128 (descoped).

## Requirements and acceptance criteria
### REQ-RCPT-021 - Take a photo of a receipt with the camera
**Given** the Add sheet or an entry's "Add receipt" **When** the user picks "Camera" and takes a photo **Then** the photo
is written through a non-exported `FileProvider` URI to the app's cache, validated and stored exactly as in REQ-RCPT-016/
017 (JPEG, EXIF stripped), attached to the entry when started from an entry, or stored unattached (`expenseId = null`)
when started from the Add sheet's "Receipt only".
Unhappy path: **Given** the user backs out of the camera **Then** nothing is stored and the temporary file is deleted.
Unhappy path: **Given** no camera app can handle the request **Then** the Camera option is hidden (not shown and failing).
Unhappy path: **Given** the CAMERA permission was never requested by Masroof **Then** the flow still works (the system
camera app takes the photo; Masroof declares no CAMERA permission).

### REQ-RCPT-022 - Share a receipt to Masroof from another app
**Given** another app shares one `image/*` or `application/pdf` item (`ACTION_SEND`) or up to 10 items
(`ACTION_SEND_MULTIPLE`) with `content://` URIs **When** Masroof is chosen **Then** each item is copied at once,
validated and stored unattached (REQ-RCPT-016/017 rules), and the Receipts list opens showing them, newest first.
Unhappy path: **Given** a shared `file://` URI, a URI without a scheme, or an `EXTRA_STREAM` pointing at Masroof's own
`content://` authority **Then** the item is refused ("This file can't be shared to Masroof") and nothing is read.
Unhappy path: **Given** a share with only `EXTRA_TEXT` (no stream), another action than SEND/SEND_MULTIPLE, or 11+
items **Then** text is ignored, other actions are not accepted (intent filter), and only the first 10 items are taken
with "Only 10 receipts at a time".
Unhappy path: **Given** one of several shared items fails validation **Then** the others are stored and the failing one
is listed with its reason.

### REQ-RCPT-023 - Unattached receipts become entries
**Given** the Receipts list (More -> Receipts) with unattached receipts **When** the user taps one and chooses "New entry"
**Then** the Add sheet opens with the receipt shown (amount and merchant are typed by the user; nothing is read from the
receipt) and on Save the receipt's `expenseId` is the new entry. **When** the user chooses "Attach to entry" **Then** a
picker lists entries of the last 30 days (amount, merchant, date) and the receipt is attached to the chosen one.
**When** the user deletes an unattached receipt and confirms **Then** its row and file are deleted.
Unhappy path: **Given** the Add sheet opened from a receipt **When** the user cancels **Then** the receipt stays
unattached in the list.
Unhappy path: **Given** an unattached receipt older than 90 days **Then** it is still kept (no automatic deletion) and the
list shows a "Not attached for 3 months" hint.

## UX states (UI stories only)
- empty: Receipts list "No receipts waiting. Share a receipt to Masroof or take a photo."
- loading: thumbnail placeholders while copying shared items.
- error: per-item reason lines (REQ-RCPT-022).
- offline: identical.
- RTL / dark: list and actions mirrored, strings in `values` + `values-ar`, 48 dp targets, TalkBack reads type and date.

## Contract impact
None.

## Data / migration impact
None (v9 `receipt.expenseId` null = unattached).

## Security / privacy / LLM-safety impact
Trust boundary L2: one new exported activity (share target) by necessity, accepting only `SEND`/`SEND_MULTIPLE` for
`image/*` and `application/pdf`, only `content://` URIs from other authorities, copying immediately and reading no other
extras; the camera `FileProvider` is not exported and its URI grant is only to the camera intent. No CAMERA, storage or
network permission is added. security-reviewer sign-off on the exported activity (ADR-0009 L2).

## Eval impact
None.

## Design notes (architect)
ADR-0009 D4 sources. The ML Kit document scanner is a separate optional story (STORY-197).

## Dependencies
STORY-174

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: share activity + camera ≈ 90, Receipts list + attach ≈ 100, tests ≈ 180)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D4), reuses STORY-128 share target |
| 2026-10-28 | 5 implement | android-engineer | IN_PROGRESS - camera (non-exported FileProvider), share target activity, Receipts list, Add with waiting receipt; verify pending |

## Known limitations
- Sharing to Masroof reopens MainActivity and discards a half-typed Add form.
- "Receipt only" opens the Receipts list by watching for a new unattached row; after process death the list is not opened automatically.
- Copying shows a spinner on the list (not per-item thumbnail placeholders).
| 2026-10-29 | 6+7 verify | qa-verifier + security-reviewer | PASS loop 3, security APPROVE (semgrep suppression scoped, deleted-entry receipts kept, deep-link extras dropped, copy timeout + quota, cold-start share vs sweep); merged |
