# Milestone 5 verification

22 October 2026. Scope: expenses, equal splitting, edit/delete, derived balances and real group summaries. No settlement entry/recommendations, UPI payments, receipt OCR, recurring UI or Firebase were added.

## Changes and architecture

- Group Detail has Members, Expenses and Balances tabs. Expense cards group by UTC calendar date, newest first, showing category glyph/label, title, payer, amount, participant count, own share and signed net impact. A floating add button opens the expense editor. Existing expenses open read-only with edit and confirmed delete actions.
- The editor supports title, exact INR amount, current payer, checkbox participants, five categories and a date picker. Shares preview through the existing sorted-UUID equal-split rule. An older expense retains its original departed payer/participants as labeled options. Existing legacy categories may be retained; new entries use Food, Travel, Rent, Bills or Other.
- ExpenseViewModel obtains the actor from AuthService, uses SavedStateHandle for drafts and lifecycle-aware observation/callbacks, and suppresses stale completions on identity change. Fragments perform no SQL or persistence calls. The new destination is included in the Activity authentication guard and has no bottom navigation.
- Existing ExpenseRepository save/delete operations remain authoritative. Membership checks, writes and split replacement happen in one SQLite transaction. Checked total-expense aggregation was added before save commit, preventing total overflow even when all net balances cancel to zero. Invalid changes roll back.
- Authorized GroupDetails snapshots include expenses, actual totals, balances and public names for current/referenced historical users. Group membership is checked before every read. Balances derive from the existing domain calculator including recorded settlements, never recurring contributions. They are not persisted and do not imply individual debtor-to-creditor relationships.
- Home shows total expenses, the signed-in user's net balance, current member count and latest expense date where available. There is no modification-time/activity-log column; no fabricated recent activity is shown.
- Database schema remains version 3. No migration, reset, new dependency, authentication change or recurring-model change. Java 17, min SDK 24, compile/target 37, AGP 9.2.0 and Gradle 9.4.1 retained. App version name: 0.5.0.

## Verification results

Device: Medium_Phone_API_37.0, Android 17 / API 37 emulator.

| Command/check | Result |
| --- | --- |
| Focused compile and ExpenseAmounts/Rules unit tests | Passed |
| Focused initial expense UI and SQLite tests | 16 passed |
| `assembleDebug` | Passed |
| `testDebugUnitTest` | 16 passed; no failures/errors |
| `connectedDebugAndroidTest` | 24 passed; no failures/errors |
| `lintDebug` after namespace cleanup | 0 errors; 13 pre-existing warnings |
| `installDebug` | Installed successfully |
| `git diff --check` | Passed |

The full build/unit/emulator/lint command ran once at completion. Lint identified two redundant namespace declarations in the new FrameLayout wrapper; only those declarations were removed afterward. A targeted `assembleDebug lintDebug installDebug` passed. No behavior changed after the full test run.

The 24 emulator tests comprise 14 SQLite cases, 3 authentication cases, 2 existing authentication-navigation cases, 2 group-navigation cases and 3 new expense-navigation cases. Coverage includes:

- Exact decimal parsing, positive values, rejection of extra fractional digits/exponents/overflow; existing deterministic leftover-paise and checked arithmetic tests.
- Single/multiple participants, actual share preview, validation of empty selections and invalid amounts.
- Creation, replacement, deletion, totals and balance recalculation; current-member mutation permission and unauthorized read/write rejection.
- Checked total overflow rollback even when payer/participant net balances are all zero.
- Former members retained in accounting and older expense editing; new expenses cannot add departed participants. Recorded settlements still affect historical balances correctly.
- Account switching, different own-balance perspectives, draft/Activity recreation, light/dark recreation, navigation boundaries and persisted repository reopening.
- Existing migrations, authentication/session behavior, group management and recurring isolation remain passing.

Reports: `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/androidTests/connected/debug/`, `app/build/reports/lint-results-debug.html`. APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Lint

No new warnings remain. The 13 retained warnings are:

- 11 pinned dependency/tool version notices: 9 GradleDependency, 1 AndroidGradlePluginVersion, 1 NewerVersionAvailable.
- 1 pre-existing UnusedAttribute for the Recurring placeholder's API-28 accessibilityHeading attribute.
- 1 pre-existing UnusedResources notice for the preserved brand_teal design token.

## Direct emulator checks

- Inspected the light editor with exact ₹50.01/₹50.00 shares and dark balance perspectives from the automated workflow. Direct UI checks also confirmed the Expenses/Balances tabs and add FAB switch correctly.
- Created a disposable review account/group and entered a real ₹123.45 expense through the installed APK. Its one participant had a ₹123.45 share and zero net impact as both payer and participant; group/Home showed actual spending and a settled net balance.
- Checked the expense list at font scale 1.3 and in landscape, including scrolling. Original font/orientation settings were restored. Editor recreation and light/dark theme changes were separately covered by tests.
- Force-stopped and cold-relaunched the installed app. It restored the session directly to Groups Home with total expenses ₹123.45. Reopening the group showed the persisted “Persistent lunch” expense. The emulator is left signed in to this disposable review account.
- Screenshots in `docs/screenshots/milestone-5/` record these results. They contain disposable usernames, no private credentials.

## Limits and follow-up coverage

- Lists remain unpaginated for local use. Financial aggregates use checked signed long paise and fail visibly on overflow instead of displaying wrapped or fabricated values.
- Existing repository methods remain trusted in-process contracts. New expense screens read through the membership-checked GroupDetails projection; raw legacy observation methods must not be exposed to untrusted callers.
- Expense dates are calendar dates shown/grouped in UTC; no audit trail or last-modified timestamps were introduced. Existing repository updates remain last-write-wins.
- API 24, physical devices, TalkBack and a conventional full docked software keyboard remain outside completed device coverage. The emulator uses a compact input toolbar. No claim of exhaustive font-size/device coverage.
- Expense and balance screens are complete for this milestone. Settlement entry/recommendations/payment execution and all other Milestone 6 work remain unimplemented.

## Files created and modified

Created under `app/src/main`:

- `java/com/example/splitease/domain/ExpenseAmounts.java`
- `java/com/example/splitease/ui/expenses/ExpenseViewModel.java`, `ExpenseFragment.java`, `ExpenseUi.java`
- `res/layout/fragment_expense.xml`, `item_expense.xml`
- `res/drawable/ic_add.xml`, `res/values/expense_strings.xml`

Added tests:

- `app/src/test/java/com/example/splitease/domain/ExpenseAmountsTest.java`
- `app/src/androidTest/java/com/example/splitease/ui/ExpenseNavigationTest.java`

Modified:

- `data/SqliteRepositories.java`, `model/GroupDetails.java`, `util/InrFormatter.java`
- `ui/MainActivity.java`, `ui/groups/GroupDetailFragment.java`, `ui/groups/GroupsFragment.java`
- `res/layout/fragment_group_detail.xml`, `item_group.xml`, `res/navigation/main_nav_graph.xml`, `res/values/strings.xml`
- `app/src/androidTest/java/com/example/splitease/data/SqliteRepositoriesTest.java`
- `app/build.gradle` (version name), `README.md`, `AGENTS.md`
- This report and `docs/screenshots/milestone-5/`

Abbreviated Java paths are relative to `app/src/main/java/com/example/splitease/`; resource paths are relative to `app/src/main/`.
