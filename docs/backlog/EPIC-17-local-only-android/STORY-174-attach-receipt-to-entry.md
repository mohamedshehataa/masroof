---
id: STORY-174
epic: EPIC-17
title: Attach a receipt photo or PDF to an entry and view it in the app
phase: 3
status: DONE
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-170]
reqs: [REQ-RCPT-015, REQ-RCPT-016, REQ-RCPT-017, REQ-RCPT-018, REQ-RCPT-019, REQ-RCPT-020]
implements: ["ADR-0009 D4 (receipt storage, sources Photo Picker + OpenDocument, limits, EXIF stripping, in-app viewing, deletion)", "PROMPT.md §1 channel 6 (receipt upload; no automatic reading in v1 per ADR-0009 D1 §6)"]
owner_agent: android-engineer
---

# STORY-174 - Attach a receipt photo or PDF to an entry and view it in the app

## Goal
From an entry's edit screen the user adds a receipt from the photo library or a PDF from Files, sees it inside the app,
and can remove it; receipts are deleted with their entry. Files are checked by their real type, size-capped, and
images lose their EXIF/GPS. Nothing reads the receipt automatically in v1 (ADR-0009 D1 §6, D4). The camera, share
target and receipt-first flow are STORY-175.

## Requirements and acceptance criteria
### REQ-RCPT-015 - Attach an image or a PDF to an entry
**Given** an entry's edit screen **When** the user taps "Add receipt", picks "Photo" and chooses a 3 MB JPEG in the
system Photo Picker (no storage permission is requested) **Then** a file `filesDir/receipts/<uuid>.jpg` exists, one
`receipt` row has `expenseId` = the entry, `mimeType = image/jpeg`, `sizeBytes` and `sha256` of the stored file,
`pageCount = 1`, and the edit screen shows a thumbnail. **When** the user picks "PDF" and a 2-page PDF in the system
file picker (`OpenDocument`, `application/pdf`) **Then** the file is stored as `<uuid>.pdf` with `pageCount = 2`.
An entry can hold several receipts (shown in the order added).
Unhappy path: **Given** the user cancels the picker **Then** nothing is stored and no error is shown.
Unhappy path: **Given** the copy fails half-way (provider throws, storage full) **Then** no row exists, the partial file
is deleted and "Couldn't add the receipt" is shown.

### REQ-RCPT-016 - Only real images and PDFs within the limits are accepted
**Given** a picked file **When** it is added **Then** its type is decided from its first bytes (JPEG, PNG, WebP, HEIF or
PDF), never from the provider's MIME type or the file name. Limits: images at most 20 MB and 50 megapixels (checked from
the header before full decoding), PDFs at most 10 MB and 30 pages; the copy stops at the byte cap.
Unhappy path: **Given** a text file renamed `receipt.jpg` with MIME `image/jpeg`, a ZIP renamed `.pdf`, or an SVG
**Then** it is refused with "This file type is not supported" and nothing is stored.
Unhappy path: **Given** a 25 MB JPEG, a 12,000 x 6,000 px PNG (72 MP) that is small on disk, an 11 MB PDF, or a 31-page
PDF **Then** it is refused with "This file is too large" (or "too many pages") and nothing remains on disk.
Unhappy path: **Given** a PDF that `PdfRenderer` cannot open (corrupt or password-protected) **Then** it is refused with
"This PDF can't be opened" and nothing remains.

### REQ-RCPT-017 - Images lose their location and EXIF
**Given** a JPEG with EXIF GPS coordinates and orientation 6 (rotated 90°) **When** it is attached **Then** the stored file
is a JPEG (quality 85, long edge at most 2560 px) with no EXIF block at all (no GPS, no camera model), displayed
upright. **Given** a HEIF or PNG **Then** it is stored as JPEG too. **Given** a PDF **Then** it is stored byte for byte
(its metadata is kept, as documented in ADR-0009 D4).
Unhappy path: **Given** an image whose pixels cannot be decoded (truncated JPEG) **Then** it is refused with "This image
can't be opened" and nothing remains.

### REQ-RCPT-018 - Receipts open inside the app only
**Given** an entry with a receipt **When** the user taps the thumbnail **Then** an in-app viewer shows the image (pinch to
zoom) or the PDF pages (`PdfRenderer`, page indicator "1 / 2"), and no other app is started: no `ACTION_VIEW` intent and
no `FileProvider` URI grant is created for a receipt.
Unhappy path: **Given** the file of a row is missing on disk **When** the viewer opens **Then** it shows "This receipt
file is missing" with a Remove button instead of crashing.

### REQ-RCPT-019 - Receipts are deleted with their entry and orphans are cleaned up
**Given** a receipt **When** the user removes it from the edit screen and confirms **Then** its row and then its file are
deleted. **Given** an entry with receipts that is soft deleted **Then** the receipts stay (Undo brings them back);
**When** the entry is purged after 30 days (REQ-EXP-046) **Then** its receipt rows and files are deleted.
**Given** a file in `filesDir/receipts` with no row (crash between copy and insert) **When** the app starts **Then** the
startup sweeper deletes it; a row whose file is missing is kept and shown as missing (REQ-RCPT-018).
**Given** Settings **Then** "Receipts: N files, X MB" shows the total size.
Unhappy path: **Given** deleting the file fails after the row was removed **Then** the next startup sweep removes it.

### REQ-RCPT-020 - The same file attached twice is detected
**Given** a receipt already stored with SHA-256 `H` **When** the user attaches a file with the same content (to the same
or another entry) **Then** no second copy is stored and the message says "This receipt is already attached to <entry
date, amount>" with an "Open" action.
Unhappy path: **Given** the same photo re-saved by the gallery with a different EXIF date **Then** after re-encoding the
content may differ; it is stored as a new receipt (no perceptual matching in v1, ADR-0009 D1 §7).

## UX states (UI stories only)
- empty: edit screen shows "Add receipt" with no thumbnails.
- loading: a progress indicator on the new thumbnail slot while copying/re-encoding (large images may take seconds);
  Save of the entry is not blocked.
- error: the messages above as a snackbar; nothing half-added is shown.
- offline: identical.
- RTL / dark: thumbnails row mirrors; viewer controls mirrored; page indicator uses the digit setting; strings in
  `values` + `values-ar`; TalkBack: "Receipt 1 of 2, photo".

## Contract impact
None.

## Data / migration impact
Uses the v9 `receipt` table (STORY-168). New app-private directory `filesDir/receipts`. No schema change.

## Security / privacy / LLM-safety impact
Trust boundary L2 (pickers -> receipt store): type by magic bytes, byte caps, pixel and page caps before decoding
(pixel/decompression bombs), generated file names (no path traversal, no PII in names), EXIF/GPS stripped by
re-encoding, in-app viewing only (no URI grants). Receipt content is never logged. `allowBackup="false"` stays.
security-reviewer sign-off (ADR-0009 L2).

## Eval impact
None (no automatic reading of receipts in v1).

## Design notes (architect)
ADR-0009 D4 is the design (sources, limits, re-encode parameters, viewing, deletion, sweeper).
Reusable UI from closed PR #73 is on branch `wip/receipts-ui-for-story-174` (picker, camera, PDF, in-app viewer, items editor; server wiring removed, see `android/RECEIPTS-UI-WIP.md` there). Owner want (2026-10-27): also a list of purchased items per entry; line items stay deferred here (ADR-0009 D1 §7), so this flags the want, not a scope change.

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
- [x] Size ≤ 400 changed lines (estimate M: store/validate/re-encode ≈ 140, viewer + edit-screen UI ≈ 90, tests ≈ 160 with small fixture files; split viewer out if design exceeds 400)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D4), reuses STORY-061 receipt intent |
| 2026-10-28 | 5 implement | android-engineer | IN_PROGRESS - ReceiptStore, edit-screen section, in-app viewer, Settings total (speed mode) |
| 2026-10-28 | 6+7 verify | qa-verifier + security-reviewer | PASS loop 3, security APPROVE (fixed camera-JPEG trailers, PDF page size caps, EXIF parser bounds, decode memory, viewer close); merged |
