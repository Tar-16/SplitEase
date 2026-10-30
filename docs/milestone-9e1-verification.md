# Milestone 9E.1 — Firestore expenses

Date: 2026-10-29 (Asia/Kolkata). Local SQLite/authentication remain the application default. All cloud verification targets named demo-splitease-auth Auth/Firestore Emulators, not the real/default Firebase app. No production writes/deployment, billing/Blaze, Storage, Functions or Analytics.

## Implementation

- Reused ExpenseRepository, Expense/ExpenseSplit, ExpenseViewModel/Fragment, GroupDetail and the established navigation/design. Added scoped cloud expense injection; no parallel accounting database or cloud balance calculator.
- UUID expense/group IDs; opaque Firebase UIDs checked against the scoped AuthService and SDK identity. Atomic revision snapshots contain integer-paise amount, payer, unique/sorted participants, immutable per-revision exact shares, category/date, title and optional description. Server timestamps record creation/update metadata. Local models keep their existing constructor behavior; no SQLite migration or local description persistence was added.
- Current members may mutate expenses. Current group membership is checked inside transactions and in Rules. New selections must be current members; edits may retain the original departed payer/participants. Names remain available through retained group membership snapshots.
- Sorted equal splitting reuses the existing domain algorithm. Local Rules.equalSplit still validates UUIDs; the cloud identity entry point accepts opaque UIDs. Cloud-only technical bounds are eight participants and 1,000,000,000,000 paise. Rules unroll every share check, require exact map keys, sorted unique participants and quotient/remainder equality, guaranteeing the total without unbounded iteration or overflow. The amount cap also keeps Rules division/conversion safely below binary numeric precision limits. Rules string comparisons follow the [official reference](https://firebase.google.com/docs/reference/rules/rules.String).
- Expected revisions survive editor recreation/live updates. Transactions reject stale edits/deletes. A rejected concurrent write can make one server read to classify a newer revision as conflict; it never retries an overwrite. Soft deletion increments revision and preserves a tombstone; direct deletion/resurrection is denied. ID-only cloud deletion rejects; version-aware deletion is required. A new editor retains its generated expense ID across retries. An ambiguous acknowledged-lost creation is not silently recreated under another ID: refresh/back to the list reconciles it.
- Expense observation uses a group-filtered query plus parent membership guard, server-confirmed snapshots and a ten-second initial timeout. Terminal errors require Refresh. Stop/account changes close subscriptions and suppress queued callbacks. GroupDetail/editor combine this with the existing group stream. Home does not fan out expense listeners. No claim of offline editing or automatic error recovery.
- expensesAvailable is independent of accountingAvailable. Cloud Expenses tab/editor are wired to the cloud repository, while aggregate balances, settlements, leaving and receipt controls remain unavailable. Local functionality and receipt storage contracts are preserved.

## Finalization assessment — 2026-10-31

**Recommendation: finalize Milestone 9E.1 for its implemented, emulator-isolated expense scope. No specific expense-related blocker remains in the available evidence.** Keep the unrerun monolithic cloud UI journey recorded separately; do not report it as a fully passing test.

### Isolated account-switch/member-add diagnosis

Inspected the captured screenshot, `Confirmation dialog missing: Confirm` stack at former line 99, member-search XML, GroupsViewModel, GroupDetailFragment/GroupScreenFragment and existing 9D coverage. The screenshot showed the Members screen with the switched account already identified as admin, rather than the expected add-member confirmation.

The isolated test deterministically reproduced the defective automation preconditions:

| Point | Expected by the old test | Actual observed in the isolated reproduction |
| --- | --- | --- |
| Enter newly created group-detail Fragment | Visible member-search child means admin detail is ready | Child visibility was VISIBLE, but its parent content was hidden and the new ViewModel had not yet received its queued AuthService state |
| Search/Add readiness | Visible Add child means a result is available | Add also had its XML default VISIBLE state, while actor was not ready and `found == null` |
| Premature Search/Add | Open add-member confirmation | ViewModel correctly ignored Search without an actor; Add correctly returned without a found user. No dialog was created |
| Correctly synchronized actions | Add third member as the switched admin | Validated admin/detail readiness, exact username result and positive-button confirmation succeeded; a SERVER read showed all three members, transferred admin and immutable creator |

Classification: **test automation/readiness defect**, not a demonstrated application/session or Firestore membership synchronization defect. The old test inspected child `getVisibility()` values that do not imply ancestor visibility, lifecycle/session readiness or a completed repository lookup. Fresh Fragment session delivery is asynchronous by design. No application logic was changed to permit those premature actions.

Small test-only correction: wait for loaded, shown group content, validated actor/admin identity and enabled Search; then wait for the exact found user, completed search and enabled/shown Add. These predicates are shared by the isolated regression and the corrected final segment of the original test. No sleeps or timeouts were increased. The isolated regression executes the bad preconditions first, proving that they accept an unready screen, then verifies the proper flow.

### One isolated test and existing coverage

- **1/1 passed**, JUnit **4.139s**, wall time **5.371s**, 120-second cap: `CloudGroupsNavigationTest#switchedAdminAddsMemberOnlyAfterDetailAndSearchAreReady`. One invocation only; no full cloud journey or 55-test rerun.
- Actual SDK/AuthService logout and login switched creator A to member B after a repository ownership transfer. The UI's username search/confirmation added C; a Firestore SERVER read verified member count/identity, admin B and creator A. Local AuthService remained LOCAL/signed out.
- Logs explicitly recorded both `REPRODUCED: old child-visibility waits passed while content hidden, actor not ready, found null; premature search/add ignored` and `VERIFIED: switched admin identity, exact username result, confirmation and three server-confirmed memberships`.
- No ANR or crash: empty crash/events buffers and last-ANR report confirmed no ANR since boot. Only the existing recovery AVD/demo Firebase emulators were used.
- Test APK build passed in 8s. Only instrumentation test code and status documentation changed. Existing 60/60 unit tests, debug/release builds and lint zero errors/12 existing warnings remain valid and were not rerun.
- This behavior also has earlier evidence: the 9D UI report records successful switch-to-new-admin/add-third-member coverage. The recent 55-test combined run includes `transferIsAtomicCreatorImmutableAndLeavingAlwaysUnavailable` (new admin can add, old admin cannot) and `accountSwitchAndServiceRestorationNeverReturnOtherAccountsGroups` (stale stream cancellation and own-group isolation). Those service tests support permissions/session behavior but do not substitute for the new isolated UI reproduction.

Evidence: `/tmp/m9e-account-isolated-result.txt`, `/tmp/m9e-account-isolated-summary.json`, `/tmp/m9e-account-isolated-checkpoints.txt`, and corresponding `*-crash.txt`, `*-events.txt`, `*-anr.txt` diagnostics. The reproduction is retained in the versioned instrumentation test; temporary logs may expire.

### Scope of the recommendation and remaining limits

Expense-specific evidence comprises all six cloud expense SDK/Rules cases in the passing 55-test run, plus the later UI run's completed expense creation/editing, description/draft recreation, remote edit, stale-delete protection, refresh and confirmed deletion/empty-list return. The unrelated group confirmation failure has now been isolated and its test preconditions corrected without changing expense/session/repository logic.

The **entire corrected monolithic journey has not been rerun**. Keep that aggregate result pending separately rather than inventing a green end-to-end result. API 24, physical/two-device coverage, exhaustive accessibility and live deployment/plan/index verification also remain outside the measured coverage. These are not newly discovered expense defects. Cloud balances, settlements, leaving and receipts remain unavailable; SQLite remains default. No production write/deployment, billing/Blaze/Storage/Functions change or commit occurred. Task-owned emulator processes were stopped after the single isolated run.

## Previous focused run — 2026-10-31: expense recovery fixed; later UI check failed

9E.1 remains **not finalized**. The original stale-delete/refresh/delete navigation check now passes. The one permitted full cloud UI run failed later while looking for a member-add confirmation after account switching. No application, repository, rules or financial-calculation code changed in this focused task; no retry or additional instrumentation suite was run.

### Focused test fix and observed state

The old failure was the generic state wait on former line 74. That line combined two expectations: refresh readiness, then a return to Group Detail with an empty expense list after deletion. The old log did not record which predicate or actual view state failed, so its precise historical intermediate state cannot be reconstructed.

Two concrete test defects were identified from the relevant source:

- `ExpenseViewModel.load()` clears the error immediately while `loading` is still true. The editor enables Delete based on `busy`, not `loading`. Therefore, the old `error hidden && Delete enabled` predicate could pass before the refreshed snapshot arrived. The test now waits for the expense form to be shown and the loading indicator to be gone as well.
- The confirmation helper selected any Button with text `Delete expense` across all accessibility windows. Both the underlying editor action and modal positive button use that text; the helper could activate the editor action instead of confirming. It now requests accessibility view IDs and selects only the visible, clickable dialog positive button `android:id/button1`, with the expected label. No sleeps, polling interval or timeout were increased.

The refresh and post-deletion waits are now separate named checkpoints. If either fails, the test records destination, form/progress/error visibility, amount, Delete enabled state and ViewModel loading/busy/done/revision, plus a screenshot. These are instrumentation-only diagnostics, not production logging.

In the new run, the observed passing checkpoints were: refreshed expense form visible, progress/error hidden and Delete enabled; then **Group Detail visible with zero expense rows** after positive-button confirmation. The test also passed its existing checks that the stale deletion left the remote-edited ₹2.05 expense undeleted. This supports a test synchronization/selection cause for the original failure; no application propagation/deadlock defect was demonstrated. The old logs cannot isolate which of the two faulty checks caused the historical run.

### Single bounded run and new blocker

| Check | Result | Evidence |
| --- | --- | --- |
| Test APK build | PASS | `assembleDebugAndroidTest`, 9 seconds; application code unchanged |
| Original expense refresh/delete phase | PASS within the run | Both named checkpoints and stale-delete preservation assertions completed |
| Entire CloudGroupsNavigationTest | FAIL at a later step | **1 run only**, 180-second cap; JUnit 18.229s, wall time 20.246s |
| Later failing assertion | FAIL | `Confirmation dialog missing: Confirm` at line 99, adding the third member after switching to the new admin account |
| Captured actual UI | Members screen, no modal dialog | Screenshot shows the signed-in `Ui_GroupMember` admin and group Members content, instead of the expected add-member confirmation |
| Android health | PASS | Crash/events buffers empty; last-ANR report says no ANR since boot |
| Other verification | Retained | Existing 55/55 combined instrumentation, 60/60 units, debug/release builds and lint zero errors/12 existing warnings were not invalidated by this test-only change and were not rerun |

The later member-search step still uses child visibility checks before clicking Search/Add. The absence of a dialog is established by the captured UI, but the snapshot does not include the search ViewModel's readiness/found state. A premature test action is a possible cause; it is not claimed as the proven cause of this separate failure. Further diagnosis must inspect that specific step. The requested stop-after-failure boundary was respected: no second UI attempt, no broadened suite, no speculative application fix.

Evidence: `/tmp/m9e-ui-fix-result.txt`, `/tmp/m9e-ui-fix-summary.json`, `/tmp/m9e-ui-fix-m9e-dialog-failure.png`, `/tmp/m9e-ui-fix-logcat.txt`, and `/tmp/m9e-ui-fix-{crash,events,anr}.txt`. Receipt photos, credentials and raw emulator exports were not added to Git. Temporary artifacts may expire; the actual outcomes are preserved here.

The previously healthy recovery AVD was reused, without recreating or clearing it. Task-started emulator processes were stopped afterward. SQLite remains default; no production Firebase data/rules, billing, Blaze, Storage, Functions or commits were introduced. Remaining limits include the later cloud member-add confirmation, unexecuted final assertions after that step, API 24/physical-device coverage and production activation checks. No further milestone was started.

## Recovery verification — 2026-10-29

No application, repository, Security Rules, minSdk or architecture changes were made during this recovery task. Existing APKs and passing build/unit/lint evidence were reused. Exactly one fresh temporary AVD was created; neither the original AVD nor its user data was modified.

### Environment and smoke test

- Host: 64 GiB RAM, 91% memory-pressure availability, no recorded swap-ins/outs, approximately 546 GiB free disk space. These measurements do not indicate a host memory/disk shortage at recovery time.
- Emulator 37.2.12, ADB 37.0.1, arm64 hardware acceleration available through Hypervisor.Framework. Installed Android 37.0 Google Play arm64 image revision 6 requires emulator 36.5.11 or newer, so the installed emulator meets that requirement. No other system image or avdmanager was installed; no SDK download was introduced. Existing Java 17 application settings, minSdk 24 and SDK 37 were preserved.
- Original AVD: one CPU core, 2 GiB RAM, 1080×2400 display and automatic graphics. Recovery AVD: fresh writable data, four cores, 4 GiB RAM, 720×1280 at 320 dpi, explicit SwiftShader graphics, headless/no audio/no snapshots. Configuration is isolated at `/tmp/splitease-9e1-recovery-avd`; it uses the same installed image.
- Recovery device: `emulator-5556`, API 37 / Android 17. Boot completed in about 20.6 seconds. The bounded smoke checks passed: package service present, package query responsive, debug APK installation successful, cold Activity launch `Status: ok` in 2.121 seconds, app process alive, and no crash/ANR in the collected buffers. The smoke script's post-boot checks took 6.396 seconds.
- More CPU/RAM, fresh data and renderer/display changes were made together. Recovery therefore does **not** establish which old-AVD setting or image state caused the original failure. The original native asset-finalizer stall's underlying cause remains unknown.

### One-shot runtime results

| Check | Result | Evidence |
| --- | --- | --- |
| Smoke: package manager / install / launch | PASS | All recorded steps exited zero; no crash or ANR |
| Cloud UI test | FAIL — normal assertion, not ANR/hang | One run, 180-second cap; JUnit finished in 37.054s (38.913s wall time). `CloudGroupsNavigationTest` timed out its internal state wait at line 74, in refresh/delete recovery after the stale-delete check |
| Combined cloud/local instrumentation | PASS | One run, 300-second cap; **55/55 passed**, JUnit 56.304s (57.438s wall time), no skipped tests or reported crash/ANR |
| Android health after each test invocation | PASS | Crash/events buffers empty; `dumpsys activity lastanr` reported no ANR since boot |
| Earlier final build/unit/lint | Retained PASS | Debug/release/test APK builds; 60/60 unit tests; lint zero errors and 12 existing warnings. No application changes invalidated them in this recovery task |

Combined coverage: Firestore expenses 6, Firestore groups 8, Firestore profiles 6, normal navigation 2, local authentication 3, local group navigation 2, local expense navigation 4 and SQLite repositories/migrations/isolation 24. Cloud UI was separate and is **not** included in the 55 passing tests.

The cloud UI run progressed through cloud group creation/admin transfer, expense draft Activity recreation, expense creation and editing, description persistence, the remote update to ₹2.05, rejection of the stale deletion and confirmation that the expense remained undeleted. It then failed on a line containing refresh/readiness and the second delete/navigation wait. The recorded stack cannot identify which individual wait on that line failed. Later cloud UI checks for landscape/dark theme, Back and account switching were not reached in this run. Separate combined service/local tests cover their stated areas; they do not prove this remaining cloud UI path.

This was an ordinary JUnit state assertion. No app deadlock, ANR or system crash was observed in the recovery session. Whether the remaining failure is an application-state/navigation defect or a test interaction/readiness issue remains **unknown**. No speculative repository rewrite or extra retry was performed. The corrected cloud UI flow must pass before 9E.1 can be finalized.

### Diagnostics and restrictions

Original evidence still establishes that the earlier installation blocker involved Android System UI/system_server and a missing package service. It does not explain the separate earlier SplitEase bind-startup ANR; that event remains unresolved. Recovery results show that those system failures are not reproducible in this one clean configuration, while a distinct cloud UI failure remains.

Local evidence: `/tmp/m9e-recovery-smoke-summary.json`, `/tmp/m9e-recovery-launch.txt`, `/tmp/m9e-recovery-cloud-ui.txt`, `/tmp/m9e-recovery-combined.txt`, `/tmp/m9e-recovery-tests-summary.json`, and per-run `*-crash.txt`, `*-events.txt`, `*-anr.txt` diagnostics. No new ANR occurred, so application main-thread ANR tracing was not needed. Temporary evidence is not a durable CI archive; exact outcomes are recorded here.

SQLite/local authentication remain default. Cloud balances, settlements, leaving and receipts remain unavailable. Only demo Auth/Firestore emulator data was used; no production write, rule deployment, billing/Blaze change or commit occurred. Recovery-owned emulator processes were shut down after verification; the temporary AVD files remain available for inspection.

## Previous final verification attempt: infrastructure blocked

Finalization on 2026-10-29 stopped at an Android system failure, per the requested no-retry boundary. No new application features or application-code changes were made during finalization. The cloud UI test's premature readiness check was corrected: it now waits for the expense form to be visible and loading to finish before asserting its contents. The earlier check only inspected a child button's default visibility while its parent form could still be hidden.

| Check | Status | Actual evidence |
| --- | --- | --- |
| Debug, unsigned release and instrumentation APK builds | PASS | One final `assembleDebug assembleRelease assembleDebugAndroidTest testDebugUnitTest lintDebug --no-daemon` invocation, `BUILD SUCCESSFUL in 27s` |
| Unit suite | PASS | 60 tests, zero failures/errors/skips, from final test XML. Rerun was required because repository/presentation changes followed the earlier focused-only run |
| Lint | PASS | Zero errors; 12 existing warnings: 9 GradleDependency, 1 AndroidGradlePluginVersion, 1 NewerVersionAvailable, 1 UnusedResources |
| Earlier focused expense instrumentation | PASS across development runs | All six expense cases passed individually across focused runs; this is **not** a final combined-suite pass. Coverage includes create/edit/delete, two-client updates, concurrent/stale writes, raw malicious writes, historical members, account cancellation and the eight-participant create/edit maximum-amount boundary |
| Previously interrupted run | FAILED, subsequently inspected | `/tmp/m9e-boundary-ui3.txt` completed after interruption: 2 tests, 1 pass and 1 failure in 32.045s. Eight-participant create/edit/reopened observation passed. Cloud UI asserted `Cloud dinner` before the form finished loading; this was a test readiness defect, not evidence that a saved expense title was empty |
| Final cloud UI attempt | BLOCKED BEFORE LAUNCH | Clean emulator preparation failed installing the debug APK: `cmd: Failure calling service package: Broken pipe (32)`. The planned UI timeout was 180 seconds. No final UI test started; no retry was performed |
| Final combined instrumentation | NOT RUN — blocked | Current APK installation could not be established reliably after Android's package service died. The planned cloud expense/group/profile and local SQLite/auth/navigation regression run was not started with an uncertain installed APK |
| Final runtime local/cloud isolation | NOT VERIFIED | Source review confirms the boundaries; prior focused runs are retained separately. No claim of a final runtime regression pass |
| Diff/configuration review | PASS | `git diff --check`; local composition root and release hook preserved; google-services.json ignored/untracked; no dependency, SQLite schema or production deployment configuration changes |

### ANR diagnosis and infrastructure evidence

These are distinct observations; a shared root cause is **not** established:

1. Earlier screenshots showed **System UI isn't responding** covering the SplitEase confirmation dialog. Earlier logs showed slow dispatch/delivery in `com.android.systemui`. That obstruction belonged to Android System UI.
2. Earlier exit history separately recorded a **SplitEase BIND APPLICATION ANR** at 10:44:24: its process failed to complete startup. The saved crash buffer was empty, and the available evidence does not establish whether SplitEase startup or emulator/system pressure caused that earlier event.
3. The one clean final session produced direct system-process failure evidence before the final UI test could launch:

```text
11:55:46 am_anr: com.android.systemui, executing SysUiSelectionToolbarRenderService, waited 20611ms
11:58:05 FATAL EXCEPTION IN SYSTEM PROCESS: FinalizerWatchdogDaemon
java.util.concurrent.TimeoutException: android.content.res.ApkAssets.finalize() timed out after 10 seconds
11:58:05 am_crash: system_server
11:58:59 Process: com.android.systemui — DeadSystemException: The system died
Service package: not found
```

The final blocker is therefore in the Android emulator's system processes/package service, not a failed expense assertion. The reason that the Android native asset finalizer stalled remains unresolved; no unsupported attribution to host hardware, a particular app, or an Android bug is made.

Evidence retained locally: `/tmp/m9e-final-build.log`, `/tmp/m9e-final-crash.txt`, `/tmp/m9e-final-events.txt`, `/tmp/m9e-final-package-service.txt`, `/tmp/m9e-final-diagnostics-summary.json`, and existing Gradle unit/lint reports under `app/build/`. Earlier attempt evidence remains in `/tmp/m9e-boundary-ui3.txt` and `/tmp/m9e-exit-info.txt`. Temporary files are not durable CI artifacts; the relevant sanitized findings are recorded above.

### Security/integrity review and remaining verification

- Reviewed exact integer share checks, sorted/unique participants, allowed-member sets, immutable group/creator, revision checks, tombstones and historical payer/participant retention. No additional confirmed critical defect was found in this review. Bounds remain eight participants and 1,000,000,000,000 paise; no checks were weakened to pass tests.
- The development fixes include reducing repeated Rules expressions after the initial eight-member budget failure, waiting for the repository's initial AuthService state, suppressing cancelled deliveries, and freezing the expected revision when a deletion confirmation opens. Final end-to-end verification of the deletion-confirmation race remains pending.
- SQLite/LocalAuthService remain the default composition. Only the named emulator graph injects Firestore expenses. Cloud balances/settlements/leaving and receipt controls remain unavailable; release exposes no cloud selector. This is source-verified, not a substitute for the blocked runtime regression run.
- No production rules/index/data deployment or writes were performed. The live console/plan/rules were not inspected. google-services.json remains ignored and untracked. No billing, Blaze, Storage, Functions, Analytics or new SDK dependency was introduced.
- The prior task-owned emulator PIDs were positively identified and stopped before one clean session. After diagnostics, the final session's Android/Firebase emulators were stopped as well. No unrelated process, database, account, or user file was reset/deleted. APK installation failed, so the emulator's installed app version is not claimed to match the final build.
- To finish: use a reliable Android emulator/device, install the final APKs, run the corrected cloud UI test once (including remote edit during delete confirmation), then the combined cloud/local instrumentation verification. Do not mark 9E.1 complete from builds, unit tests or prior scattered passes alone.

## Limits and deployment prerequisites

- No live deployment/production authorization, owner Spark status or indexes were inspected. Production deny-all rules were not deployed over. Candidate Rules require independent review and explicit approval before any deployment.
- API 24, physical/two-device behavior, exhaustive small-screen/large-font coverage and cloud receipt uploads remain unverified/out of scope. Two named SDK clients on one Android emulator are not two physical devices.
- Cloud leaving remains disabled; no settlements/balances/recurring repository was implemented. The local group history fixture uses the localhost demo administrative endpoint only to simulate a departure; client leaving remains denied.
- Eight-participant/per-expense amount bounds are deliberate server-validation limits, not new local product limits. No persisted calculated balances or aggregate financial summary is provided in cloud mode.
- Current revisions overwrite old values; tombstones preserve deletion identity, not an edit audit. Tombstones count toward group query reads. Group membership remains the existing bounded-document design. A later reviewed design is needed for pagination/large histories.
