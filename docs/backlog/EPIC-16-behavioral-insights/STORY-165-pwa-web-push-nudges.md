---
id: STORY-165
epic: EPIC-16
title: "Nudges reach the iPhone PWA by web push"
phase: 8
status: DRAFT
size: S
modules: [backend]
depends_on: [STORY-160, STORY-114, STORY-162]
reqs: [REQ-AN-139]
implements: ["Owner request 2026-10-25 (gentle nudges via push for both members)", "PROMPT.md §11 PWA-specific (web push, iOS 16.4+)", "PROMPT.md §5 (Member 2 on iPhone)"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-165 — Nudges reach the iPhone PWA by web push

## Goal
Member 2 on iPhone gets habit nudges as web push notifications from the installed PWA, through the web-push sender
of STORY-114, with the same preferences, quiet hours and privacy rules as Android push (owner request 2026-10-25;
PROMPT §11 PWA-specific, §5).

## Requirements and acceptance criteria
### REQ-AN-139 — Web push channel for nudges
The push channel of STORY-160 REQ-AN-127 also sends to the member's web-push subscriptions (STORY-114): notification
title and body rendered server-side in the member's language and digits, `data {nudgeId, memberId, url: "/?nudge=<id>"}`;
clicking opens the PWA Home with the nudge card and marks it seen (STORY-162). One delivery row per (nudge, channel
`WEB_PUSH`), so FCM and web push are tracked separately.
**Given** Member 2 has `channels.push = true`, one web-push subscription and no Android device **When** her `me`
nudge is delivered outside quiet hours **Then** one web push is sent, the delivery row is `SENT`, and the push channel
is no longer `SKIPPED_NO_DEVICE`.
Unhappy paths:
- **Given** `channels.push = true` but no subscription and no Android device **Then** push stays `SKIPPED_NO_DEVICE`.
- **Given** the push service returns `404`/`410` for a subscription **Then** that subscription is deleted (STORY-114
  rule) and the channel is `FAILED` only if no other subscription succeeded.
- **Given** `429` or `5xx` **Then** retried at most 3 times within 1 hour (REQ-AN-128), never sent twice.
- **Given** quiet hours or an `EXPIRED` nudge **Then** nothing is sent (same rules as REQ-AN-128).
- **Given** a subscription belonging to Mohamed **When** Member 2's nudge is delivered **Then** it is never used
  (subscriptions are matched by member id; test with two members on one browser profile switching accounts).

## UX states (UI stories only)
N/A (service worker notification display is STORY-114's; Home card is STORY-162's).

## Contract impact
None beyond STORY-114's subscription endpoints; delivery channel enum gains `WEB_PUSH` (internal).

## Data / migration impact
None (uses STORY-160's `nudge_delivery` with a new channel value; if the channel is a DB enum, a new migration adds it).

## Security / privacy / LLM-safety impact
Payload contains only the rendered sentence and ids; VAPID keys in `.env`. Same per-member privacy rules as
STORY-160. No LLM.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-160 (delivery worker), STORY-114 (web-push sender, Phase 8), STORY-162 (settings toggle and Home card).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 40 production + ≈ 90 tests)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
