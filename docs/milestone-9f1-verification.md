# Milestone 9F.1 — Cloud recurring shared bills

15 November 2026 (Asia/Kolkata). The tracker is available only in the Android debug graph for the named `demo-splitease-auth` Auth/Firestore emulators. The release and normal debug navigation retain SQLite and local authentication. No production deployment or data writes, billing, Functions, Storage, local migrations or experimental ledger changes.

- `recurringBills/{billUUID}`: group ID, title, amount, current handler and sorted participant UIDs, template shares, due day, active flag, revision and next UTC month index. The group membership document supplies authorization; templates can select up to eight participants and use up to 10^12 integer paise.
- `recurringBills/{billUUID}/cycles/{monthIndex}`: one frozen snapshot of month, due day, amount, handler and participants, initially with an empty paid list. Shares derive deterministically from the frozen amount and sorted identities. Due dates clamp to month end. The collection supports no deletes, and Rules limit each status write to adding/removing at most one historical participant. Any current group member may do so.
- A transaction reads the template, membership and month document, then creates the cycle and advances the cursor together. Concurrent/repeated requests reuse the canonical month document. Server UTC bounds eligible months. Template changes require the viewed revision and an active template must catch up before its edit; reactivation skips inactive gaps without rewinding an already generated cycle.
- Read pattern: one group membership listener, one groupId equality template query per visible group, and one cycle listener for the open bill. Generation uses server reads and one transaction per missing month. No composite index or per-person listener is needed. Server-only snapshots, account and SDK identity checks, and listener teardown keep views scoped to the signed-in account.
- Recurring writes do not touch any group doc, financial fence, expense or settlement. Neither recurring shares nor paid statuses represent a payment or enter balance calculations. The existing financial Rules outside the new recurring block were compared byte-for-byte against HEAD.

## Verification

Gradle used the workspace's populated `.gradle-home` cache after the default cache lacked the release lint artifact.

| Check | Result |
| --- | --- |
| `assembleDebug assembleRelease assembleDebugAndroidTest` | Passed |
| `testDebugUnitTest` | 64 passed, no failures or skips |
| `lintDebug` | 0 errors, 1 existing unused `brand_teal` resource warning in the offline report |
| Cloud debug navigation, confirmed bill history, Activity recreation, local reminder controls hidden | Passed, Android API 37 |
| Scoped Rules, rejected incomplete cursor/cycle writes, allowed retry by another member, historical field/participant protection, nonmember and logout isolation | Passed on the focused follow-up |
| Concurrent cycle creation and paid updates, stale edits, reactivation and accounting isolation | Passed after the focused transaction-race correction. Two clients converged on one cycle and both paid updates; the next cursor and historical snapshot were preserved, the parent group document was unchanged, and no expense or settlement was created. |
| Existing SQLite recurring UI regression | **Passed (1/1, 13.206 s)** on the settled API 37 emulator. The earlier package-update interruption and startup ANR are recorded below. |

The initial four-case instrumentation run passed the cloud UI case. The next two cases failed before executing assertions because deleting a named test FirebaseApp left a stale Auth emulator registration in the SDK. The test fixture now signs out and closes its repository/session owners, leaving FirebaseApp cleanup to test process exit. Only those two blocked cloud cases were run again, with a 100-second bound; the passing cloud UI case and interrupted local test were not repeated in that follow-up. The original run was bounded at 165 seconds.

The follow-up passed the Rules case and exposed a real concurrent creation
failure: the emulator evaluated the losing write against the winner's new
cursor and returned permission denied before the SDK could retry. The adapter
now reconciles only against a server-confirmed canonical cycle visible to the
same account. A status transaction retries at most once on a Rules denial,
only if an authorized server read shows that the paid set changed. Checks and
writes still use the same membership, cursor and paid-set Rules. The one
affected concurrency test passed in 10.126 seconds (85-second bound). The
debug and test APKs and lint passed again after this debug-only fix. No full
instrumentation suite was run; the three cloud cases passed across the bounded
run and its affected follow-ups.

## Limits

Generation runs on tracker load/resume/refresh, not in the background. It processes up to twelve missing months per bill per pass; longer backlogs can need repeated refreshes. No cloud notifications, reminder window, cross-device notification deduplication, or offline write mode. Local WorkManager and its permission/scheduling implementation were untouched. The loaded history is not paged. Full calendar rollover, network-disconnect and physical-device/API 24 coverage were not run; interrupted atomic writes were exercised at the emulator Rules boundary, not by forcibly terminating the app during a transaction. No live access or production authorization is implied.

## Final verification follow-up

The earlier SQLite test stopped with the instrumentation runner's generic
`Process crashed` status. Android's captured process-death events identified
the Google Play services package update as the reason both SplitEase processes
were killed; the interrupted run did not produce a test assertion result.

The repeat used a read-only API 37 AVD with no snapshot, verified boot completion
and a responsive package manager, and disabled network access before installing
the existing APKs so a package update could not interrupt the test again.

Source review confirmed that the SQLite repository, application composition and
WorkManager worker/scheduler files remained unchanged. The shared UI still
refreshes the local scheduler after local saves and paid-status changes; the
cloud UI neither exposes notification controls nor calls the local scheduler.
This review did not rerun notification delivery; the earlier permission,
delivery, deduplication and logout checks are recorded in the Milestone 7
verification report.

The candidate recurring Rules require the demo token audience and current group
membership for existing bill and cycle access. A status update can change only
the paid list, with no duplicate or nonparticipant entries and at most one
participant's status changed. Cycle creation requires the canonical month ID,
an active template, current template members, an unpaid initial snapshot of the
template and its paired cursor advance. Historical cycle fields cannot be
edited or deleted. Existing SDK emulator cases cover the relevant denials and
allowed writes, so no new cloud tests or Firebase emulators were needed. The
repository only writes bill and cycle documents; the accounting repository
reads expenses and settlements, and the prior concurrency case verified the
group document was unchanged and no expense or settlement was created.

No production Firebase or billing operation was performed in either 9F.1
verification session. The demo scope and production financial hold remain
unchanged. This was a local verification, not a live console audit.

The first verification follow-up selected
`RecurringNavigationTest#createEditTrackRecreateAndSwitchAccounts` with a
125-second bound. Both APK installs succeeded. The runner returned
`Process crashed` before emitting a test-start or assertion result. Android
exit-info recorded `BIND APPLICATION ANR` (PID 2131). The captured main thread
was runnable inside Android ART's APK/Dex decompression, and several unrelated
Android and Google processes hit startup ANRs during the same boot. This
evidence points to emulator startup contention; it does not establish the
exact underlying cause or a SQLite recurring-feature failure. The test was
not retried. The recorded 64 unit tests, three cloud cases, debug/release
builds and lint (zero errors, one existing warning) were not rerun.

That follow-up ended with sign-off held pending the SQLite result.

## Final SQLite regression

A disposable, read-only API 37 AVD ran offline with four virtual CPU cores
and 6 GiB of RAM. After a quiet 40-second post-boot window, Android's package
manager responded, both existing APKs installed, and SplitEase cold-launched
successfully in 1.825 seconds. No ANR or package update was reported during
preflight.

Ran only `RecurringNavigationTest#createEditTrackRecreateAndSwitchAccounts`
with a 125-second bound. **Passed (1/1) in 13.206 seconds.** The test covered
creating and editing a SQLite bill, exact monthly contributions, marking a
contribution paid, preserving its status and historical amount after editing
and deactivation, Activity recreation, and navigation across member and
unrelated accounts. Notification delivery was not rerun. No app or test code
changed; the earlier build, lint, unit and cloud results stand.

**Recommendation: sign off Milestone 9F.1 for the isolated demo scope.** No
production deployment, Firebase data write, billing change or commit was made.
