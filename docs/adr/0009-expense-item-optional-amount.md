# ADR-0009: Expense line items with an optional amount, and receipt attachments in MinIO

- Status: accepted (2026-10-27)
- Story: STORY-168

## Context
The owner asked to attach receipts and a list of purchased items to an entry. The contract has declared
`Expense.items` (schema `ExpenseItem`, `required: [name, total]`) since v0, but the backend has always answered
`items: []` and rejected any non-empty `items` on create and patch with 400 "line items are not supported yet". No
client, generated or hand-written, has ever received or sent an item.

Users want to jot items down without prices ("bread, milk x2"), so the amount must be optional, and the name needs a
lower bound so a blank row is not stored.

Attachments need object storage. The production host is a GCP e2-micro (1 GB RAM, docs/HOSTING.md). MinIO already
runs there (measured 32 MiB, capped at 128 MiB, `GOMEMLIMIT=96MiB`) and is already in the nightly encrypted backup,
but the backend has never used it.

## Decision
1. `ExpenseItem.total` becomes optional and `ExpenseItem.name` gets `minLength: 1`. oasdiff reports this as breaking
   (`response-property-became-optional`, `request-property-min-length-set`) on every path that embeds `Expense` or
   `ExpenseItem`:
   - `GET /api/expenses`
   - `POST /api/expenses`
   - `GET /api/expenses/{expenseId}`
   - `PATCH /api/expenses/{expenseId}`
   - `POST /api/expenses/{expenseId}/merge`
   - `POST /api/expenses/{expenseId}/split`
   - `POST /api/expenses/{expenseId}/restore`
   - `GET /api/review`
   - `POST /api/review/{expenseId}/resolve`
   - `POST /api/import`
   - `POST /api/parse/dry-run`
   `unitPrice` and `categoryId` stay in the schema (removing them would also be breaking) but the server rejects them
   until a story needs them.
2. Attachments are stored in the existing MinIO through a small S3 (SigV4) client written on `java.net.http`, with no
   new dependency, so the backend image, heap budget and Gradle verification metadata do not change. Files are capped
   at 8 MiB and 5 per expense, sniffed by magic bytes (JPEG, PNG, PDF only), and image metadata segments (EXIF, XMP,
   IPTC, comments, PNG text chunks) are removed before storage, by a byte-level filter rather than a decode and
   re-encode, because decoding a 12 MP photo needs about 48 MB of heap on a 224 MB heap. Object keys contain only ids.
   Postgres `bytea` was rejected: Postgres is capped at 192 MiB with 32 MB shared buffers, and MinIO is already
   running and backed up.

## Consequences
No deployed client is affected: no client ever saw a non-empty `items` array. The web and Android clients are
regenerated from this contract in the same change. After this release, further changes to `ExpenseItem` must be
additive.

Without S3 credentials the attachment endpoints answer 503 and the rest of the API works. PDF metadata (author,
producer) is not stripped. PDFs are served only as downloads with `nosniff`.
