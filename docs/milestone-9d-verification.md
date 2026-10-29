# Milestone 9D — Firestore groups and membership

Implementation verification: 2026-10-28; finalization review: 2026-10-29 (Asia/Kolkata). Normal application mode remains LocalAuthService/SQLite. Cloud groups are explicitly selected inside the debug Firebase emulator sandbox; release has no cloud mode selector. No production Firestore reads/writes, real test-account creation, rules/index deployment, billing/Blaze, Storage or Functions. Existing SQLite schema 5, credentials, accounting, recurring and receipts remain unchanged. No dependency/Gradle configuration changes.

## Implementation and storage

- FirestoreGroupRepository implements the existing asynchronous GroupRepository. Actor arguments are checked against the current Firebase AuthService session and the SDK identity of the same named app. No UUID validation is applied to Firebase user UIDs. Cloud group IDs remain UUIDs.
- One groups/{UUID} document is the membership ledger: name/type, immutable creatorId/createdAt, current adminId, server updatedAt, checked revision, current memberIds and a retained members map. Member entries snapshot public username, ACTIVE/LEFT status, original joinedAt and latest leftAt. Flat/Trip creation and initial memberships are one atomic transaction/document write. Creator is auto-admin; only admin can add/rejoin or transfer to another current member. Rejoin retains historical timestamps. Deletion is unsupported.
- Initial creation accepts **eight members including creator**, verified against the actual Rules expression/access budgets. More members can be added afterward (the boundary test creates eight, then adds a ninth). No history is pruned; Firestore's 1 MiB document limit is a technical bound on this representation. Large/unbounded groups need a later reviewed representation; no claim of unlimited size.
- Concurrent edits use a revision plus SDK transactions. If Rules rejects a stale revision before SDK retry, at most two additional transactions occur, and only after a server read confirms an advancing revision and continuing admin/current membership. Actual permission loss is not retried. Duplicate adds cannot create duplicate entries or lose unrelated members.
- The existing UID-based group contract needs UID-to-name reads. publicIdentities/{uid} contains only UID/normalizedUsername/displayUsername, maintained atomically with profile and username reservation. Exact authenticated get only; no list, email, UPI or private timestamps. Group creation/add reads this public mirror, never another user's private profile. Idempotent owner profile creation repairs missing legacy demo mirrors and avoids writing an intact mirror. No real data migration.

## Isolation, lifecycle and unavailable finances

- GroupDependencies injects cloud AuthService/GroupRepository only when a reused Groups/CreateGroup/GroupDetail Fragment belongs to cloud_groups_graph. Release hook always returns null; main/root/local screens retain their original SQLite owner. No global mode switch or arbitrary Bundle actor selector.
- The nested graph contains only group destinations; expense/settlement/recurring destinations do not exist. MainActivity's local authentication gate is preserved. The outer cloud Fragment hides content until a FIREBASE profile session is ready and resets its child graph on identity changes. ViewModels retain session-owned drafts and close data subscriptions on stop.
- GroupDetails.accountingAvailable=false explicitly marks cloud financial data unknown. Home hides financial summaries. Detail shows unavailable placeholders, no Add Expense or Settle Up, and a disabled Leave explanation. leaveGroup and observeBalances fail UNSUPPORTED; Rules disallow removing/leaving/deleting. **No unknown balance is interpreted as zero.** Local eligible leaving and accounting stay active.
- Home uses one memberIds array-contains actor query listener; Detail uses one group-document listener. No listener/profile read per member. Server-confirmed snapshots only; pending/cache-only data is not shown as authoritative. Initial server deadline is ten seconds. Network/permission errors terminate the stream, clear the UI and expose Refresh. Reconnect requires Refresh/resubscription after a terminal error; not automatic offline editing. Account changes cancel queued results and listeners.

## Candidate Rules and indexes

firestore/firestore.rules retains 9C profile ownership/username uniqueness and adds private group membership reads, creator/admin creation checks, registered-member snapshot validation, immutable identities, request-time timestamps/revisions, admin-only additions/transfers, historical retention and default denial of unimplemented paths. Membership subcollections cannot act as a second authorization source. Malicious direct SDK operations are tested, not merely UI restrictions. Candidate rules were loaded only in the emulator; production deny-all rules remain unchanged.

firestore/firestore.indexes.json has no composite indexes and exempts the unqueried members map. Retain the default array-contains index for memberIds. Home sorts creation time/ID locally, so it does not need a compound index. Emulator tests do not prove production index configuration. See [Firebase indexing](https://firebase.google.com/docs/firestore/query-data/index-overview) and [Rules conditions/limits](https://firebase.google.com/docs/firestore/security/rules-conditions).

Read/write patterns (logical SDK work, plus Rules-dependent reads/retries where applicable):

- Home: initial matching groups, then changed documents; Detail: one document on subscribe/change. Background/navigation closes screen subscriptions.
- Create: new group-ID existence read plus N public-identity reads, one group write, then a server read returning committed timestamps. Rules validate initial identity snapshots.
- Add/rejoin: group + target public identity read, one group write. Transfer: group read + one group write. Confirmed contention can add a fresh read and bounded retries.
- Profile creation/display-case update now keeps private profile, username reservation and UID mirror in one transaction. No directory collection scans or account email lookup.

## Verification results

| Layer | Result/evidence |
| --- | --- |
| Focused domain tests | **5/5 passed**, included in the 56 unit tests. Cover opaque/sorted UIDs, creator inclusion, duplicates/self/invalid identities, eight-member initial boundary, bounded names and explicit unknown-accounting state |
| Actual SDK + emulator groups/Rules | **8/8 passed**, included in the 28-test combined run (67.272s). Cases: atomic Flat/Trip creation and exact normalized search; independent A/B clients receive group changes; concurrent distinct/duplicate adds; transfer/creator/leave limits; raw unauthorized access/self-assignment/escalation/history erase/delete/financial writes; eight-member boundary then ninth; trusted historical fixture and metadata-preserving rejoin; offline/error/retry and queued cancellation; account switching and auth-service restoration |
| Cloud UI | **1/1 passed**, 9.011s: existing screens create a trip, select registered member, transfer admin with confirmation, recreate, inspect disabled financial actions, reject expense navigation, use Back, switch accounts, see only shared group and add a third member as new admin. Landscape/dark with 1.3× font scale; screenshot visually reviewed |
| Force-stop/relaunch | **1/1 passed**, 2.246s: different process PID, SDK session/profile restored, exact shared group/admin/three members loaded from server, unknown accounting/disabled leave retained, local/default Firebase identities remained separate; fixture deleted and cloud logged out |
| Profile privacy/uniqueness + local regressions | **20/20 passed**, completing **28/28 combined instrumentation tests**: profiles/Rules 6, normal navigation 2, local authentication 3, local group navigation 2, expense navigation 4, and SQLite migration/recurring-isolation/receipt-upgrade cases 3 |
| Milestone build/unit/lint | **PASS**: assembleDebug, assembleRelease (unsigned), assembleDebugAndroidTest, testDebugUnitTest and lintDebug; final Gradle run 22s. **56/56 unit tests passed**, no failures/errors. Lint: **0 errors, 12 existing warnings** (9 GradleDependency, 1 AndroidGradlePluginVersion, 1 NewerVersionAvailable, 1 UnusedResources). No new lint warning reported |
| Live/second-device | **NOT VERIFIED** — no live backend/rules/indexes/plan inspection or physical second device. Two independent named Firebase SDK clients on the Android emulator verify realtime behavior; this is not a claim of physical two-device coverage |

Device: Medium_Phone_API_37.0 / Android 17 emulator, emulator-5554. Node 24.15, Java 25, firebase-tools 15.34.1, Firestore emulator 1.22.0; app remains Java 17/minSdk 24/SDK 37 with existing pinned SDKs. API 24, physical devices and exhaustive accessibility/device-size coverage remain outstanding. Font/night settings were restored. No user SQLite data was cleared.

The two cloud UI/restoration tests ran separately from the combined 28: **30/30 unique instrumentation tests passed** overall. The UI run retained its fixture for the cold-restart test; its alternative immediate-logout-at-cloud-root branch was not executed. Account switching/logout in the sandbox and session-driven navigation have the coverage described above, not exhaustive coverage of every navigation branch.

Finalization reused these recorded results without rerunning tests or changing application code. Existing unit-test XML was inspected and still totals 56 tests with zero failures/errors. Earlier temporary `/tmp/m9d-*` console logs are no longer available, so instrumentation/build durations above are retained execution records, not newly reproduced results.

## Finalization review — 2026-10-29

- Reviewed tracked changes and the new-file inventory for scope, isolation, temporary artifacts and sensitive material. No accidental sensitive files or deployment commands were found; legitimate implementation/tests remain uncommitted. `git diff --check` passes.
- `app/google-services.json` is ignored and **not tracked**. Firebase/Firestore debug logs remain ignored; no receipt photographs, credential exports or emulator exports were added to the change set. Fixed demo test credentials are emulator fixtures only.
- SplitEaseApplication still constructs LocalAuthService and SqliteRepositories. Release cloud injection returns no dependencies; the debug child graph contains only group destinations. Financial UI remains explicitly unavailable and cloud leave/balance operations remain UNSUPPORTED.
- No production rules/index deployment was performed. This is an execution/change-review statement, not a fresh inspection of the live console or its active rules. Candidate rules require separate approval; no live access was used for finalization.
- Process inspection found no running Firebase CLI, Firestore emulator or matching temporary 9D server processes. Nothing was terminated or deleted, and no unrelated processes were touched.
- No new completion blocker found. Live deployment/plan verification, API 24 and physical/two-device coverage remain unverified prerequisites/limitations below, not passing results.

## Defects found and resolved

1. Full initial-row schema validation exceeded the 1000-expression Rules budget at eight members. Exact initial member-map equality removes repeated schema evaluation while retaining every required field/value check. The actual eight-member create/ninth-member add boundary passed after the fix; the bound was not justified from text review alone.
2. Competing group updates may produce PERMISSION_DENIED on stale revision before SDK optimistic retry. Added bounded retries only for a server-confirmed newer revision with continuing admin authority. Verified distinct adds both survive and duplicate adds have exactly one winner.
3. Old listeners can lose permission before the auth-state callback arrives. SDK identity checks and delivery-time cancellation suppress stale values; logical prior session identity is tracked separately so account changes release sources promptly. A permission error caused by an identity switch is reported as cancellation.
4. Profile UID mirrors are added atomically instead of allowing group repositories to read private profiles or trust UI-supplied names. Profile privacy/uniqueness regression includes public mirror shape, unauthenticated/list and cross-owner write denials.

Historical status fixture: setup uses a restricted localhost/demo admin endpoint to seed a previously departed member, because client leaving is intentionally disabled. Actual reads/rejoin/history-rewrite assertions use normal authenticated SDK clients and candidate rules. No production history or balances were manufactured.

## Reproduction

```sh
npx --yes --package firebase-tools@15.34.1 firebase emulators:start \
  --only auth,firestore --project demo-splitease-auth --config firebase.emulator.json
adb -s emulator-5554 reverse tcp:9099 tcp:9099
adb -s emulator-5554 reverse tcp:8080 tcp:8080
./gradlew assembleDebug assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.example.splitease.firebase.FirestoreGroupsEmulatorTest \
  -e groupsEmulator true \
  com.example.splitease.test/androidx.test.runner.AndroidJUnitRunner
```

Group/profile Rules fixtures clear only the dedicated **demo emulator** Firestore database; Auth fixture accounts are reused. All assertions use ordinary SDK auth, except explicit fixture reset/historical seeding. Opt-in tests skip without flags. No real Firebase login is needed. SDK test accounts and logs/exports must never be used against production.

CloudGroupsNavigationTest uses groupsEmulator=true. Add keepCloudGroupFixture=true to retain only UID/groupID/PID in cache after the normal Activity stop flushes SDK persistence. Do not uninstall or clear app data between phases:

```sh
adb -s emulator-5554 shell am force-stop com.example.splitease
adb -s emulator-5554 shell am start -n com.example.splitease/.ui.MainActivity
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.example.splitease.firebase.CloudGroupsColdRestartTest \
  -e cloudGroupsRestart true \
  com.example.splitease.test/androidx.test.runner.AndroidJUnitRunner
```

The cold test validates the distinct PID and server state, then logs out and removes the credential-free fixture. Stop emulators/remove only the 8080/9099 ADB reverses afterward. Generated screenshots remain temporary test evidence, not user/private receipt data.

## Deployment prerequisites and remaining scope

- Review/approve Rules and index changes separately before any live deployment. Verify the owner-controlled project is still Spark with no billing account, intended Auth provider and default memberIds array index. Keep current production deny-all rules until that approval. No deployment attempted.
- Confirm the one-document representation and initial-create bound suit planned group sizes; future scale/pagination changes need a reviewed authorization design. Do not prune history to bypass document limits. Public-name snapshots update on join/rejoin; no fan-out of profile-name edits to old groups.
- Existing 9C demo profiles missing public mirrors can repair via idempotent owner profile creation. No production profiles were created in 9A–9C; no local account/group/receipt migration is implemented. Externally deleted/abandoned Auth accounts retain 9C's pending/administrative-cleanup limitations.
- Cloud leaving remains unavailable until an approved server-enforceable accounting eligibility implementation. No cloud expense, settlement, recurring, payment or receipt repository was started in 9D. Normal app remains local; future production selection must coordinate auth/data/navigation safely. No Blaze, billing, Storage or Functions.

README and AGENTS document the boundary. Stop after 9D.
