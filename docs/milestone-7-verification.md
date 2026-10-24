# Milestone 7 verification

23 October 2026. Scope: local recurring shared bills, monthly cycle history, contribution status and reminders. Existing ordinary expenses/settlements and authentication are preserved.

## Implementation

- Recurring Home lists bills across current groups with an optional filter. Bill settings support name, exact positive INR amount, group, participants, handler, due day, reminder lead (0–31 days), active/inactive status and deterministic equal-share preview. Current members may create/edit and toggle any contribution after confirmation.
- Bill Detail shows the current month, paid progress and history/upcoming snapshots. Paid means the share was contributed to the cycle handler. Status changes never create accounting records or modify net balances.
- RecurringViewModel obtains identity from AuthService, uses SavedStateHandle for account-bound drafts, observes membership-scoped RecurringSnapshot, closes subscriptions and suppresses stale results. The Activity guards the bill destination and notification payload. Fragments contain no SQL or scheduling implementation.
- Repository operations generate cycles transactionally, backed by the existing unique bill/year/month constraint. A persisted cursor fills creation/current and missed UTC months; next month is included when its reminder window opens. Days beyond month end clamp to its last day. Existing amount, handler, participants/shares and due date remain immutable.
- Missing months use the current template, with old-template backfill performed before edits. There is no template-version history. Inactive bills generate nothing; activation resumes in the current month, skipping inactive gaps. Departed template identities pause generation with a visible correction message; history/deactivation remain available.
- Schema **4** adds only recurring_schedule and recurring_reminders. Migration preserves users, groups, expenses, settlements, cycles and paid flags; existing bills anchor at the earliest known cycle or upgrade month without inventing earlier history. Receipt identity is composite cycle/recipient; entity IDs remain UUIDs.
- Added WorkManager **2.12.0**. Java 17, minSdk 24, compile/target 37, AGP 9.2.0 and Gradle 9.4.1 retained. Version name **0.7.0**.

## Reminder behavior

A unique 12-hour periodic worker and immediate resume/update checks run without exact alarms. WorkManager handles normal process death and reboot rescheduling; Android may defer execution. Force-stop prevents background work until reopening. Cycles are generated even when notification permission is denied.

Workers compare their recipient with the restored AuthService identity. Only that current member’s unpaid cycle contribution can produce a reminder during the lead window through the due date. Runtime permission, global notifications and channel settings are checked before durable claims. Bill/group/due date are shown; public lock-screen content is generic. Taps carry bill/recipient, require the same signed-in account, and load only authorized bill data.

Account changes cancel old work/notifications; paid, deactivated or inaccessible bill notifications are pruned. Startup clears visible reminders conservatively before session validation. Durable cycle/recipient receipts prevent repeats across restarts, toggles and account switching. Receipt claims precede posting, so a crash, cancellation or permission race may miss a reminder rather than duplicate it. No exact-once delivery guarantee is made.

Phase 1 cannot notify all local accounts simultaneously or reach other devices: each account receives only eligible reminders while signed in, and may miss a window while signed out. Completed/overdue cycles are available in-app; no overdue catch-up notification is sent beyond the due date.

## Automated verification

Device: Medium_Phone_API_37.0, Android 17 / API 37.

| Check | Result |
| --- | --- |
| Focused RecurringSchedule tests | 3 passed |
| Focused SQLite and recurring UI tests | All cases passed after corrections |
| assembleDebug | Passed |
| testDebugUnitTest | **23 passed**, no failures/errors |
| connectedDebugAndroidTest | **35 passed**, no failures/errors/skips |
| lintDebug, final targeted rerun | **0 errors, 12 pre-existing warnings** |
| installDebug | Passed |
| git diff --check | Passed |

Full build/local/emulator/lint verification ran once at completion. Three new text-formatting warnings were corrected afterward (localized former-member/bill summaries and progress wording). A misleading empty-history sentence was also clarified to “No other cycles yet.” Targeted build/lint/install checks passed. No financial, repository or scheduling behavior changed after the full run.

Coverage includes exact shares/remainders, clamped/leap dates, reminder boundaries, creation/editing/deactivation/reactivation, missed-month backfill/idempotence, snapshot/paid preservation, outsider rejection, another member’s paid updates, departed template handling, persistence on repository reopen, v1/v3 migrations, accounting/recurring isolation and existing M1–M6 regressions. UI tests cover form drafts/recreation, current-month contribution rows, account switching, forbidden bill access, light/dark recreation and logout navigation boundaries.

The real background worker is exercised with notifications denied (successful work without claims/delivery), then allowed (one notification). Tests check recipient isolation, receipt deduplication, notification PendingIntent navigation and cancellation on logout. Focused failures exposed test timing/case assumptions and Activity launch-intent replacement; those were corrected and the full suite passed.

Lint retains 9 GradleDependency, 1 AndroidGradlePluginVersion, 1 NewerVersionAvailable and 1 UnusedResources (brand_teal). The previous Recurring placeholder UnusedAttribute warning disappeared with its replacement. No unrelated dependency upgrades were made.

Reports: app/build/reports/tests/testDebugUnitTest/, app/build/reports/androidTests/connected/debug/, app/build/reports/lint-results-debug.html. APK: app/build/outputs/apk/debug/app-debug.apk. Automated light/dark screenshots are under screenshots/milestone-7/.

## Direct checks and limits

Light/dark screenshots were visually inspected: correct exact shares and 0/2 → 1/2 progress, with readable teal cards. A disposable local account created “Persistent internet” at ₹123.45 through the installed APK, then marked its contribution Paid. Force-stop and cold relaunch restored the signed-in session directly to Home; reopening Recurring and the bill retained the amount and Paid 1/1 status. The emulator remains on this disposable account. Direct evidence is in m7-before-relaunch.png and m7-after-relaunch.png.

Checked scrolling at font scale 1.3 in portrait and landscape, then restored original font/orientation settings. Responsive screenshots are included alongside the automated light/dark evidence.

API 24, physical-device delivery, actual reboot/Doze timing, TalkBack and a conventional full docked keyboard remain outside completed device coverage. WorkManager’s reboot integration is used rather than a custom receiver. Lists/history remain unpaginated, repository updates are last-write-wins, and app-scoped observation only sees writes through the shared owner. Legacy raw observation methods remain trusted internal contracts; UI reads use the authorized projection.

## File inventory

New Java files under app/src/main/java/com/example/splitease/: domain/RecurringSchedule.java; model/RecurringSnapshot.java and BillReminder.java; ui/recurring/RecurringViewModel.java and BillFragment.java; reminders/RecurringReminders.java and RecurringReminderWorker.java.

Replaced ui/recurring/RecurringFragment.java and res/layout/fragment_recurring.xml; added res/layout/fragment_bill.xml and res/values/recurring_strings.xml. Modified SqliteRepositories, SplitEaseDatabase, RecurringBillRepository, SplitEaseApplication, MainActivity, navigation graph, AndroidManifest, app/build.gradle and removed obsolete placeholder strings. Added RecurringScheduleTest and RecurringNavigationTest; extended SqliteRepositoriesTest. Updated README.md, AGENTS.md, this report and screenshots.

WorkManager reference: [official AndroidX release and dependency documentation](https://developer.android.com/jetpack/androidx/releases/work), [deferrable work and scheduling guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

No receipt OCR, Firebase or work beyond Milestone 7 was implemented.


## Additional nine-scenario verification — 24 October 2026

All nine requested scenarios passed within the explicitly described coverage below. Verification used the running API 37 emulator and five focused instrumentation cases, not the full suite. Production application code, dependencies and schema were unchanged in this verification task. Existing uncommitted M7 work was preserved.

The app/test APKs were built and installed with `adb install -r` so existing emulator data was retained. Temporary local accounts/groups were used. The original session and notification permission/AppOp setting were saved privately and restored afterward; existing groups and financial records were not reset. Temporary fixtures remain isolated from the original account and are retained for reproduction.

| # | Requested scenario | Result | Evidence and verification type |
| --- | --- | --- | --- |
| 1 | ₹999 WiFi bill, three members, ₹333 each | **PASS** | Instrumented UI created “WiFi verification” with three participants. Independent SQLite-backed repository reads asserted amount 99,900 paise, exactly three contributions of 33,300 paise, initially Unpaid. UI cards showed ₹333.00. |
| 2 | Mark Priya Paid | **PASS** | UI confirmation changed progress 0/3 → 1/3; a fresh repository snapshot asserted Priya’s stored paid flag true. Screenshot extra-priya-paid.png. The temporary username has a unique `priya` prefix to avoid collisions. |
| 3 | Mark Priya Unpaid again | **PASS** | UI confirmation changed progress 1/3 → 0/3; a fresh snapshot asserted false. Screenshot extra-priya-unpaid.png. |
| 4 | Isolation from ordinary expenses, balances and recommendations | **PASS** | Nonempty control: a ₹900 ordinary expense split among three, plus a ₹100 settlement. Canonical fingerprints compare every expense/split, settlement, exact net balance, total and suggested transfer before bill creation and after Paid, Unpaid, history updates, account switching and restart. Every fingerprint matched. This is stronger than testing an otherwise empty accounting group. |
| 5 | Edit amount; retain historical snapshots/status | **PASS** | Existing focused SQLite test used a controlled repository clock. January–April snapshots retained 10,001 paise and January’s Paid flag; after editing, the newly applicable May cycle used 30,001 paise and the updated handler/participants. No physical wait for calendar rollover was claimed. |
| 6 | Deactivate/reactivate; preserve history/generation rules | **PASS** | Same controlled-clock test: inactive state generated zero cycles through August, then reactivation generated August and skipped inactive June/July. Six snapshots remained, including historical paid status, and survived repository reopening. |
| 7 | Switch account; membership authorization | **PASS** | Logged into temporary Priya’s real account; Recurring listed the bill and detail rendered it. Priya changed another member’s status. An outsider saw no bills and repository mutation attempts for both the bill and contribution were rejected. Screenshot extra-participant-account.png. |
| 8 | Force-stop/relaunch persistence | **PASS** | Direct ADB interaction launched the app, recorded PID 25736, force-stopped it, verified no process remained, then cold-launched PID 25779. UI restored to Groups, then showed WiFi current and historical Paid 1/3 progress. A separate-process test verified the restored actor, exactly two cycles, each amount/share, and individual current/historical paid flags, plus the unchanged accounting control. |
| 9 | Reminder scheduling, permission, isolation and navigation | **PASS** | Focused existing tests verified the periodic WorkManager record, a real denied-permission worker completing without a notification, then an allowed real worker posting one notification in Android’s active-notification list. The bill was due on the emulator’s current UTC day and inside its 3-day lead window. Wrong-recipient delivery was rejected; durable claims deduplicated; the notification’s actual PendingIntent opened the bill; logout cleared notifications and cancelled periodic work. SQLite reminder tests also checked per-recipient claims, Paid suppression, outsiders and persistence. |

### Runs and evidence

- `assembleDebug assembleDebugAndroidTest`: passed. After one test-only setup correction, `assembleDebugAndroidTest` also passed. The initial new WiFi fixture tried registering Priya while signed in; the test now explicitly logs out first, as required by the existing AuthService. This was not an application defect.
- Focused core run: **3/3 passed** — `recurringBackfillSnapshotsPauseAndReactivationAreTransactional`, `recurringRemindersAreRecipientScopedDeduplicatedAndRespectStatus`, `notificationPermissionSchedulingDeepLinkAndLogoutCancellation`.
- Exact WiFi UI/accounting run: **1/1 passed** — `additionalWifiThreeMembersPaidUnpaidIsolationAndAccountSwitch`.
- Explicit cold-restart run: **1/1 passed** — `restoredSessionCurrentAndHistoricalCyclesSurviveColdRestart`.
- Instrumentation logs and PID evidence: [additional verification artifacts](verification/milestone-7-additional/). Screenshots: [additional screenshots](screenshots/milestone-7-additional/). Paid and historical screenshots were visually inspected as well as asserted through UI/repository checks.
- No full build/test/lint regression rerun was needed: application behavior was not changed. The earlier 23 local / 35 emulator / 12-warning result remains historical M7 completion evidence, rather than a claim that the enlarged test suite ran in full today.

For reproduction, run the core methods individually or in one AndroidJUnitRunner class list. Run the WiFi method with `-e keepVerificationFixture true`; explicitly force-stop/cold-launch the app; then run RecurringColdRestartTest with `-e verifyColdRestart true`. The latter test deliberately skips normal suites without that explicit argument because it requires the externally prepared two-process fixture. It checks that its process differs from the preparing test process. Ordinary WiFi test execution logs out normally.

### Defects and limits

No application defects were found or fixed. Changes from this additional task are restricted to instrumentation tests, this report and verification artifacts.

Notification delivery was observed programmatically as an actual OS-posted notification; this was not merely an enqueue/scheduling assertion. Navigation used that notification’s PendingIntent. Manual notification-shade tapping/rendering was not separately verified in this run. Natural 12-hour periodic timing, exact-minute delivery, Doze, real reboot and physical-device/API 24 behavior remain **NOT VERIFIED**. Permission denial was exercised via Android’s notification AppOp, then runtime permission grant plus AppOp allowance; the human permission-dialog flow was not separately exercised. These limits do not imply that scheduled work is guaranteed to deliver at an exact time.

The historical WiFi cycle used for persistence was intentionally created through the existing repository API for the previous month; no emulator clock changes or repeated restarts were needed. Amount-edit/reactivation rollover assertions used the existing deterministic test clock in a disposable database. All recurring paid statuses are synthetic tracker records; no payment was made.
