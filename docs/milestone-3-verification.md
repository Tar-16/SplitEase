# Milestone 3 completion and verification

Status: implemented and verified on the available Android 17 / API 37 arm64 emulator. No Milestone 4 work was started.

## Resumption audit

The workspace has no `.git` directory, so `git status` cannot provide a diff. Existing source files, README, AGENTS, Gradle configuration and XML test/lint reports were inspected directly. The saved passing reports contain 14 local tests and 13 instrumentation tests, with no failures, errors or skips. Authentication implementation and architecture documentation were already present. The final verification report, visual checks and actual force-stop/relaunch check were unfinished.

No application code was rewritten or changed during the resumed verification. The missing debug APK was regenerated; only documentation and non-sensitive form screenshots were added/updated in this final pass.

## Build and automated results

The implementation's full verification command passed:

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest --no-daemon
```

During resumed verification, the following also passed in 11 seconds, regenerating the missing debug APK and confirming current sources:

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug testDebugUnitTest lintDebug --no-daemon
```

Unchanged local-test and lint tasks reused successful Gradle outputs. Instrumentation was not unnecessarily repeated in the resumption; the previously completed passing report was inspected, then the remaining device checks were performed directly.

| Check | Result |
| --- | --- |
| `assembleDebug` | Successful; APK at `app/build/outputs/apk/debug/app-debug.apk` |
| Local tests | 14 passed: 4 session coordination, 2 account validation, 6 monetary/domain, 2 INR formatting |
| Instrumentation | 13 passed: 3 local authentication, 8 SQLite persistence, 2 UI/navigation |
| `lintDebug` | 0 errors, 14 warnings; no new authentication warnings |
| Device | Medium_Phone_API_37.0, Android 17 / API 37, arm64 emulator |

Reports: `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/androidTests/connected/debug/index.html`, and `app/build/reports/lint-results-debug.html`.

Coverage includes registration, duplicate normalized email/username rejection, invalid email and password policy, real Argon2id credential verification, incorrect passwords without lockout, durable session recreation, missing/malformed/deleted user restoration, logout, account switching, UPI persistence/isolation, cancelled in-flight signup, duplicate submissions, failed session writes, signup confirmation, live checklist, cleared password fields on Activity recreation, all three tabs, dark theme, and login/back-stack boundaries. Existing SQLite/domain regression tests remain passing.

## Final direct emulator verification

- Force-stopped the signed-in application using `adb shell am force-stop com.example.splitease`, then cold-launched MainActivity. It opened authenticated Groups without requesting credentials.
- Opened Profile and compared username, email and UPI field values before and after the process restart. All matched. Account values were not copied into this report or screenshots retained in the workspace.
- Logged out through Profile, force-stopped and cold-launched again. Login appeared with no bottom navigation, confirming durable logout.
- Inspected Login and Signup in light and dark themes. Rounded inputs/cards, password toggles, all five checklist entries, action buttons and supporting copy were readable without clipping on the emulator's portrait viewport.
- Existing automated tests already verified creating two real local accounts, switching identities, UPI save/recreation and protected navigation redirects; those workflows were not recreated unnecessarily during the resumed pass.
- Restored the emulator's original light theme and `show_ime_with_hard_keyboard=0` setting. The app is left signed out on Login. Existing accounts/data were not deleted.

Screenshots of empty forms only:

- [Login, light](screenshots/milestone-3/login-light.png)
- [Login, dark](screenshots/milestone-3/login-dark.png)
- [Signup, light](screenshots/milestone-3/signup-light.png)
- [Signup, dark](screenshots/milestone-3/signup-dark.png)

## Lint classification

The original 17 warnings are now 14:

| Category | Original | Current | Decision |
| --- | ---: | ---: | --- |
| Newer dependency/Gradle notices | 11 | 11 | Existing compatible pinned dependencies preserved; no unrelated upgrades |
| `accessibilityHeading` below API 28 | 3 | 2 | New Profile/auth headings use ViewCompat; unchanged Groups/Recurring retain their existing warnings |
| Backup configuration | 1 | 0 | Added `fullBackupContent=false`; session preferences also excluded from Android 12+ extraction |
| Background overdraw | 1 | 0 | Revised Activity startup container uses the existing theme background |
| Unused brand color | 1 | 1 | Retained existing shared resource; no unrelated cleanup |

The new Lifecycle ViewModel/LiveData dependencies are stable version 2.11.0. The compiler also notes the existing deprecated Locale constructor in InrFormatter; this is not a new authentication failure. No lint warnings were suppressed to obtain these results.

## Architecture and schema

`AuthService` is the replaceable session/authentication boundary. `LocalAuthService` coordinates the existing asynchronous UserRepository and Argon2id implementation on a worker. AuthViewModel exposes main-thread lifecycle-aware state; Fragments only bind views and forward actions. UserRepository adds normalized email lookup and typed duplicate-account errors, retaining database UNIQUE constraints.

Application-private SharedPreferences stores only the active user UUID. Checked commits occur off the main thread. Restored identities must exist in SQLite. Startup content remains hidden until validation resolves; logout and account changes clear authenticated navigation history. Profile owner IDs come from the session, not UI input.

SQLite remains at schema version 3. No tables, migration versions or recurring-bill models were changed for authentication. Firebase is not included and will begin with fresh accounts/data later.

## Files created or modified across Milestone 3

Created under `app/src/main/java/com/example/splitease/`:

- `auth/AuthService.java`, `LocalAuthService.java`, `AuthException.java`, `SessionState.java`, `SessionStore.java`
- `data/PreferencesSessionStore.java`
- `domain/AccountValidation.java`
- `repository/DuplicateAccountException.java`
- `ui/auth/AuthViewModel.java`, `AuthViews.java`, `LoginFragment.java`, `SignupFragment.java`

Other created files:

- `app/src/main/res/layout/fragment_login.xml`, `fragment_signup.xml`
- `app/src/main/res/values/auth_strings.xml`
- `app/src/test/java/com/example/splitease/auth/LocalAuthServiceTest.java`
- `app/src/test/java/com/example/splitease/domain/AccountValidationTest.java`
- `app/src/androidTest/java/com/example/splitease/auth/LocalAuthenticationTest.java`
- This report and `docs/screenshots/milestone-3/` empty-form screenshots

Modified:

- `SplitEaseApplication.java`, `repository/UserRepository.java`, `data/SqliteRepositories.java`
- `ui/MainActivity.java`, `ui/profile/ProfileFragment.java`
- `res/layout/activity_main.xml`, `res/layout/fragment_profile.xml`, `res/navigation/main_nav_graph.xml`
- `res/values/styles.xml`, `res/values/strings.xml`, `res/xml/data_extraction_rules.xml`
- `app/src/main/AndroidManifest.xml`, `app/build.gradle`
- `app/src/androidTest/java/com/example/splitease/ui/NavigationTest.java`
- `README.md`, `AGENTS.md`

## Remaining limitations and manual checks

- API 24 and physical-device coverage remain outstanding. Large-font, landscape and TalkBack exploration are not claimed by the completed tests.
- The emulator displayed its compact/floating input toolbar rather than a full docked software keyboard, even with the hardware-keyboard override temporarily enabled. Input focus was checked, but full keyboard occlusion/resize behavior still needs verification with a conventional on-screen keyboard, ideally on a physical device. Original settings were restored.
- Passwords in unsubmitted forms deliberately clear on recreation. Credential recovery, password changes, email verification and Firebase are outside scope. There is no attempt lockout by design.
- SQLite/session data uses Android app-private storage rather than database encryption; rooted/debug-access devices are outside this local-account security boundary. Session files contain no plaintext password or hash.
- UPI IDs remain optional, unverified profile metadata. No payments are performed. Groups and Recurring remain placeholders.
