# Milestone 9B — Firebase Authentication adapter

Verified 2026-10-27. SQLite/local authentication remain the default. Schema version 5, existing financial/recurring/receipt repositories, dependencies and real Google Services configuration are unchanged. No local data migration/reset, live Firebase account creation, Firestore operation/rules deployment or billing/service enablement was performed.

## Implementation and identity boundary

- `FirebaseAuthService` implements AuthService through a thin `FirebaseAuthBackend` seam and `FirebaseSdkAuthBackend`. Registration, login, reset, logout, session validation and observer updates are asynchronous. The serial coordinator cancels stale results, drains uncancellable SDK operations after logout/close, and keeps unavailable/restoring identities hidden. Existing AccountValidation and AuthViewModel are reused.
- Firebase owns credentials and durable persistence. No application-managed Firebase password/token/hash/session cache was added. Owned password arrays are cleared; the SDK-required String is transient. Backup/device-transfer excludes shared preferences including Firebase's SDK storage.
- `SessionState.Backend` distinguishes LOCAL from FIREBASE. Firebase snapshots carry the exact opaque UID/email; no SQLite UUID conversion, profile/UPI or reserved username is implied. MainActivity still consumes LocalAuthService only; its gate rejects cloud sessions for normal app destinations.
- Debug Login/Profile explicitly open a separate sandbox Fragment and Fragment-scoped AuthViewModel. Its named FirebaseApp targets **only** demo-splitease-auth through the official local Auth Emulator at 127.0.0.1:9099 over ADB reverse. It cannot fall back to the real/default Firebase project. The default SDK remains signed out. Release hooks expose no sandbox destination or mode selector.
- Reused signup fields retain password confirmation, live policy checklist, visibility toggles and non-saved password fields. The sandbox provides login, reset, retry/logout on unavailable restoration and explicit Back. Firebase authentication does not open local groups. Username is input-validated only; uniqueness/profile persistence is deferred to 9C.
- Cached sessions are reloaded and force-refreshed through the SDK. Deleted/invalid users are signed out; network failure is unavailable with retry/logout. Reset response is generic and emulator-only reset links do not send mail. Safe codes distinguish invalid input/credentials, duplicate email, unavailable provider, network, rate limit and expired session without displaying backend internals.

## Verification results

| Layer | Result and scope |
| --- | --- |
| Mocked JVM adapter tests | **11/11 passed**: validation, email normalization, opaque UID, async callback executor, cleared password copy, duplicate/invalid credentials, reset, external auth changes, restoration/offline/invalid sessions, cancellation/close, queued success after logout, late validation/sign-in and redundant signed-out callbacks |
| Actual Android Firebase SDK + official Auth Emulator | **1/1 passed**, 2.952s: registration, duplicate email, wrong password, login/logout, reset-code consumption and changed-password login, adapter recreation, deleted cached user rejection and generic unknown-account reset |
| Separate process restoration | **1/1 passed**, 0.136s: SDK fixture persisted through a normal Activity stop, then explicit force-stop/relaunch; different PID and exact restored cloud UID/email verified; local session/default Firebase identity remained separate |
| UI/local regression | **6/6 unique tests passed**: final sandbox rerun **1/1**, 3.991s; existing local tests **5/5** passed in the combined run. Firebase sandbox confirmation validation, signup/login, recreation, hidden bottom tabs and forced local Groups navigation rejection; local login rejects the cloud account. Existing NavigationTest (2) and LocalAuthenticationTest (3) cover local signup/login, profiles, tabs, Back/logout boundaries, recreation, account switching and both themes |
| Build/unit/lint | **PASS** — assembleDebug, assembleRelease (unsigned), assembleDebugAndroidTest, testDebugUnitTest: **43/43**, lintDebug: **0 errors, 12 unchanged warnings**. Final debug form-only recompile/lint also passed |
| Live Firebase backend | **NOT VERIFIED** — no live accounts, reset mail, provider configuration, plan inspection, token revocation backend or production network-error injection. Emulator coverage is not live-backend coverage |

Lint classification: 9 GradleDependency, 1 AndroidGradlePluginVersion, 1 NewerVersionAvailable, 1 UnusedResources. These match the 9A baseline; no unrelated upgrades or warning cleanup. Debug APK contains the demo-project marker; unsigned release APK does not. `git diff --check` passed. Final milestone Gradle command: `assembleDebug assembleRelease assembleDebugAndroidTest testDebugUnitTest lintDebug --no-daemon` (GRADLE_USER_HOME=.gradle-home); the subsequent debug-only form gate was compiled with `assembleDebug assembleDebugAndroidTest lintDebug`.

Device: Medium_Phone_API_37.0 / Android 17 emulator (emulator-5554). A connected physical phone was not used. API 24, physical-device and exhaustive accessibility/large-font checks remain unverified for the sandbox. Existing local light/dark navigation tests passed; no claim of exhaustive visual coverage.

## Files and configuration

Added the Firebase auth coordinator/backend/error mapper, SDK adapter, debug sandbox Fragment/layouts/network policy and release no-op hook; added 11 unit cases, three opt-in instrumentation cases and their local emulator helper/config. Updated AuthService/SessionState, injected AuthViewModel support, safe error strings, Login/Profile debug entry hooks, MainActivity graph setup/gating and backup exclusions. README/AGENTS document the mode boundary and 9C coordination; .gitignore excludes emulator diagnostics. No Gradle dependency, SQLite schema, financial repository, receipt-storage contract or Firestore rule changes.

## Reproduction

Start only the Auth Emulator, with Node 20+ (used Node 24.15 and firebase-tools 15.34.1):

```sh
npx --yes --package firebase-tools@15.34.1 firebase emulators:start \
  --only auth --project demo-splitease-auth \
  --config tools/firebase-auth-emulator/firebase.json
adb -s emulator-5554 reverse tcp:9099 tcp:9099
```

Build and install both APKs using `adb install -r`, retaining application state. Do not use a managed test runner that uninstalls/clears data between the two restoration phases:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.example.splitease.firebase.FirebaseAuthEmulatorTest \
  -e authEmulator true -e keepFirebaseFixture true \
  com.example.splitease.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am force-stop com.example.splitease
adb -s emulator-5554 shell am start -n com.example.splitease/.ui.MainActivity
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.example.splitease.firebase.FirebaseAuthColdRestartTest \
  -e verifyFirebaseRestart true \
  com.example.splitease.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.example.splitease.firebase.FirebaseAuthSandboxNavigationTest,com.example.splitease.ui.NavigationTest,com.example.splitease.auth.LocalAuthenticationTest \
  -e authEmulator true \
  com.example.splitease.test/androidx.test.runner.AndroidJUnitRunner
```

Opt-in tests skip without their flags and require a verified demo emulator. Fixed test identities are reused; reset/deletion touch only those emulator fixtures. The temporary cross-process cache fixture contains UID/email/PID only, never credentials or tokens. Normal Activity stop allows Firebase's asynchronous persistence to flush before force-stop; an immediate kill at the exact registration callback was not claimed durable. Remove the cache fixture/ADB reverse and stop the emulator after testing. No account export is committed.

## Defects found during verification

- Fixed dynamic sandbox navigation insertion to happen **before** graph restoration, preventing an Activity recreation crash.
- Fixed a redundant SDK signed-out event invalidating an in-flight reset callback. Added a deterministic mocked regression test and reran the actual SDK reset sequence.
- Kept the cloud session/form gate closed while a cancelled SDK sign-in/reload drains after logout. Both form-busy and session-ready state govern submission, preventing a premature enabled form from returning BUSY during startup. The logout race test verifies the hidden intermediate state.
- Corrected the persistence test fixture to use a normal Activity stop before force-stop, allowing SDK-owned asynchronous durable writes to flush. No custom token cache or credential persistence was introduced.

## Milestone 9C requirements and remaining limits

Firestore profile/username reservation and cloud repositories are intentionally absent; existing deny-all rules remain unchanged. A Firebase Auth identity alone is not application readiness. See README's 9C integration boundary: one idempotent Firestore transaction reserves the normalized username and creates the UID-keyed profile, with pending-onboarding gating, restart reconciliation, ambiguous-commit checks and safe compensation. Auth and Firestore cannot commit atomically together. Do not delete newly created accounts blindly after timeouts or abandon unrecoverable pending users. Cloud identity/repositories/navigation must be selected together before exposing cloud application data; no local migration.

Spark status, enabled email/password provider, anti-enumeration configuration, live email delivery and eventual member-scoped rules require owner/controlled-backend verification before rollout. No billing, Blaze, Cloud Storage, Functions or Analytics was introduced. The adapter's debug path uses no real Firebase backend resources. Stop after 9B.
