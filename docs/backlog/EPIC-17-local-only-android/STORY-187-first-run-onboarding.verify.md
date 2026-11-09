# STORY-187 verify + review (qa-verifier, combined, low tier)

## Loop 1 - base origin/main `db5110b`, head `2d9f166` - PASS
Gate: `bash scripts/make/check-fast.sh android` -> GREEN in 73s; `./gradlew :app:testDebugUnitTest --rerun` -> 174 suites,
1,787 tests, 0 failures, 0 skipped (OnboardingViewModelTest 18/18, OnboardingScreenTest 18/18, OnboardingScreenArabicTest
3/3, BackupPrefsGuardTest 5/5). TEST_INTEGRITY: OK (2 test files added, 0 -> 61 and 0 -> 38 asserts).
- REQ-AND-139: PASS - 7 steps in order, Skip on 1-6, resume after a new store/ViewModel, finished never again, Settings
  reopens at step 1, a phone with an expense gets the card, 90-day backfill starts (RUNNING, D90); RTL + Arabic digits
- REQ-AND-140: PASS - explanation before the request; denial text + Open app settings with Continue; Not enabled; READ_SMS
  denied shows why nothing started
- REQ-AND-141: PASS - Sara / سارة added next to Me; Skip leaves Me; empty, spaces, 41 chars rejected, 40 accepted; 3
  concurrent adds -> one member; Paid-by shows with >= 2 members
Exploratory: 14 cases, 5 problems (invisible-only names, font 1.5 card squeeze, capture-on text, has-data rule).
FINDINGS:
- [MINOR] OnboardingViewModel.kt:65 -- only expense rows count as "has data"
- [MINOR] OnboardingScreen.kt:202-214 -- "Don't ask again" not detected; Turn on does nothing after a permanent denial
- [MINOR] OnboardingScreen.kt:202 -- "SMS capture is on" ignores the in-app switch
- [MINOR] OnboardingScreen.kt:395-399 -- Finish setup card Dismiss squeezed to 53x144dp at font scale 1.5
- [MINOR] OnboardingViewModel.kt:27 -- invisible-only names (U+200F, U+200B) and newlines accepted
- [MINOR] tests -- no OnboardingRoute/MasroofRoot tests; READ_SMS denial test checks nothing real
- [MINOR] MainActivity.kt MasroofRoot -- no Back handling in the guide
- [MINOR] DoD -- no Roborazzi goldens (Roborazzi is not set up in android/ at all; project-wide gap)
- [MINOR] OnboardingScreen.kt:368 -- notification capture turned on even when access is not granted
- [NIT] ٤٠ hardcoded in values-ar; denial texts not live regions; step 5 status; DECISIONS line wrong about restore;
  SQLite lower() dedup; step 1 no language selected for other locales; prefs read on main thread
STATUS: PASS

## Fix round after PASS - head `6d35fab` (merges origin/main `db5110b`) - orchestrator diff check
All nine MINORs and the NITs addressed by the builder; the orchestrator read the diff (Daos `hasUserData` over every user
table, Kotlin-side case-insensitive member check, invisible/control characters refused, Back handling with `reopened`
classified OUT of backup, permanent-denial detection, capture-on rule shared with Settings, notification capture only once
access is granted, stacked card buttons). Builder gate: `bash scripts/make/check-fast.sh android` -> GREEN in 1093s;
`bash scripts/quality/test-integrity-check.sh origin/main` -> TEST_INTEGRITY: OK (14 -> 160, none lost). New tests:
OnboardingRouteTest (READ_SMS denial starts no backfill; grant starts the 90-day backfill; SetupGate guide/card/reopen),
OnboardingCardNarrowTest (Dismiss >= 88dp at font 1.5 on 320dp), Back, invisible names, has-data per table.
Left as is: Roborazzi goldens (not set up in android/; LESSONS line); MasroofRoot itself is exercised through SetupGate,
not end to end (needs Hilt test infra).
