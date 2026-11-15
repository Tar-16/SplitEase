# Bounded financial posting POC results

Latest assessment: **2026-11-09 — ready for the NEXT isolated security validation
stage; not production-ready.** The record-ownership fix below passed five focused
cohorts, the seven affected cohorts and a final independent history/account audit.
Keep the production financial hold and local application behavior unchanged.

The 2026-11-08 baseline below is retained as historical evidence. Its failure and
stop recommendation describe the earlier global-record version, not the corrected
group-scoped POC. See “Record ownership fix — 2026-11-09” for the current design,
tests, measured budgets and remaining restrictions.

## Historical baseline — 2026-11-08

Original recommendation: **NEEDS REDESIGN; unsafe as written.**

The separate-account model successfully handled more than eight lifetime members
and its small posting requests fit the tested Rules budgets. However, a direct
cross-group record-ID race corrupted committed financial history. Stop this POC
at that fundamental blocker. Keep all production financial access blocked.

## Scope and environment

- New isolated artifacts are in `tools/financial-posting-poc/`: candidate POC
  Rules/config, initial harness, independent read-only audit, failing adversarial
  counterexample and README. No application or existing Rules file was modified.
- Dedicated reserved project `demo-splitease-posting-poc`; actual Auth Emulator
  email/password accounts and ordinary authenticated Firestore REST writes.
  Auth 9199 and Firestore 8180 on loopback; no live fallback or production data.
- Existing cached Firebase CLI 15.34.1, Firestore emulator v1.22.0, Node v24.15.0
  and Java 25.0.2. No new library/SDK/service dependencies.
- The initial harness had a 180-second outer cap and 12-second HTTP timeouts.
  The focused counterexample had a 60-second cap. No timeout, server crash or
  emulator-recovery loop occurred. No Android emulator/build/lint suite was
  appropriate for this isolated Rules experiment.
- The minimal schema intentionally omits production title/category/profile
  integration, join/departure timestamps, admin transfer, UI/session restoration,
  receipt/recurring behavior and migration. Existing application semantics were
  not replaced. Its settlement-capacity policy is isolated experimental behavior.

## Actual outcomes

| Check | Result | Evidence |
| --- | --- | --- |
| Secure group/first-account initialization | PASS in tested cases | Nonzero initial account, orphan account, unpaired group, independent net change and account deletion denied |
| Member addition/rejoin authority | PASS in tested cases | Non-admin assignment/nonzero new account rejected; authorized zero join and retained-account rejoin accepted |
| Multiple-member equal expense | PASS | 101 paise split in sorted UID order as 34/34/33; final nets +67/-34/-33 |
| Exact account/cursor pairing | PASS in tested cases | Missing either write, forged net, wrong target, skipped cursor, duplicate replay and premature publication denied; rejection readbacks unchanged |
| Interrupted posting recovery | PASS | Initiator stopped after payer credit; another current UID finished remaining steps and publication; original creator retained |
| Pending-operation exclusion | PASS | Second BEGIN, cancellation after APPLY, membership changes and leaving while incomplete denied |
| Simpler settlement policy | PASS in selected cases | Capacity 34; amount 35/zero/negative/nonparty recording rejected; partial 10, any-member edit to 20 and remaining 14 accepted; date/party tampering rejected |
| Historical member preservation | PASS in tested cases | Member left at zero; permitted historical expense edit made retained LEFT account -1; rejoin preserved -1 and rejected reset to zero |
| Ninth lifetime member after departures | PASS | Eight distinct accounts first existed; two left; ninth UID joined with both old accounts unchanged |
| Twelve-identity expense edit | PASS | Original eight-person expense edited to a rotated eight-person subset/payer across twelve retained accounts; exact independently computed net vector |
| Invalid candidate recovery | PASS | Inactive final participant/fractional final share failed PREPARE; abort kept all nets zero; valid subsequent operation completed |
| Concurrent BEGIN / stale record / leave race | PASS in tested cases | Parallel commits had one winner; stale old head rejected; simultaneous zero leave versus BEGIN had one winner and returned to IDLE |
| Initial read-only history audit | PASS before exploit | Four groups, 23 retained accounts and six heads: every net/spending/gross total matched reconstruction; zero-sum; all IDLE |
| Cross-group same-global-ID race | **FAIL — critical integrity vulnerability** | Both staged commands captured absent head; second FINALIZE returned 200 and replaced first group's head, including its group identity, without increasing record revision |

Initial campaign: **7/7 cohorts passed**, 9.216 seconds including fixture setup;
36 explicitly checked semantic rejections. Then the independent audit passed.
The subsequently executed targeted security regression **failed**. This is not a
fully passing POC, despite the earlier green cohorts.

The initial campaign issued 252 Commit requests and committed 367 document writes
in the emulator, including synthetic identities, memberships, budget probes and
32 owner-seeded diagnostic documents. It recorded 42 denied commits: the 36
explicit semantic checks plus concurrency losers and expected diagnostic limits.
These counts describe that campaign only; the later exploit used additional
normal authenticated fixture operations. They are not production billing or
throughput estimates.

The counterexample initially stopped before exercising any financial operation
because its fixture account-list endpoint was unavailable. Setup was corrected
to sign into the existing deterministic test accounts. There was exactly one
executed cross-group attack after that fixture correction. No Rules redesign or
passing-suite rerun followed the discovered security failure.

## Critical counterexample and root cause

The global `records/{UUID}` head is not reserved at BEGIN. Group-local control
documents serialize operations within each group, but not writes to the same
global record ID across groups. UUID syntax checks cannot stop an attacker from
deliberately choosing an already pending ID.

The executed attack used ordinary authenticated tokens, not an owner/admin
Firestore bypass:

1. Create two authorized synthetic groups A and B. Give each the same payer and
   participant identities in their own independent account documents.
2. BEGIN an expense with the same canonical UUID in both groups before either
   publishes. Both snapshot `before == null`. A's amount is 101 paise; B's is 203.
3. Finish A. Its head now records A/101/revision 1 and its balances are posted.
4. Complete B's posting steps and FINALIZE B against the same global head.
5. **B's FINALIZE succeeds**, replacing the head with B/203/revision 1. Both
   controls report IDLE. A still reports spending 101 and payer net +101, but no
   longer has the financial record supporting those values.

The observed counterexample output includes:

```
second_finish_status: 200
first_record_group_overwritten: true
record_revision_before: 1
record_revision_after: 1
first_group_phase: IDLE
second_group_phase: IDLE
first_group_payer_net: 101
first_group_total: 101
first_group_lost_its_committed_head: true
head_amount_after: 203
```

`begin` verifies the old head only when capturing the command.
`finish` verifies the final requested head, but does not recheck ownership/existence
against the actual current head. The `records` write rule also relies on the
command instead of independently preserving the stored group/revision. Thus a
caller can change immutable identity and silently overwrite an intervening write.

This is **committed corruption**, not an unfinished operation waiting for another
client. Ordinary posting cannot repair A by resetting its accounts or reinstating
the old head: that ID is now owned by B, while A's effects remain. Privileged
repair would be outside the permitted protocol and was not attempted. Independent
client writes therefore can bypass a critical invariant even though the tested
initial zero-account forgeries failed.

## Rules budget findings

All normal valid operations and 36 explicitly checked semantic adversarial
rejections in the initial campaign completed without expression/access-budget
errors. The rejection helper treats those errors as blockers instead of counting
them as successful semantic validation. Budget diagnostics intentionally saturate
the engine separately.

| Actual probe | Observed outcome |
| --- | --- |
| One write requiring ten distinct existing document gets | 200 |
| One write requiring eleven | 403, get service-call failure |
| Two writes × ten disjoint gets | 200 |
| Three writes × eight disjoint gets | 403 |
| Real two-document POST with 0, 7, 8, 9 additional distinct gets | All 200 |
| Real POST with ten additional distinct gets | 403 at existing probes/9; cursor unchanged |
| Real POST with zero, one, two extra 16-comparison chunks | All 200 |
| Real POST with four extra chunks | Explicit maximum-of-1000-expressions failure; cursor unchanged |

The successful extra-get requests show observed cached headroom on those sampled
POST branches. They do **not** establish an exact uncached access count for every
phase or a billable-read count. `getAfter` can reuse values already present in
the write set; do not subtract padding from ten and claim all real transitions
use only one read. Conservative source budgets and worst-case branch measurements
would still be required.

Likewise, 32 added simple comparison terms passed while a request for 64 reached
the expression ceiling. Function calls, field access, conditionals and other
validation contribute to that ceiling. No precise unpadded instruction count or
three-chunk boundary was measured. This sampled margin is not permission to add
arbitrary production fields/validators.

Coverage snapshots were captured from the emulator's ruleCoverage endpoint.
They are aggregate source-position value/count trees, not a reliable per-request
instruction meter or billing counter. Initial valid and adversarial commit
latencies stayed in local millisecond ranges; no real-world performance or
production quota capacity is inferred. There was no attempt to stress maximum
storage size, numeric aggregate exhaustion, database restart or network outage.

## Guarantees supported, and limits of the proof

- Separate retained accounts demonstrated ninth-member addition and a twelve-
  identity edit. The implementation contains no eight-lifetime-member array or
  slot recycling. This demonstrates the tested growth pattern, not infinite
  free storage. Eight participants **per expense** remains the existing technical
  bound in the POC; lifetime membership and per-record work are distinct.
- Within the tested single-group operation paths, exact cursor/account coupling,
  PREPARE-before-APPLY, immutable pending commands, zero initialization and the
  zero-at-leave checks resisted the selected direct-write attacks. Those local
  successes do not establish whole-ledger integrity after the cross-group failure.
- Partial/new settlement capacity was checked against two stable accounts and
  edits used the old-record-excluded ceiling. The UI/production repository's
  greedy-pair policy was not changed. Duplicate warnings, payment authenticity,
  full matching and production settlement integration were outside this POC.
- Interrupted posting recovery was another actual authenticated UID resuming
  durable emulator state. It was not an Android force-stop, emulator restart,
  real SDK transaction retry or live backend restoration test. Concurrent REST
  atomic commits exercised Rules compare-and-set rejection, not app ViewModels.
- Any member can park a valid APPLY operation and decline to finish. Other
  current users can resume tested valid work, but there is no automatic trusted
  executor. Quota exhaustion, no returning current user, or disappearance of the
  only usable identity can leave it blocked indefinitely. No unsafe timeout
  unlock exists. This availability risk remains even after the discovered
  cross-group integrity defect is addressed.
- Privileged operators bypass Rules and are outside this mobile-client threat
  model. The actual exploit required no such privilege; owning/participating in
  two groups and choosing the record ID was sufficient.

## Required correction direction, not implemented

Keep the financial hold. Before another proof, bind a record exclusively to its
group/operation **before the first account delta**. Two possible designs to review
are group-scoped record paths, or an atomic immutable global ID reservation paired
with BEGIN. Independently enforce stored group identity and actual record revision
at publication too; do not depend solely on a captured command.

**Adding only a FINALIZE precondition is insufficient**: it would detect this
collision after B has already applied its balance deltas, stranding B in APPLY.
Exclusion/reservation must precede posting, and any future failure path must remain
resumable without invalid financial publication or an unsafe balance rollback.

This is a narrowly identified redesign requirement, not authorization to change
application paths or implement a new protocol in this task. The POC Rules were
left unchanged after the exploit, and no further financial tests or repairs were
performed. A future proof must include the failing cross-group regression along
with initial forgery, publication and recovery cases; rerunning only the seven
green cohorts would conceal the defect.

## Artifacts and preserved boundaries

Retained evidence: `/tmp/posting-poc-results.json`, `/tmp/posting-poc-run.log`,
`/tmp/posting-poc-audit.json`, `/tmp/posting-poc-cross-group-race.json`,
`/tmp/posting-poc-cross-group-race.log`, coverage snapshots and
`/tmp/posting-poc-emulators.log`. Temporary files can expire; results above and the
reproducible failing test remain in the workspace. Coverage contains synthetic
identity context and is not committed.

Application repositories, UI, SQLite data/default, existing candidate/production
Rules and previous POC artifacts were preserved. No live Firebase connection,
deployment, billing, Blaze, Functions, Storage, new milestone integration or
automatic commit occurred. SHA-256 checks confirmed the existing Rules and both
financial repositories were unchanged; `git diff --check` and Python compilation
passed. Only positively identified task-owned POC servers were gracefully stopped,
and their exit was confirmed. Prior user work and POC artifacts were preserved.

## Record ownership fix — 2026-11-09

### Chosen design and alternatives

Use `groups/{groupId}/records/{recordUUID}`. The physical record identity is now
the pair **(group ID, UUID)**. The group namespace establishes ownership, and the
existing group operation gate exclusively claims that target before any balance
posting. A UUID may occur in two groups because those are different documents;
neither group can update the other's document through its operation.

An immutable global reservation could instead preserve a single global UUID
namespace, but it needs another document/write/lookup, initial reservation pairing,
per-edit operation claims, and explicit abandoned-reservation handling. Deleting
and recreating ownership records would require another proof to avoid history or
stale-claim bypasses. None of that is needed for group-scoped identity, so no
reservation collection, lease, expiry job or garbage collection was added.

This is a POC storage-path change only. Future repository adapters would need
group context for record access; ID-only global lookup cannot silently remain
equivalent. Existing application repositories, IDs, schemas and Rules were not
changed, and no legacy/global record migration was attempted.

### Ownership before posting and on each transition

- BEGIN reads the target's actual **scoped** head and captures it along with the
  group-bound proposed revision. The current-member check and group revision
  compare-and-set establish one exclusive command within that group.
- `owned(group, control)` verifies the frozen proposed group and actual current
  scoped head against the captured before snapshot. PREPARE, POST, paired account
  posting, ABORT and FINALIZE require this ownership check. Existing immutable
  command/cursor checks prevent changing the target, group or operation sequence.
- Publication additionally enforces path group == payload group, stored group
  immutability, actual stored revision + 1, immutable creator/kind, exact captured
  before data, complete cursor and fresh paired FINALIZE. A new head requires
  an absent captured before state and revision 1. The legacy global write path is
  denied. Head authorization remains coupled to the fresh group transition,
  which requires a current authenticated member; it is not a UI-only check.
- Hard deletion is explicitly denied. Deleted expense heads remain tombstones;
  BEGIN and record updates reject resurrection. Resetting the expected revision
  or pretending the tombstone is absent cannot reclaim its identity.
- PREPARE abort leaves accounts and any existing head unchanged. An unpublished
  ID can be used by a later fresh command in the same namespace because no
  financial history exists for it; old command/cursor sequences cannot continue.
  APPLY cannot be abandoned through cancellation or timeout unlock. Another
  current member can resume it, preserving the initiating creator and claim.

The fix therefore does not wait until FINALIZE to discover cross-group ownership
loss after balances have changed. Group isolation and exclusive command ownership
exist before the first POST, with actual-head checks throughout the operation.
Ownership belongs to the group namespace, not exclusively to the original user:
published records still permit the existing authorized current-member edits. An
active operation cannot be replaced by another member's conflicting command.

### Focused ownership verification

`ownership_tests.py`: **5/5 cohorts passed**, 4.314s including reusable synthetic
fixture setup, 120-second cap; 21 explicitly checked semantic rejections.

| Focused cohort | Actual result |
| --- | --- |
| Former cross-group collision | Both operations began with the same UUID, then published distinct A/101 and B/203 scoped heads; A stayed byte-for-byte unchanged. Foreign payload into A and old global-path writes were rejected |
| Concurrent claims of one scoped record | Two valid current-member claims raced on the same group/UUID; one won, one returned 403. The winner's actor/creator remained bound; duplicate absent-head claim and changed operation sequence were rejected |
| Foreign operation/ownership forgery | Forged BEGIN group, substituted B command in A, redirected account write into B and B-only member continuing A were rejected; both legitimate operations could still finish |
| Abort and interruption | Aborted PREPARE changed no balances and created no head; fresh same-ID claim had a newer sequence; old preparation/replayed posting and APPLY abort were rejected. Another current UID resumed and published the correct 29-paise record |
| Immutable revision, deletion and recreation | Forged publication group/revision/creator were rejected; legitimate edit advanced revision 1→2. Expense tombstone reached revision 3; hard delete, absent-head reclaim, resurrection and raw overwrite were rejected; balances remained zero |

The former failing `cross_group_race.py` is now a scoped regression, not a weakened
expectation that overwriting is acceptable. It verifies two **different paths**,
both exact financial effects, preservation of A's original head and rejection of
cross-path/global writes. Focused tests use ordinary Auth Emulator tokens, not
privileged financial writes. A fixture-only login improvement reuses deterministic
accounts instead of relying on the unavailable account-list endpoint.

### Affected verification, run once after focused success

The original seven cohorts ran **once**, **7/7 passed**, 7.244s, 180-second cap;
36 semantic rejections. This includes exact expense transitions, interruption,
capacity settlements/corrections, historical accounts, ninth-member addition
after departures, the twelve-account eight-participant edit, candidate abort,
concurrent/stale operations and leaving races, and the existing budget probes.
No application build/unit/instrumentation suite was rerun for this isolated POC.

The updated read-only diagnostic audit checked **11 groups, 38 retained accounts
and 14 scoped heads including the tested tombstone**. Every group was IDLE; every
account net, spending total and gross settlement total matched independent
integer reconstruction, with zero-sum balances. Shared UUIDs in different groups
no longer caused a missing or overwritten head. Owner bypass was used only to
enumerate/read the synthetic fixtures and seed diagnostic probe documents;
financial mutations under test used normal authenticated clients.

No known test failure or accepted ownership attack remains in these measured
paths. This result does not claim exhaustive protocol security.

### Rechecked Rules budgets

All ordinary valid operations and the 57 explicit semantic rejections across the
two runs completed without a budget/service-error rejection being counted as a
semantic pass. Controlled diagnostic failures below were intentionally separate.

| Probe | After the ownership fix |
| --- | --- |
| Single-write distinct gets | 10 succeeded; 11 denied |
| Atomic request distinct gets | 20 succeeded; 24 denied |
| Real existing-head POST with extra distinct gets | 0 and 7 succeeded; 8 denied at existing `probes/7`, with no cursor advance |
| Real POST with additional 16-comparison chunks | 0, 1 and 2 succeeded; 4 reached the explicit 1,000-expression limit, without advancing the cursor |

The earlier uncorrected sample accepted nine extra gets; the corrected sample
accepts seven. That reduction is retained as the cost of actual-head ownership
checks. No check was removed or relaxed to recover the previous headroom.
Two expression chunks mean 32 additional comparison terms passed; four request
64 terms plus call/field/validation overhead and exhaust the engine. Three chunks
were not measured. Exact per-operation instruction counts and billable/uncached
read counts remain unavailable from aggregate coverage. These sampled margins
are not guarantees for every future production field, query or request shape.

Normal eight-way creation/editing and the 18-component edit path completed under
the added checks. Source/coverage observations and deliberately padded requests
are distinct evidence; no success is inferred merely from theoretical limits.

### Remaining limitations and next-stage recommendation

**Ready for the NEXT bounded, isolated security validation stage. Not ready for
production deployment or application integration.** The confirmed global-record
ownership vulnerability is resolved in the scoped model and its measured tests.

Remaining restrictions include the known APPLY availability risk, no automatic
completion if no current user returns, finite Spark capacity, unverified full
production record/profile fields and indexes, no live/IAM/real-device coverage,
and no migration of old global heads. Authorized manual financial claims still
are not external proof of purchase/payment. The simpler settlement policy remains
experimental; current application greedy behavior was not modified. The POC's
per-expense participant bound remains distinct from lifetime membership; the
ninth-member and twelve-retained-account cases still pass.

A subsequent security stage should validate the complete intended schema and
compound/adversarial transition sequences with these ownership invariants intact,
including resource boundaries and liveness. It must not broaden the production
audience gate or deploy candidate Rules merely because these focused tests pass.

### Changes, evidence and cleanup

Changed only POC `posting.rules`, `run.py` path/fixture helpers,
`cross_group_race.py`, `audit.py`, README and this report; added
`ownership_tests.py`. No ownership-reservation service, unrelated feature or new
dependency was introduced.

Evidence: `/tmp/posting-owner-focused.json`, `/tmp/posting-owner-focused.log`,
`/tmp/posting-owner-cross-group.json`, `/tmp/posting-owner-affected.log`, the latest
`/tmp/posting-poc-results.json`, `/tmp/posting-poc-audit.json`, ownership/before/after
coverage snapshots and `/tmp/posting-owner-emulators.log`. Earlier baseline
evidence remains described above; temporary logs may expire.

Protected application repository/Rules hashes matched their pre-task values.
SQLite/local auth remain default and the production hold remains intact. No live
Firebase access, deployment, billing/Blaze, Functions, Storage, application change
or automatic Git commit occurred.

Final cleanup for the 2026-11-09 fix: only the positively identified task-owned
Auth/Firestore emulator servers were stopped, and process exit was confirmed.
`git diff --check`, Python compilation and protected file hash checks passed.
No prior user work or data was removed.

## Integration-readiness follow-up — 2026-11-09

A documentation/source-only assessment is in
`financial-ledger-integration-readiness.md`. Its recommendation is **NEEDS
CORRECTION before staged application integration**: durable operation/outcome
semantics, recovery admission/headroom, complete schema/query compatibility,
atomic initial membership/admin transfer and explicit settlement-policy acceptance
remain unresolved. This does not reverse the narrower finding that the ownership
fix was ready for another isolated security validation stage.

No new tests or security experiments were performed for that follow-up; all
counts above remain the previously recorded evidence. Universal interrupted-work
recovery and full application-schema budget compatibility are not established.
Application code, POC/production Rules, services, data and the production financial
hold were unchanged; no deployment, billing change or commit occurred.
