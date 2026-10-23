# ADR-0003: Spring Boot 3.5.16 for the skeleton although 3.5.x OSS support ended — upgrade decision needed before a public endpoint ships

- **Status:** accepted (orchestrator, 2026-10-23): keep 3.5.16 for STORY-002 only; the STORY-143 design moves the module to Spring Boot 4.1.x before the first endpoint ships
- **Date:** 2026-10-23
- **Story:** STORY-002 (affects STORY-143 onward)
- **Deciders:** architect (proposal), owner (decision)

## Context and problem statement
PROMPT §2 and `backend/CLAUDE.md` bind the backend to "Spring Boot 3.x ... unless current docs give a strong reason
not to — note any change in DECISIONS.md". When versions were checked for STORY-002 (2026-10-23), the Spring
generations API (`api.spring.io/projects/spring-boot/generations`) reported:

| Line | Initial release | OSS support ends | Commercial support ends |
|---|---|---|---|
| 3.5.x | 2025-05-31 | **2026-06-30 (ended)** | 2032-06-30 |
| 4.0.x | 2025-11-30 | 2026-12-31 | 2027-12-31 |
| 4.1.x | 2026-06-30 | 2027-07-31 | 2028-07-31 |

The last 3.5.x on Maven Central is 3.5.16 (published 2026-06-25). So every Spring Boot 3.x line is past free
support: future CVE fixes in Spring Framework 6.2 / Boot 3.5 will not be published as OSS releases. Masroof will
expose an internet-facing webhook and API (B1/B2 in `docs/security/THREAT_MODEL.md`).

## Decision drivers
- Binding spec text (3.x) and the orchestrator's instruction for STORY-002 (3.x).
- Security patch availability for an internet-facing service holding household financial data.
- Migration cost grows with every story; the walking skeleton is the cheapest moment to move.
- Ecosystem coupling of a 4.x move: Jackson 3 (`tools.jackson..` packages), Testcontainers 2 (package and artifact
  renames), JUnit 6, modular starters (`spring-boot-starter-flyway`), JSpecify throughout Spring — and jqwik's
  compatibility with JUnit 6 is unverified.

## Considered options
1. **Spring Boot 3.5.16** (last 3.x) — follows the binding spec; no OSS patches after 2026-06-30.
2. **Spring Boot 4.1.x** — OSS-supported to 2027-07-31; deviates from the spec; several library majors change at once.
3. **Spring Boot 4.0.x** — OSS-supported only to 2026-12-31; same migration as 4.1 for a shorter window. Dominated by option 2.

## Decision outcome
Chosen for STORY-002: **option 1, Spring Boot 3.5.16**, because the spec and the story instruction bind 3.x and
STORY-002 has no network surface (no web starter, no endpoint), so the unpatched-framework risk does not apply yet.
STORY-002 is kept portable: domain rule 1 bans Spring and Jackson packages, nullness uses JSpecify, and Boot use is
limited to core + JDBC + Flyway + Testcontainers. A later move to 4.1 changes about 10 lines (BOM version,
`spring-boot-starter-flyway`, Testcontainers 2 coordinates and imports, adding `tools.jackson..` to rule 1).

### Decision needed (owner, before STORY-143 is implemented)
STORY-143 adds the first HTTP endpoint. Recommendation: **approve option 2 (Spring Boot 4.1.x) as a short
`[STORY-143]` prerequisite or a dedicated story**, with this ADR updated to "superseded by ADR-xxxx", PROMPT §2's
"note any change in DECISIONS.md" satisfied by a DECISIONS line, and `backend/CLAUDE.md` updated from "3.x" to "4.x".
If the owner keeps 3.5, record "Accepted risk (ADR-0003)" in THREAT_MODEL §5 and track the Spring security advisories manually.

### Consequences
- Good: STORY-002 follows the binding spec and stays small; the upgrade decision is explicit, not silent.
- Bad: until decided, the backend sits on a framework line with no OSS security fixes.
- Bad: if the upgrade waits past STORY-143/006 (web + generated interfaces), it gets more expensive.

## Validation
- The decision is recorded (this ADR moves to accepted or superseded) before STORY-143 leaves stage 3.
- With option 2: `make check` green on 4.1.x, and ArchUnit rule 1 also bans `tools.jackson..`.

## Links
- ADR-0002 (backend build toolchain); PROMPT §2; `backend/CLAUDE.md`; https://spring.io/projects/spring-boot
