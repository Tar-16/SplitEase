# Milestone 6 verification

22 October 2026. Scope: settlement suggestions, recording/correcting payments and optional UPI handoff. No recurring UI, OCR or Firebase work.

## Architecture and behavior

Pure-Java SettlementCalculator derives deterministic greedy transfers from existing Balances snapshots with checked long paise. Largest remaining magnitude comes first, with UUID ties. It handles zero/partial/historical balances and rejects overflow/nonconservation. It does not claim globally minimum payment count; recurring records remain excluded.

SettlementsViewModel obtains actor identity from AuthService, observes authorized SettlementDetails, retains session-bound drafts through SavedStateHandle and suppresses stale callbacks. Fragments only render and forward actions. The Activity guards the new destination and clears it on logout/account change. Bottom navigation remains on the three root destinations.

Repository transactions recheck membership, identities, positive amounts, ceilings and duplicate acknowledgement. New records require the actor to be payer or payee and are capped at that pair’s current greedy suggestion. Any current member can edit amount/date; group/payer/payee are immutable. Historical parties remain referenced. Edit ceiling is max(old amount, pair debt/credit capacity calculated without the old record), allowing retention/reduction when expenses changed. No edit history is kept. Duplicate heuristic: same group, parties, amount and UTC calendar date, excluding the edited record; explicit acknowledgement permits proceeding but never bypasses other validation.

Only suggested recipients’ unverified UPI IDs are included in the authorized settlement projection. UPI URI fields pa/pn/am/cu/tn are encoded; the full current suggestion amount is used independently of the manual record field. ACTION_VIEW is launched via system chooser. No handler/disappearing handler is safe. There is no payment-result callback or automatic recording. Manual recording requires separate confirmation and remains available without UPI.

SQLite stays version 3: no migration or reset. No dependencies changed. Java 17, minSdk 24, compile/target 37, AGP 9.2.0, Gradle 9.4.1 retained. App version 0.6.0.

## Results

Device: Medium_Phone_API_37.0, Android 17 / API 37.

| Check | Result |
| --- | --- |
| Focused calculator unit tests | 4 passed |
| Focused SQLite, settlement navigation and UPI emulator tests | 19 passed |
| assembleDebug | Passed |
| testDebugUnitTest | 20 passed, no failures/errors |
| connectedDebugAndroidTest | 29 passed, no failures/errors/skips |
| lintDebug | 0 errors, 13 pre-existing warnings |
| installDebug | Installed successfully on API 37 emulator |
| git diff --check | Passed |

The full build/unit/emulator/lint command ran once at completion. Tests cover deterministic matching/conservation, exact paise and overflow, full/partial recording and recalculation, immutable identities, editing ceilings, duplicate override, unauthorized operations, historical records and repository close/reopen persistence. UI coverage verifies account switching, nonparty recording restrictions, correction by another current member, invalid amount rejection, duplicate confirmation, Activity recreation, light/dark themes and logout destination guard. Existing auth/groups/expenses/recurring isolation regressions pass.

UPI tests assert encoded fields, exact amount including long limits, chooser target, no handler and disappearing handler. Cancellation is simulated at the injected handoff boundary and cannot write a record. The emulator workflow tests the actual missing-app message when no compatible handler exists. No real bank transfer or external payment app was launched.

Light payment details and dark suggestion/record lists were visually inspected; evidence is in screenshots/milestone-6/. Test records/accounts are disposable. Reports are in app/build/reports/tests/testDebugUnitTest/, app/build/reports/androidTests/connected/debug/ and app/build/reports/lint-results-debug.html.

## Lint and remaining coverage

Unchanged warnings: 9 GradleDependency, 1 AndroidGradlePluginVersion, 1 NewerVersionAvailable (pinned tool/dependency versions); 1 UnusedAttribute on the existing Recurring accessibility heading; 1 UnusedResources for preserved brand_teal. No unrelated version upgrades were made.

Real third-party UPI chooser/app cancellation, physical devices and API 24 still need manual coverage. No claim of bank confirmation, UPI verification or reconciliation. Settlement persistence is tested by reopening SQLite; a new settlement-specific force-stop/cold-relaunch manual workflow was not performed. Full docked keyboard, TalkBack, landscape and large-font settlement-specific checks remain manual coverage. Lists remain unpaginated; existing writes are last-write-wins. Legacy raw repository observation methods remain trusted internal APIs, while the UI uses the membership-checked projection.

## Files

Created under app/src/main/java/com/example/splitease/: domain/SettlementCalculator.java; model/Transfer.java and SettlementDetails.java; repository/DuplicateSettlementException.java; ui/settlements/SettlementsViewModel.java and SettlementsFragment.java; util/UpiPayment.java. Added res/layout/fragment_settlements.xml and res/values/settlement_strings.xml.

Modified SettlementRepository, SqliteRepositories, MainActivity, GroupDetailFragment, group-detail layout, navigation graph, AndroidManifest (UPI package visibility), app/build.gradle (version only), README.md and AGENTS.md.

Added SettlementCalculatorTest, SettlementNavigationTest and UpiPaymentTest; extended SqliteRepositoriesTest. Added this report and screenshots/milestone-6/.

Implementation references: [Android intent chooser and missing-handler guidance](https://developer.android.com/training/basics/intents/sending), [Google Pay India payment URI fields](https://developers.google.com/pay/india/api/web/create-payment-method).
