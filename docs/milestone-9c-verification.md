# Milestone 9C — Firestore profiles and username registry

Date: 2026-10-27. Local SQLite/authentication remain active by default. No production Firestore reads/writes, rules deployment, account migration, data reset, billing/Blaze enablement, Storage or Functions. No new dependencies or SQLite migrations; schema remains 5.

## Implementation

- Added immutable CloudProfile (opaque Firebase UID, normalized/display username, normalized email, optional unverified UPI, created/updated milliseconds projected from server timestamps), CloudProfileValidation and CloudProfileRepository. Reuses RepositoryCallback, supplied serial executors, ObservableQuery and public MemberIdentity. UserRepository's credential API remains local; no dummy Firestore password methods.
- FirestoreProfileRepository derives its actor from its named FirebaseAuth scope. Private `profiles/{uid}` is owner-readable only; `usernames/{normalized}` exposes just UID/displayUsername for authenticated exact lookup. No list/query directory API. Server reads establish readiness; observer cache/pending-write events are filtered. Cancelled/stale-account callbacks cannot deliver another owner's profile; auth changes terminate subscriptions.
- Create profile and registry atomically in one Firestore transaction. Existing profile makes retries idempotent, including a lost acknowledgement followed by a different requested username. Concurrent reservations have one winner. A failed create checks the exact public key before reporting a verified collision; unrelated permission/unavailable failures stay generic.
- Cloud usernames: trimmed 3–30 ASCII letters/digits/underscores, Locale.ROOT lowercase key, display case retained. Local name rules are unchanged. UID, canonical username, email and creation metadata are immutable. Update permits display-case-only changes and optional UPI, with registry display updated atomically and updatedAt set to server time. Renaming, email changes, deletion and UPI verification are outside scope.
- ProfiledFirebaseAuthBackend composes the existing SDK backend and repository. Signup creates Auth first, then the profile transaction. Login/restoration reload the Auth user and fetch its server profile. FirebaseAuthService publishes PROFILE_REQUIRED for incomplete onboarding; only a complete profile produces SIGNED_IN. The debug screen supports retry/choose another username/logout and survives recreation. No cloud identity can enter local groups or replace LocalAuthService.
- Cross-service failures retain a recoverable Auth identity without a profile, never silently mark it ready or blindly delete it. An ambiguous commit resolves by read/idempotent retry. No custom credential/token/pending-name preference store; Firebase SDK owns session persistence. Already accepted atomic writes may finish after logout, but late callbacks cannot reauthenticate the UI.
- Debug uses only named demo-splitease-auth Auth/Firestore Emulators (127.0.0.1:9099/8080 over explicit ADB reverse), with memory cache for Firestore and no production fallback. Release has no sandbox selector. Real google-services.json and the default FirebaseApp remain unchanged.

## Files created/modified

Added model/CloudProfile, domain/CloudProfileValidation, repository/CloudProfileRepository, data/firebase/FirestoreProfileRepository and auth/ProfiledFirebaseAuthBackend; candidate firestore/firestore.rules and firebase.emulator.json. Extended AuthService/backend, SessionState, FirebaseAuthService and AuthViewModel for pending profile completion, plus safe error resources. Updated the existing debug composition/Fragment/strings and sandbox navigation regression, and clarified MemberIdentity scope. Added 8 JVM cases and profile/Rules, onboarding UI and cold-restart instrumentation classes. Updated README/AGENTS and ignored Firestore debug logs. No Gradle, SQLite, group/financial repository, receipt-storage or production configuration changes.

## Candidate rules (not deployed)

`firestore/firestore.rules` enforces authenticated exact reads, private owner access, strict allowed fields/types, Auth email equality/lowercase, immutable identities, request-time timestamps, paired getAfter profile/registry validation, and reservation ownership. Both halves of creation/update must agree. Denies standalone/orphan reservations, hijacking, list/delete, extraneous fields and every unimplemented collection. Candidate rules load only through `firebase.emulator.json`; production deny-all rules were not changed or inspected via a live API.

## Verification

| Check | Result/evidence |
| --- | --- |
| JVM tests | **51/51 passed**, including 8 new profile/coordinator cases and existing 11 Firebase adapter cases. New tests inject before-commit failure, lost acknowledgement, duplicate reservation, invalid username, restart recovery, late profile completion/logout and external sign-out during a restored profile read; pure normalization/UPI checks |
| Firestore profile/Rules emulator | **6/6 passed**, also in the final combined **15/15** run (24.581s). Actual Android Auth + Firestore SDKs; owner reads, create/reopen/idempotency, minimal public fields, simultaneous claims with one winner, alternate-name recovery, allowed updates, immutable timestamps/identity, denied cross-owner/unauthenticated access, denied collections/unimplemented paths, atomic pair requirements and account switching |
| Pending-profile UI | **1/1 passed**, 6.324s. Actual Auth account without profile; invalid-name error, complete profile after Activity recreation, landscape/dark at 1.3× font scale. Screenshots visually reviewed; original font/night settings restored |
| Force-stop/relaunch | **1/1 passed**, 2.531s. Explicit force-stop/relaunch, different PID, exact SDK UID + server profile username restored; local auth/default Firebase identity remained separate. SDK logout and fixture removal completed |
| Integration/local regression | **9/9 passed** within the final 15-test run: cloud signup/navigation isolation (1), existing local NavigationTest (2), LocalAuthenticationTest (3), SQLite core migration, recurring isolation and receipt-v4 migration (3). **17 unique instrumentation tests passed** across the final runs |
| Build/lint | **PASS** — assembleDebug, assembleRelease (unsigned), assembleDebugAndroidTest, testDebugUnitTest, lintDebug; final milestone build 20s. **0 lint errors, 12 unchanged warnings**: 9 GradleDependency, 1 AndroidGradlePluginVersion, 1 NewerVersionAvailable, 1 UnusedResources |
| Live backend | **NOT VERIFIED** — no production accounts, profile data, provider/plan inspection, backend email delivery, live rules or quotas exercised. Emulator tests do not establish production authorization or Spark status |

`git diff --check` passed. APK inspection found the demo-project marker in debug and not in release. Final test-only rebuild passed after making the sandbox regression wait for the 9C pending-profile branch. Production source/rules did not change afterward.

Android device: Medium_Phone_API_37.0 / Android 17 emulator (emulator-5554). Only that emulator was operated; connected physical device was not used. Unit failure injection is distinct from actual SDK/emulator verification. Rules tests use raw Android SDK calls with each user's real emulator identity, not an admin bypass; the test setup's database clear is restricted to the local demo emulator fixture database. Auth fixture identities are reused, plus temporary UI accounts only in the emulator. No real cloud disposable accounts.

## Reproduce

Use Node 20+ and Java 21+; verified Node 24.15, Java 25, firebase-tools 15.34.1, Firestore emulator 1.22.0. No Firebase login/live project needed:

```sh
npx --yes --package firebase-tools@15.34.1 firebase emulators:start \
  --only auth,firestore --project demo-splitease-auth --config firebase.emulator.json
adb -s emulator-5554 reverse tcp:9099 tcp:9099
adb -s emulator-5554 reverse tcp:8080 tcp:8080
./gradlew assembleDebug assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.example.splitease.firebase.FirestoreProfilesEmulatorTest \
  -e profilesEmulator true \
  com.example.splitease.test/androidx.test.runner.AndroidJUnitRunner
```

Run UI isolation regression with `FirebaseAuthSandboxNavigationTest` (`authEmulator=true`, both emulators running). Pending UI recovery uses `FirebaseProfileOnboardingNavigationTest` with `profilesEmulator=true`. It intentionally creates an emulator Auth identity without a profile, completes it after Activity recreation, checks validation, landscape/dark, and verifies the local identity stays signed out.

For real cross-process restoration, add `-e keepProfileFixture true` to the pending UI test. The normal Activity stop flushes SDK-owned persistence; a cache fixture contains only UID, username and PID. Do not uninstall/clear app data between phases:

```sh
adb -s emulator-5554 shell am force-stop com.example.splitease
adb -s emulator-5554 shell am start -n com.example.splitease/.ui.MainActivity
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.example.splitease.firebase.FirebaseProfileColdRestartTest \
  -e profilesRestart true \
  com.example.splitease.test/androidx.test.runner.AndroidJUnitRunner
```

The cold test requires a different PID, restores the exact UID/username through SDK validation and server profile read, verifies local/default Firebase identities remain separate, logs out and removes the fixture. Opt-in tests skip without flags. Stop emulators and remove only the 8080/9099 ADB reverses after testing; logs/exports/private fixtures must not be committed.

## Findings and limitations

- Concurrent commits may fail rules validation before the SDK retries a transaction. Read the exact reservation on failure to distinguish a verified collision; do not treat all permission failures as duplicate usernames. Verified one winner and one duplicate result.
- During rotation/theme changes, the sandbox may briefly revalidate its session. UI tests must wait for validated profile readiness, not just view existence. No authenticated content is exposed while restoring.
- External sign-out while profile validation is in flight now ends SIGNED_OUT, rather than leaving an unavailable gate; a deterministic regression test covers it.
- Offline/missing emulator, permission denial or incomplete onboarding does not grant readiness. Before-commit/ambiguous acknowledgement recovery is deterministically mocked; a real network packet-loss-at-commit test was not performed. Actual SDK tests exercise a deliberately missing profile and conflicting reservation.
- Pending Auth accounts are intentionally retained for recovery. Permanently abandoned accounts, externally deleted Auth identities and stale reservations need an explicitly designed future administrative/deletion policy. No automatic privileged cleanup or account deletion is implemented. No orphan profile/registry halves can be created through the tested client rules.
- Exact authenticated username lookup necessarily reveals whether a guessed handle exists, and returns its public UID/display name only. It cannot list the directory. No paid server/rate-limit service was added. Production quotas/abuse controls require review before rollout.
- API 24, physical devices and exhaustive accessibility/device coverage remain unverified. The reviewed large-font landscape sandbox is vertically scrollable; lower controls can require scrolling. No cloud group/expense integration, profile editor/search UI outside the sandbox onboarding flow, or whole-app cloud switch in 9C. Profile update/search/observation APIs are available and tested for later presentation flows.

## Deployment steps (future, approval required)

1. Review candidate rules and this evidence, including uniqueness/privacy and recovery limitations. Confirm the owner-controlled real project remains Spark with no billing account and intended Auth provider configuration.
2. Keep current production deny-all rules until reviewed rules receive explicit deployment approval. Do not use emulator test tokens, demo options or test data against production. No deployment was attempted in 9C.
3. Prepare a separate production rules configuration/change review, deploy only the approved rules to the explicit intended project, and perform bounded controlled owner/lookup denial smoke checks without production load testing.
4. Before normal app cloud activation, implement and review coordinated cloud repositories/navigation with a strict cloud identity boundary. Auth/profile readiness alone must never expose local SQLite groups. No data migration; fresh cloud accounts/data. No Blaze, billing, Storage or Functions.

README and AGENTS document the implemented boundary. Stop after 9C.
