# Milestone 2 verification — 21 October 2026

Final verification command (workspace-local Gradle cache):

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest --no-daemon
```

Result: **BUILD SUCCESSFUL**, 31 seconds. SDK Platform 37 / Build Tools 36.0.0, Gradle 9.4.1, AGP 9.2.0, JDK 25 daemon, Java 17 application sources. Device: `Medium_Phone_API_37.0`, Android 17 / API 37, arm64 emulator.

| Check | Result |
| --- | --- |
| Debug APK | Built, `app/build/outputs/apk/debug/app-debug.apk` |
| Local JUnit | 8 tests passed; zero failures/errors/skips |
| SQLite instrumentation | 8 tests passed; zero failures/errors/skips |
| Navigation instrumentation | 1 test passed; all three tabs and Activity recreation |
| Android lint | Zero errors, 17 warnings |

## Tests exercised

- Equal splitting: deterministic leftover assignment independent of participant input order, zero shares, invalid/duplicate participants, Long.MAX_VALUE sum, overflow rejection.
- Monthly dates: leap-year and non-leap-year end-of-month clamping.
- Derived balances and partial settlement behavior; exact INR formatting at long limits.
- Real Android SQLite schema creation, user/group/member CRUD, case-normalized username/email uniqueness, foreign-key and membership-key failures, database reopening and retained values.
- Argon2id correct/incorrect password verification and persisted credential salt/hash/algorithm shape.
- Transaction rollback after a trigger aborts a split insert midway through expense replacement/creation; original amount/shares and row counts survive.
- Expense and settlement persistence across reopen; immutable settlement parties; updates overwrite; members/admin/outsider permissions; zero-balance leave and admin transfer; historical member identity and rejoin behavior.
- Recurring monthly uniqueness, new unpaid cycles, amount/handler/share snapshots after bill changes, paid/unpaid toggles, former snapshots after reopen, protected history deletion, and no ordinary expenses/settlements/balances generated.
- Version 1 -> 3 migration retains core data and backfills creator; foreign-key check is clean; downgrade throws and leaves data intact. The test applies the same intermediate v2 and v3 migration functions used by the app.
- Callback executor thread, initial and post-commit observation, no invalidation on failed writes, suppression of queued events after cancellation, errors after repository close.
- App launch, Groups/Recurring/Profile tab switching and selected destination after Activity recreation.

## Warnings and limits

The 17 lint warnings comprise 11 newer-dependency/Gradle notices, three pre-existing XML accessibilityHeading attributes ignored below API 28, one pre-existing background overdraw warning, one unused brand color and one suggestion for pre-Android-12 fullBackupContent configuration. Backup is already disabled by allowBackup=false; Android 12+ database cloud and device-transfer exclusions are explicit. No lint errors were suppressed. Existing stable toolchain/UI dependencies were preserved.

Only API 37 was available for runtime coverage. API 24, physical devices, large fonts, exhaustive light/dark visual QA, low-memory process death and concurrent multi-process access were not exercised. Activity recreation is not a full process-death test. Existing UI/theme resources were not redesigned.

An initial instrumentation attempt failed because the emulator disconnected. Restarting the installed emulator resolved it; the final full command passed. Sandbox restrictions on Gradle sockets/ADB required approved execution outside the sandbox.

Runtime observation is app-scoped, not a SQLite cross-process change feed. Credential primitives do not implement sessions/rate limits/recovery. Database contents are app-private but not encrypted. Version 3 cannot recover original creators from legacy records that never stored them, so their current admin is used as the documented migration fallback. Full screens and notification scheduling remain outside scope.

## Files added

Under `app/src/main/java/com/example/splitease/`:

- `SplitEaseApplication.java`
- `model/User.java`, `Group.java`, `GroupMember.java`, `Expense.java`, `ExpenseSplit.java`, `Settlement.java`, `RecurringBill.java`, `RecurringBillCycle.java`, `RecurringBillContribution.java`
- `domain/Rules.java`, `Balances.java`
- `repository/UserRepository.java`, `GroupRepository.java`, `ExpenseRepository.java`, `SettlementRepository.java`, `RecurringBillRepository.java`
- `data/SplitEaseDatabase.java`, `SqliteRepositories.java`, `PasswordHasher.java`

Other added files:

- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/test/java/com/example/splitease/domain/RulesTest.java`
- `app/src/androidTest/java/com/example/splitease/data/SqliteRepositoriesTest.java`
- `app/src/androidTest/java/com/example/splitease/ui/NavigationTest.java`
- `docs/milestone-2-verification.md`

Modified files:

- `app/build.gradle`: app version name, credential dependency, instrumentation runner/dependencies.
- `app/src/main/AndroidManifest.xml`: application composition root and database extraction rules.
- `data/package-info.java`, `domain/package-info.java`, `model/package-info.java`, `repository/package-info.java`: updated layer documentation.
- `README.md`, `AGENTS.md`: setup, schema, API semantics, decisions, limits and future rules.

Existing Activity/Fragment classes, layouts, theme resources, navigation, application ID, SDK versions, Gradle wrapper and user-installed SDK/JVM configuration were preserved.
