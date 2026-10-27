# Receipts UI kept for STORY-174 (work in progress, not for merge as is)

This branch keeps the Android receipt UI built for closed PR #73 (server-backed receipts, superseded by the
local-only decision ADR-0009 `0009-local-only-android.md`, D4/D7). The server implementation is removed, so the
branch does not compile until a local `ReceiptRemote` exists.

What is here and reusable:
- `ui/edit/ReceiptSection.kt`, `ReceiptViewer.kt`, `ItemsSection.kt`: attachments section, in-app viewer (images, PDF
  pages via `PdfRenderer`, secure window), purchased-items editor with an "Items total".
- `ui/viewmodel/AttachmentsViewModel.kt`, the items parts of `EditExpenseViewModel.kt`: state machines (load, upload,
  error mapping, delete with confirm, viewer temp-file cleanup, items diff and row errors).
- `data/receipts/AndroidReceiptFiles.kt`: EXIF-aware bounded decode, scale to 2048 px, JPEG q85 re-encode (no EXIF),
  PDF copy with an 8 MiB cap. `ItemParsing.kt`: quantity and amount parsing without `Double`.
- Manifest: non-exported `FileProvider` limited to `cache/receipts/`; no CAMERA or storage permission.
- Strings in English and Egyptian Arabic; unit tests `ReceiptAttachmentsTest`, `ReceiptItemsTest`, `ReceiptFakes`.

What STORY-174 still needs:
- A local `ReceiptRemote` (or a renamed `ReceiptStore`) over `filesDir/receipts/<uuid>.<ext>` + the Room `receipt` table
  (ADR-0009 D3/D4), bound in `di/AppModule.kt` (the binding was removed with `ApiReceiptRemote`).
- The 409/413/415/401 server error mapping in `AttachmentsViewModel` becomes local validation results.
- Purchased items need local storage too; the owner asked for them (2026-10-27) although ADR-0009 D1 §7 defers them.
