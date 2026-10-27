---
id: STORY-168
epic: EPIC-04
title: Attach a receipt photo or PDF and a list of purchased items to a transaction
phase: 2
status: IN_PROGRESS
risk_tier: high
size: L
modules: [contracts, backend, android, infra]
depends_on: [STORY-166, STORY-022]
reqs: [REQ-RCPT-020, REQ-RCPT-021, REQ-RCPT-022, REQ-RCPT-023, REQ-RCPT-024, REQ-RCPT-025, REQ-RCPT-026, REQ-RCPT-027, REQ-RCPT-030, REQ-RCPT-031, REQ-RCPT-032, REQ-AND-130, REQ-AND-131, REQ-AND-132, REQ-AND-133, REQ-AND-134, REQ-AND-135]
implements: ["PROMPT.md §11 Key screens (Transaction detail: receipt, items)", "owner request 2026-10-27"]
owner_agent: backend-engineer
---

# STORY-168 - Receipt attachments and purchased items on a transaction

## Goal
Owner request (2026-10-27): "I'd love to have an option to attach receipts, or even a list of purchased items to the
entry." On the Android edit screen (STORY-166) the user attaches a receipt photo (camera or gallery) or a PDF, views
and removes it, and keeps a simple list of purchased items (name, optional quantity, optional amount). Stored on the
backend, household-scoped. Receipt OCR / AI parsing into items is out of scope (follow-up, STORY-053 path).

Risk tier high: uploads (untrusted files) and money (item amounts).

## Requirements and acceptance criteria

### Backend - attachments (`/api/expenses/{expenseId}/attachments`, ADR-0009)

### REQ-RCPT-020 - List attachments
**Given** an expense of the caller's household with attachments **When** `GET .../attachments` **Then** 200 with
`items` oldest first (id, expenseId, contentType, sizeBytes, uploadedByMemberId, createdAt), no file name.
Unhappy: unknown expense, another household's expense or a soft-deleted expense -> 404 (same body as unknown).

### REQ-RCPT-021 - Upload is sniffed, size- and count-limited
**Given** a multipart `file` **When** `POST .../attachments` **Then** the type is decided from the leading bytes only
(JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A`, PDF `%PDF-`); the declared part content type and file name are
ignored and the name is not stored. 201 returns the `Attachment`.
Unhappy: GIF/HEIC/WEBP/HTML/SVG/ZIP or a text file renamed `.jpg` -> 415; empty file or no `file` part -> 400; more
than 8 MiB (8388608 bytes) -> 413 without storing anything; a 6th attachment on one expense -> 409; unknown or other
household's expense -> 404 and nothing stored.

### REQ-RCPT-022 - Image metadata is removed before storage
**Given** a JPEG with APP1 Exif (incl. GPS), APP1 XMP, APP13 IPTC and COM segments **When** uploaded **Then** the
stored and served bytes contain none of those segments (no `Exif\0\0`, no XMP namespace, no GPS bytes), still start
with SOI, keep the image data (SOF/DQT/DHT/SOS..EOI) and APP0 JFIF / APP2 ICC, and decode as the same image.
**Given** a PNG with `eXIf`, `tEXt`, `zTXt`, `iTXt` or `tIME` chunks **Then** those chunks are removed and the PNG
stays valid (signature, IHDR, IDAT, IEND, CRCs untouched). `sizeBytes` is the stripped size.
Unhappy: a truncated or malformed JPEG/PNG segment structure -> 415 (never stored half-stripped).

### REQ-RCPT-023 - Storage in MinIO (S3), consistent with the database
**Given** S3 is configured (`S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET` default `masroof-media`)
**When** a file is uploaded **Then** the object is PUT under `households/{householdId}/attachments/{attachmentId}`
(ids only) and then the row is inserted; if the insert fails the object is deleted; the bucket is created on first
use if missing. Unhappy: S3 not configured or unreachable -> 503 problem, no row stored; the rest of the API is
unaffected and the backend still starts without S3 settings.

### REQ-RCPT-024 - Download
**Given** an attachment of the caller's household **When** `GET .../attachments/{attachmentId}` **Then** 200 with
the stored bytes, `Content-Type` from the row, `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`,
`Cache-Control: private, no-store`. Unhappy: attachment of another expense (even same household, mismatched path),
another household, or deleted -> 404.

### REQ-RCPT-025 - Remove
**Given** an attachment **When** `DELETE .../attachments/{attachmentId}` **Then** 204, the row is gone and the object
deleted (an object-delete failure is logged at WARN with ids only and does not fail the call). A second DELETE -> 404.

### REQ-RCPT-026 - Authorization (IDOR)
**Given** no or an invalid token **Then** all four operations -> 401. **Given** a member of household B **When** they
list, upload, download or delete on household A's expense or attachment ids **Then** 404 and nothing changes.

### REQ-RCPT-027 - Deployment wiring
**Given** `infra/docker-compose.yml` **Then** the backend receives `S3_ENDPOINT=http://minio:9000`, `S3_ACCESS_KEY`,
`S3_SECRET_KEY` (from the same `.env` values MinIO uses) and `S3_BUCKET`; the backend does not hard-depend on MinIO
health to start. Spring multipart limits match 8 MiB; the prod memory budget is unchanged (docs/HOSTING.md note).

### Backend - purchased items (existing `Expense.items`, ADR-0009)

### REQ-RCPT-030 - Items are stored and returned
**Given** `POST /api/expenses` or `PATCH /api/expenses/{id}` with `items` **Then** the list replaces the stored one
(server-assigned ids, input order kept) and `GET` one, `GET` list and the PATCH response return it in that order.
**Given** a PATCH without `items` **Then** stored items are unchanged; `items: []` clears them.

### REQ-RCPT-031 - Item validation
`name` trimmed, 1-200 code points, no NUL; `qty` optional, > 0, <= 999999, at most 3 decimals; `total` optional,
`amountMinor` 0..the expense amount limit, currency equal to the expense currency; `unitPrice` and `categoryId` must
be absent; at most 100 items. Unhappy: any violation -> 400 naming the field (`items[2].total`), nothing changes.

### REQ-RCPT-032 - Money stays integral
Item amounts are `BIGINT` minor units + `CHAR(3)` in the DB and `long` + currency in code (never `double`/`float`);
`qty` is `NUMERIC(12,3)` / `BigDecimal` (not money). A property test round-trips random valid item lists through
validation unchanged.

### Android (edit screen from STORY-166)

### REQ-AND-130 - Attachments and items load with the screen
**Given** a synced row opened for editing **When** the screen opens **Then** the form shows from the cache at once and
the attachments list and items are fetched from the server; while loading those sections show a spinner; offline or
failure shows "Couldn't load attachments" with Retry, and the rest of the screen still works. Items are never sent
unless they were loaded and changed.

### REQ-AND-131 - Attach a photo
**Given** the edit screen **When** the user taps "Attach receipt" and picks Camera (system camera via
`TakePicture` + FileProvider, no CAMERA permission) or Gallery (system photo picker, no storage permission) **Then**
the image is decoded with EXIF orientation applied, scaled so the long edge is at most 2048 px, re-encoded as JPEG
(quality 85, which writes no EXIF) and uploaded; the new attachment appears in the list. Temp files live in the app's
cache dir and are deleted after upload. Unhappy: cancel -> nothing; upload failure -> message, nothing added;
413/415/409 -> specific message ("too large", "only photos and PDFs", "at most 5").

### REQ-AND-132 - Attach a PDF
**Given** "Attach receipt" -> PDF (`OpenDocument` with `application/pdf`) **Then** the file is uploaded as is when at
most 8 MiB; larger files are refused on the phone with "too large" before any upload.

### REQ-AND-133 - View an attachment
**Given** an attachment row **When** tapped **Then** the bytes are downloaded with the session's auth and shown inside
the app (image full screen; PDF pages rendered with `PdfRenderer`), never handed to another app; files are kept only
in the app cache and deleted when the viewer closes; the window keeps the app's secure-window setting.

### REQ-AND-134 - Remove an attachment
**Given** an attachment row **When** the user taps Remove and confirms **Then** `DELETE` is sent once and the row
disappears; Cancel sends nothing; 404 counts as removed.

### REQ-AND-135 - Edit the items list
**Given** the Items section **When** the user adds, edits or removes rows (name, optional quantity, optional amount in
the expense currency) and taps Save **Then** the full list is sent as `items` in the same PATCH (only if it changed),
amounts parsed to minor units without `Double`, blank rows dropped; an "Items total" is shown next to the
transaction amount (informational, no enforcement). A 400 naming `items[n]...` marks that row. Strings in English and
Egyptian Arabic, RTL, 48dp targets.

## UX states
loading (sections spin; form usable) - empty ("No receipt attached", "No items") - error/offline (inline message +
Retry, edits kept) - uploading (progress, buttons disabled) - RTL - dark.

## Contract impact
New `/api/expenses/{expenseId}/attachments` (GET, POST multipart) and `/{attachmentId}` (GET binary, DELETE);
schemas `Attachment`, `AttachmentList`, `AttachmentUploadForm`, parameter `AttachmentId`. `ExpenseItem.total`
optional, `name.minLength 1`: breaking per oasdiff, accepted in ADR-0009 (no client ever saw a non-empty `items`).

## Data / migration impact
New `V11__expense_attachment_item.sql`: tables `expense_attachment` and `expense_item` (FK to expense, household id
for scoping, CHECKs on sizes, type, money). Additive only.

## Security / privacy / LLM-safety impact
Untrusted files: magic-byte sniffing, 8 MiB cap, metadata stripping, ids-only object keys, served as downloads with
nosniff. Household scoping and IDOR tests on every operation. No file content, item names or amounts logged.
Residual: PDF document metadata is not stripped (ADR-0009). No LLM involvement.

## Eval impact
None (no parsing).

## Out of scope / follow-ups
- Receipt OCR / AI parsing into items (STORY-053 / REQ-RCPT-010..014).
- Web UI for attachments and items.
- Offline queueing of attachment uploads through the outbox (online-only for now, with a clear message).

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | spec + contract | orchestrator | contract + ADR-0009 written, speed mode |
