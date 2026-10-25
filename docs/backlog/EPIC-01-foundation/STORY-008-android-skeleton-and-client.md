---
id: STORY-008
epic: EPIC-01
title: Android Gradle project skeleton with fast gate and generated Kotlin client
phase: 1
status: IN_PROGRESS
size: M
modules: [contracts, android]
depends_on: [STORY-002]
reqs: [REQ-OPS-054, REQ-OPS-055]
implements: ["PROMPT.md §2 (Android stack)", "PROMPT.md §9 (generated Kotlin client)", "PROMPT.md S9 (Android gates)"]
risk_tier: low
owner_agent: android-engineer
---

# STORY-008 — Android project skeleton with fast gate and generated Kotlin client

## Goal
An `android/` Gradle project builds a debug APK, runs the S9 Android gates via `./gradlew checkFast`, and compiles a Kotlin client generated from the contract, so Phase 2 Android screens start on a gated module.

## Requirements and acceptance criteria
### REQ-OPS-054 — Android module with fast gate
**Given** `android/` **When** `./gradlew checkFast` runs **Then** it compiles Kotlin with `allWarningsAsErrors`, runs detekt (with the custom `NoFloatingPointMoney` rule), ktlint, Android Lint (`warningsAsErrors`, `abortOnError`) and JVM unit tests, exiting 0 in < 3 min; `make check-fast MODULE=android` dispatches to it; `./gradlew assembleDebug` produces `app-debug.apk` with `minSdk 26`, Compose BOM, Material 3, Hilt, Room, Retrofit/OkHttp, WorkManager, DataStore, Coil dependencies declared (version catalog) and LeakCanary + StrictMode in debug.
Unhappy path: **Given** a Kotlin class with `val amount: Double` in a package named `money`/`domain` **When** detekt runs **Then** the custom rule fails naming the file.

### REQ-OPS-055 — Generated Kotlin client
**Given** the build **When** `./gradlew generateApi` runs (wired before `compileDebugKotlin`) **Then** a Retrofit/kotlinx-serialization client and models are generated under `android/app/build/generated/openapi` for every contract operation, `Money.amountMinor` is `Long`, `date-time` is `kotlinx.datetime.Instant`, and a unit test deserialises the `Health` example from the contract.
Unhappy path: **Given** the contract is changed to make `Health.version` optional **Then** the unit test fails to compile or fails (type is now nullable) rather than silently passing.

## UX states (UI stories only)
N/A (no screens yet).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
`android:allowBackup="false"`, cleartext traffic disabled in the manifest/network-security-config from day one (MASVS); debug builds may trust user CAs only via a debug-only config.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-002.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (Gradle scaffolding; generated code excluded)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
