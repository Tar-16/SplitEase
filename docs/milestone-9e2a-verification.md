# Milestone 9E.2A — cloud settlements and derived balances

Date: 2026-10-31 (Asia/Kolkata). Scope is the explicit named demo Auth/Firestore emulator graph. Normal application authentication/accounting remains SQLite. No production write, rule/index deployment, billing/Blaze, Storage, Functions, Analytics or automatic commit.

## Implementation

- Added FirestoreSettlementRepository behind SettlementRepository; reused Balances, the deterministic greedy SettlementCalculator and existing Settle Up/Group Detail UI/ViewModels. Added an opaque-UID calculator entry point; local UUID validation remains intact. All arithmetic uses checked long paise. Recommendations are not completed payments or a proof of optimal transfer count.
- Full/partial payment records and amount/date corrections, expected settlement revision, immutable group/parties/recorder/createdAt, current-member permissions and historical names. New records require the current payer or payee. Any current member can edit. Cloud deletion is deliberately unsupported to retain records; no edit audit.
- Duplicate warning: same group/payer/payee/amount/UTC day, excluding edited ID; explicit acknowledgement repeats transactional validation. New record limit is the current suggested pair amount; edit limit excludes the old record and permits max(old amount, remaining pair capacity). Per-record bound is 1,000,000,000,000 paise; zero/negative/noninteger amounts are rejected.
- Group financialRevision/financialRecord and document financialVersion form an atomic shared fence for every expense/settlement mutation. Rules reject unpaired writes and protect group membership/creator/admin fields. Before/after group server reads bracket both history queries; a changed financial or membership revision rejects a mixed snapshot. Save transactions compare those same revisions and expected record revision. No silent retry overwrites stale data.
- Observers watch group + expenses + settlements, coalesce invalidations and reload authoritative snapshots. Initial deadline ten seconds; up to two conflict reloads; terminal errors require Refresh. Subscriptions close with view/account lifecycle and suppress queued delivery. There is no offline editing claim. Large histories currently require full queries; default groupId single-field indexes suffice. Home avoids accounting-listener fan-out.
- Group Detail exposes actual expense total and net balances, including settlements and retained historical identities. Cloud leaving remains API/Rules/UI-disabled regardless of zero balance; 9E.2B is not implemented. Cloud UPI and receipt controls remain hidden. Local session/default repositories, SQLite schema/data, recurring isolation and receipt storage are unchanged.

## Security boundary requiring live review

Rules validate access, current membership, party/recorder identity, permitted fields, positive bounded integer amount/date and atomic revisions. They **do not** compute the net-debt ceiling or duplicate heuristic over unbounded history. Those policies are enforced in the asynchronous repository against a version-fenced snapshot; an authorized raw SDK caller can bypass them while still submitting a shape-valid payment claim. The security review below reproduces this gap. Candidate Rules now restrict **all expense/settlement writes and financial-fence advances to the `demo-splitease-auth` token audience**. This is a production security hold, not server-side debt validation. No unreviewed rules were deployed; keep live deny-all rules unchanged.

## Verification

- Focused unit/build: calculator/checked-arithmetic tests and debug/test APKs passed.
- Focused actual SDK/Rules: **6/6 passed**, 10.686s. Covers full/partial balance recalculation, expense edit/deletion effects, duplicate acknowledgement, any-member amount/date edit, immutable parties, stale/concurrent writes, historical parties, reopened repository, actor switching/cancellation, nonmember access and raw adversarial paired/unpaired writes. Admin REST is used only for dedicated-demo fixture reset/legacy departure; assertions use ordinary authenticated SDK clients.
- Focused boundary/UI: **2/2 passed**, 6.320s. Verifies the maximum eight-share expense create/edit boundary under the new fence; cloud UI records a partial payment, edits it, recreates Activity, refreshes balances, navigates Back and renders dark/landscape with leaving disabled and UPI hidden.
- Final bounded build/unit/lint: debug, unsigned release and test APK builds passed; **64/64 unit tests**, zero failures/errors/skips; lint **0 errors, 12 existing warnings** (9 GradleDependency, 1 AndroidGradlePluginVersion, 1 NewerVersionAvailable, 1 UnusedResources). The single full Gradle completion invocation passed in 26s. Builds/lint were rerun after the specific fixes below (19s and 20s); unchanged pure-Java unit tests were not repeated.
- One milestone instrumentation invocation, 300-second cap: **62/63 passed**, 64.992s JUnit / 66.202s wall. Cases: settlements 6, expenses 6, groups 8, profiles 6, isolated account-switch UI 1, local navigation 2, local authentication 3, local group navigation 2, local expense navigation 4, local settlement navigation 1, and SQLite/migration/isolation 24. No skips or ANR/system crash. The sole failure was the expense-observer regression described below; this run is not presented as 63/63 passing.
- After the observer fix: only the six affected expense stream/authorization/history cases plus the cloud settlement UI were run: **7/7 passed**, 9.697s JUnit / 10.863s wall, 180-second cap.
- After explicit pending/cache exclusion in the accounting read barrier: only the six settlement SDK/Rules cases plus cloud settlement UI were run: **7/7 passed**, 12.619s JUnit / 13.920s wall, 180-second cap. No ANR/crash; the full milestone suite was not repeated. All known test failures are resolved by the applicable focused verification; the final whole-suite aggregate was not rerun.
- Screenshot evidence is synthetic test UI only: app-private `cloud-settlements-dark.png`, pulled to `/tmp/m9e2-cloud-settlements-dark.png`. Theme transition, landscape, loading completion and hidden UPI were automated assertions. This does not establish exhaustive visual/accessibility coverage.

### Defect found and fixed during milestone verification

The new financial fence updates the parent group for each expense change. FirestoreExpenseRepository's existing membership guard re-emitted its cached expense list on every group snapshot, so a create could publish the old empty list before the expense query delivered the committed row. `createEditDeleteAndTwoClientRealtimeRemainSeparateFromBalances` caught this as an IndexOutOfBoundsException.

The fix keeps every membership/identity check, but the group listener emits rows only on initial readiness. Subsequent expense lists come from server expense-query snapshots; group changes still revoke access immediately. No test assertion was relaxed. All six affected expense cases passed afterward.

Final review also made the server snapshot barrier explicitly reject `hasPendingWrites` or cache metadata on all document/query reads. Source.SERVER does not authorize presenting an unconfirmed local overlay as final financial state. The affected accounting and UI cases passed after that guard.

### Files and compatibility

New production files: FirestoreSettlementRepository, FirestoreFinancialRevision, CloudFinancialException and cloud settlement strings. Settlement gained a cloud revision overload while retaining its old constructor/default zero. The calculator gained an opaque-identity entry point around the unchanged greedy algorithm. Scoped dependencies/debug navigation and existing financial Fragments/ViewModels were integrated; no SQL/Firebase query was added to production UI/domain code. New unit/instrumentation tests are separate files; original expense tests were preserved. Existing cloud UI fixtures now perform paired revision-fenced writes; the optional cold-restoration fixture expects available accounting while leaving stays disabled.

No Gradle/dependency/toolchain changes. Java 17, minSdk 24, SDK 37 and SQLite schema 5 remain. Group financial metadata is additive; existing expense data remains readable and receives financialVersion on its next write. Older clients cannot mutate under the new paired-write candidate rules; no live rollout or data migration was attempted.

Local logs: `/tmp/m9e2-final-build.log`, `/tmp/m9e2-final-instrument.txt`, `/tmp/m9e2-final-summary.json`, `/tmp/m9e2-affected-result.txt`, `/tmp/m9e2-confirmed-result.txt` and matching diagnostic files. Exact counts/outcomes are retained in this report; temporary logs can expire. `git diff --check` passed and google-services.json remains untracked. Task-owned Android/Firebase emulators were stopped after verification; no unrelated process or user data was removed.

## Limitations

Live authorization/plan/index/provider configuration, API 24, physical/two-device, exhaustive large-font/device-size and force-stop/process restoration coverage are not established by these tests. Existing 9E.1/9D reports retain their own evidence; they are not silently upgraded to 9E.2A coverage. No cloud settlement deletion, UPI, recurring, receipt upload or leaving feature was added. Prior aggregate cloud UI journey is not rerun as part of the focused settlement UI case.

## Security boundary review — 2026-10-31

**Decision:** ready for emulator-stage finalization **as a demo-only prototype with a production security hold**. **Not safe to enable production cloud financial writes.** The production blocker is unbounded-history settlement ceiling validation, not an unresolved emulator/test failure. No 9E.2B work or live deployment is authorized.

### Enforcement inventory

| Invariant | Firestore Rules | Android repository/domain |
| --- | --- | --- |
| Settlement amount is integer paise, 1–1,000,000,000,000 | Enforced | Enforced; long input |
| Settlement date | Nonnegative integer; no additional future-date/business restriction | Same nonnegative epoch-millisecond rule |
| Current-member reads/mutations; new recorder must be payer/payee; no forged recorder | Enforced against authenticated UID and group membership | Scoped AuthService + SDK UID, repeated transaction checks |
| Immutable group, payer/payee, recorder, creation time; edits only amount/date plus controlled metadata | Enforced; deletion denied | Enforced; historical parties retained |
| Exact sorted equal expense shares, unique/current or retained original participants | Enforced, maximum eight participants | Existing CloudExpenseValidation/equalSplitIdentities |
| Stale record edits and paired financial revisions | Enforced; ordinary group updates now cannot alter/remove financial metadata | Expected record revision plus fenced financial/membership snapshot; no blind overwrite retry |
| New-payment suggested-pair ceiling; old-record-excluded edit ceiling | **Not enforced for demo claims** | Enforced against version-fenced history |
| Duplicate-looking payment warning | **Not enforced** | Same group/parties/amount/UTC day; explicit acknowledgement allows proceeding |
| Checked derived balances/conservation; exclusion of recurring data | No history aggregation or aggregate monetary bound | Checked long calculations; invalid/overflowing reads fail instead of wrapping |
| Aggregate-history overflow prevention at expense-write admission | **Not enforced** | **Not enforced** by expense save across the entire history; accounting reads detect overflow. Per-record caps are not an aggregate bound |
| Independent proof that money actually changed hands | **Neither layer** | Manual claims only; no payment verification claim |
| Production expense/settlement writes | **Denied by demo-audience gate** | Normal app remains local; debug cloud composition stays emulator-only |

Revision checks prevent stale cooperative edits and unpaired writes; they do not prove a submitted amount is financially justified. Any authorized member may deliberately change permitted fields under the stated edit policy. Duplicate acknowledgement is a presentation policy, not a fraud-prevention invariant, because duplicates are intentionally allowed after confirmation.

### Direct-write evidence and narrow corrections

1. **Confirmed repository/Rules mismatch:** in a synthetic group with 100 paise debt, the repository rejected a 150-paise payment. An ordinary authenticated raw SDK batch (no admin bypass) accepted it, reversing the creditor's net to -50 paise. The repository then rejected an edit to 250 paise; a raw current-member edit accepted it, producing -150 paise. A second identical-looking raw 250-paise claim succeeded without acknowledgement, producing -400/+400 paise net balances. A raw payment in a zero-debt group also succeeded. Negative/fractional dates and stale settlement revisions were denied. Baseline **1/1 passed**, 3.656s: passing here means the bypass was reproduced, **not** that the policy was secure.
2. **Confirmed fence-removal defect:** the admin membership-update branch prohibited the presence of financial metadata instead of prohibiting changes to it. A full group replacement could strip the financial fence while incrementing the membership revision. The new regression failed with `Rules must deny` because the malicious replacement succeeded (**0/1 passed**, 1.303s). Fixed `modifyGroup` to reject any diff affecting financialRevision/financialRecord. The corrected test denies the removal and verifies a legitimate admin transfer preserves both financial fields. This also permits normal group updates after financial activity without resetting the fence.
3. **Production hold:** `demoFinancialWritesOnly` gates both sides of every paired financial write using reserved ID-token audience `demo-splitease-auth`. A real production project's valid Firebase ID token cannot carry that demo audience; missing/mismatching claims fail closed. No client preference, custom claim toggle or release selector enables it. The emulator test's genuine matching-audience SDK token successfully committed a control claim; an explicitly emulator-only unsigned token with the same identity but another audience received HTTP 403, left the fence unchanged and created no second record. This tests Rules claim handling, **not live token verification or live deployment**. Never remove/broaden the gate merely to activate cloud mode.

### Focused verification after correction

- Actual Auth/Firestore emulators, existing API 37 temporary AVD; one bounded 180-second invocation: **23/23 passed**, 26.481s JUnit / 27.643s wall. Nine settlement tests (six existing plus the three security regressions), six expense tests and eight group tests. Coverage includes concurrency, stale revisions, split integrity, historical membership, authorization, switching/restoration, realtime updates, fence preservation and non-demo audience rejection. No production endpoint was used.
- Test APK builds passed (final compile 7s); Rules hot reload compiled successfully. Production Java, Gradle, UI, SQLite and domain calculations were not changed during this review. Prior 64/64 unit, debug/release and lint evidence above is reused; full suite/build/lint were not repeated for Rules/test/documentation-only changes.
- Logs: `/tmp/m9e2-security-baseline.txt`, `/tmp/m9e2-security-fence-before.txt`, `/tmp/m9e2-security-affected.txt`, `/tmp/m9e2-security-affected-build.log`, `/tmp/m9e2-security-firebase.log`. Temporary logs may expire. No currently failing focused check remains; the intentional pre-fix failure is retained as evidence.
- Review changes are limited to candidate Rules, three focused instrumentation regressions, README, AGENTS and this report. Local SQLite remains default; cloud leaving remains disabled. google-services.json is ignored/untracked. No production rules/data deployment, account creation, billing/service change or commit was performed.
- `git diff --check` passed. Only the Android/Firebase emulator processes positively identified as started for this review were gracefully stopped; their exit was confirmed. No user data or unrelated processes were removed.

### Required secure alternative before production

Firestore Rules cannot query/sum an arbitrarily sized financial history or execute the greedy matching algorithm over it within their bounded document-access/evaluation budget. The shared revision fence proves atomic ordering, not completeness or correctness of a client-supplied financial proof. Adding a client-written net balance alone would not solve this.

Keep SQLite financial functionality and the demo-only cloud prototype for now. A possible Spark-compatible future design is a **strictly bounded, Rules-verifiable authoritative ledger/state transition**: complete bounded member/financial state, exact per-write delta validation, conserved checked totals and immutable identities, atomic revision pairing for every expense/edit/delete/settlement, with explicit hard limits and rejection when limits are reached. Greedy-pair ceilings would also need a bounded verifiable representation or an explicitly approved policy change; a pair's debtor/creditor capacity alone is not the same rule. Such a redesign needs its own specification, adversarial proof/tests and review; it is **not implemented or established safe here**. No Functions/trusted paid backend, billing upgrade or weakened Rules is proposed. Until a server-enforceable design is approved, production financial writes and cloud leaving must remain disabled, and live deny-all rules must remain unchanged.
