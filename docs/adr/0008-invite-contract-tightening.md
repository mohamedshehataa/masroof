# ADR-0008: Tighten the invite request schemas before the endpoints ship

- Status: accepted (2026-10-26)
- Story: STORY-011

## Context
`POST /auth/invite` and `POST /auth/invite/accept` were declared in contract v0 but answered `501` until STORY-011, so no client, generated or hand-written, has ever sent them successfully. Security review of the implementation found three schema gaps that are only cheap to fix before the first client exists:

- BCrypt reads at most 72 bytes and Spring's encoder throws beyond that, so the accepted password length has to be bounded, and a 12-character minimum is the policy used for the bootstrap admin (the contract said 8).
- The invite `code` is a bearer secret and must be bounded.
- Invites can only create `MEMBER` accounts; an `ADMIN` enum value on the request invites privilege-escalation mistakes.

## Decision
Make these request-schema changes, which oasdiff reports as breaking on the two paths below:

- `POST /auth/invite`: `InviteRequest.role` is `enum: [MEMBER]` (the `ADMIN` value is removed).
- `POST /auth/invite/accept`: `password` `minLength` 8 -> 12 and `maxLength` 256 -> 72 (UTF-8 byte limit enforced by the server on top); `code` gets `maxLength: 128`.

## Consequences
No deployed client is affected (the operations never worked). The web and Android clients are generated from this contract in the same change. After the first release, further tightening of these schemas needs a new `/v2` or an additive path.
