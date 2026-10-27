# EPIC-17 - Local-only Android app

Owner decision 2026-10-27 (in chat): "only local, captures only the messages and we can attach receipts". Masroof
becomes **one Android app with Room as the only copy of the data**. Design: [ADR-0009](../../adr/0009-local-only-android.md)
(D1 superseded PROMPT sections, D2 analytics port, D3 Room v8 -> v9 -> v10, D4 receipts, D5 backup, D6 one-time import,
D7 frozen modules and server retirement). Every story here cites the ADR decision it follows.

## What the household gets
- Bank SMS and bank-app notifications become entries on the phone (on-device parser, review list, template learning,
  SMS-vs-notification pairing, Undo - all already on `main`), plus a 90-day SMS backfill and manual entry.
- Receipts (photo, camera, PDF, share to Masroof) attached to entries or kept first and attached later; viewed in the
  app; deleted with the entry. Nothing reads receipts automatically in v1.
- Home, Insights (overview, categories, merchants, calendar, trends) and budgets computed on the phone, offline.
- An encrypted backup file to Drive or anywhere via the system picker, restore on a new phone, CSV export.
- History from the v0.1.x server imported once; then the app forgets the server.
- Paid-by as an optional member label (no splits, no settlement). Member 2's iPhone does not use the app.

## What is gone and why
WhatsApp, the iPhone path (PWA, Shortcuts, web push), the web app, the backend API, login and sync, the LLM parser and
eval-driven AI features, FCM, server hosting. Reason: zero running cost and operations, no expense data leaving the phone,
and no multi-user problem left to solve (ADR-0009 "Decision drivers"). All open stories of EPIC-01..16 are DESCOPED
(2026-10-27); `backend/`, `web/`, `infra/` and `contracts/` are frozen until STORY-198.

## Story map (dependency order)
| # | story | risk | size | depends on | reuses |
|---|---|---|---|---|---|
| 1 | STORY-168 Room v9 expand migration + safety copy + seeds | high | M | - | Room v8 (STORY-065/067) |
| 2 | STORY-169 Manual entries local, no sign-in, Erase all data | high | M | 168 | STORY-036, STORY-038 |
| 3 | STORY-170 Confirm / edit / delete with Undo, paid-by label | high | M | 169 | STORY-166 |
| 4 | STORY-171 Captures write local entries, capture setting | high | M | 169 | STORY-064..069, 046 |
| 5 | STORY-172 Review list saves locally, queued captures converted, SMS-001 guard | high | M | 171 | STORY-067 |
| 6 | STORY-173 Manual vs capture "Possible duplicate" card | high | M | 172 | STORY-087/089 |
| 7 | STORY-174 Attach a receipt photo/PDF, in-app viewer | high | M | 170 | STORY-061 |
| 8 | STORY-175 Receipt first: camera, share target, Receipts list | high | M | 174 | STORY-128 |
| 9 | STORY-176 Period + projection maths port (Kotlin) | high | M | 168 | backend `domain.analytics` |
| 10 | STORY-177 Golden analytics parity file (backend test-only) | high | M | 176 | STORY-093..098 |
| 11 | STORY-178 Home + Insights overview/categories on the phone, month start day | high | M | 171, 176, 177 | STORY-109 |
| 12 | STORY-179 Insights merchants/heatmap/trends on the phone | high | M | 178 | STORY-111 |
| 13 | STORY-180 Budget progress/rollover port | high | M | 178 | STORY-092 |
| 14 | STORY-181 Budgets stored and managed on the phone | high | M | 180 | STORY-112 |
| 15 | STORY-182 One-time server import, forget credentials | high | M | 173, 181 | - |
| 16 | STORY-183 Encrypted backup file | high | M | 174, 181 | STORY-124 intent |
| 17 | STORY-184 Restore from backup | high | M | 183 | - |
| 18 | STORY-185 CSV export | high | S | 170 | STORY-125 |
| - | STORY-186 90-day SMS backfill | high | M | 172 | STORY-068 |
| - | STORY-187 First-run guide without sign-in | low | M | 186 | STORY-070 |
| stub | STORY-188 Scheduled automatic backup (first after MVP) | high | M | 184 | - |
| stub | STORY-189 Budget alerts as local notifications | low | S | 181 | STORY-102 |
| stub | STORY-190 Recurring detection | high | M | 179 | STORY-103 |
| stub | STORY-191 Biometric app lock | low | S | 169 | STORY-129 |
| stub | STORY-192 Widget + quick-add tile | low | M | 178, 181 | STORY-127/128 |
| stub | STORY-193 CSV / Google Sheets import | high | M | 173, 185 | STORY-126 |
| stub | STORY-194 Local Habits insights | low | M | 179 | EPIC-16 |
| stub | STORY-195 Savings goals (owner idea, scope to confirm) | high | M | 181 | - |
| stub | STORY-196 Manage categories, accounts, members | low | M | 170 | STORY-040/013/014/015 |
| stub | STORY-197 Document scanner for receipts | low | S | 175 | STORY-061 |
| stub | STORY-198 Retire the server (owner checkpoint OWNER-015) | high | M | 182, 184 | - |

Parallel lanes after STORY-169: receipts (174 -> 175), analytics (176 -> 177 -> 178 -> 179 -> 180 -> 181), backup
(183 after 174 + 181). Captures (171 -> 172 -> 173) run beside them. Cap 4 concurrent implementers.

## MVP line
**MVP = STORY-168 .. STORY-185** (foundation, captures local, receipts, insights, budgets, import, backup/restore, CSV).
Backfill (186) and the first-run guide (187) are READY and come right after; STORY-188 (scheduled backup) is the first
stub to specify after the MVP.

**Release rule:** between STORY-169 and the end of the MVP, `main` mixes local and server-backed screens (e.g. Insights
still reads the server while entries are local). No APK built from `main` in that window is installed on the owner's
phone; the first local-only release is cut when STORY-168..185 are DONE, and its first run performs the import (182).

## Shared rules for every EPIC-17 story (ADR-0009 D1 invariants)
- Money is `Long` minor units + ISO-4217 code in Room, backup JSON and CSV (decimal strings built from minor units);
  never `Double`/`Float`.
- Business dates in `Africa/Cairo` via `CairoTime`, stored as UTC epoch millis; date-only entries at Cairo noon with
  `timeKnown = 0`; month start day 1..28 is a phone setting.
- No SMS/notification text, account number or receipt content in logs, CSV or backup; review text only in `review_item`
  (30-day purge); OTPs never stored; message content is untrusted and rendered as plain text.
- Every Room version bump: hand-written `Migration(n, n+1)`, exported schema JSON, `MigrationTestHelper` test from every
  shipped version, no new destructive fallback; shipped migrations and schema JSONs immutable.
- Nothing new may call the network; only STORY-182 uses the frozen generated client.
- Local data is never wiped by a session event; only "Erase all data".
- Tests carry REQ ids in their names (`fun \`REQ-SMS-095 ...\`()`).

## Open design points (architect)
1. **Budgets table.** ADR-0009 lists budgets as "later" and D3 has no `budget` table, but the owner's MVP includes
   budgets (STORY-180/181) because the shipped budgets screen would otherwise show server numbers that miss local
   entries. Fold `budget` into `MIGRATION_8_9` if STORY-168 is not merged yet; otherwise add an additive version (and the
   v10 contract step shifts by one).
2. **Backup and import lists.** STORY-183 adds `data/budgets.jsonl` to the D5 file list; STORY-182 adds budgets and the
   household month start day / ATM rule to the D6 import list.
3. **"Keep both" memory** for STORY-173 may need storage; placement is the architect's.

## Decisions to record (orchestrator copies into docs/DECISIONS.md)
- 2026-10-27 - [AND] EPIC-17 MVP is STORY-168..185 and includes local budgets (owner list), which ADR-0009 had put after the MVP; budgets table placement is an architect point (EPIC-17 README).
- 2026-10-27 - [AND] Release rule: no APK from `main` is installed on the owner's phone between STORY-169 and the end of the EPIC-17 MVP; the first local-only release runs the one-time import.
- 2026-10-27 - [AND] Leftover `outbox`/`parsed_outbox` rows are converted to local entries at app start by STORY-169/172 (not only by the import); a converted row that may already be on the server becomes a merge suggestion during the import (REQ-DEDUP-066).
- 2026-10-27 - [DEDUP] Manual-vs-capture "Possible duplicate" rule: same currency and amount, one manual and one capture-only entry, same Cairo day when the manual entry is untimed, else at most 2 hours apart; never merged automatically (STORY-173).
- 2026-10-27 - [AN] "Count ATM withdrawals as spending" becomes a phone setting, default off, overwritten by the household's server value during the import (STORY-178/182).
- 2026-10-27 - [SDLC] Every open story of EPIC-01..16 is DESCOPED (superseded by ADR-0009); shipped-but-unclosed stories (STORY-033, 046, 064..067, 069, 092..098, 109, 111, 112, 166 ...) are DESCOPED too and their surviving behaviour is re-specified in the EPIC-17 story that reuses it; no open android story was kept because each depended on descoped server stories. EPIC-00 STORY-146/148 stay.
