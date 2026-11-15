# Expense-only ledger durability POC

Updated: 2026-11-10 (Asia/Kolkata). **Isolated proof, not application integration or production approval.**

**Current conclusion: INCONCLUSIVE — further application-schema validation required.**
The 10 November correction improves cost without removing financial checks.
Valid workflows still complete and recover, but BEGIN's expression margin
remains small with realistic authorization reads and most invalid requests
still produce mixed budget diagnostics. Keep the production financial hold.
The original 9 November results below are historical evidence; the correction
section at the end supersedes their recommendation.

## Implementation and boundary

Disposable sources: `tools/financial-expense-durability-poc/`. The generated
`expense.rules` is selected only by that directory's `firebase.json`. Existing
application repositories, existing candidate/production Rules and earlier POCs
were not changed. SHA-256 comparisons confirmed the existing expense/settlement
repositories, `firestore/firestore.rules` and prior posting Rules unchanged.
SQLite/local authentication remain untouched. No application builds/tests were
needed for this isolated proof. No deploy, live data write, billing, Storage,
Functions, settlement, UPI or leave implementation was performed.

Auth and Firestore emulators used demo project
`demo-splitease-expense-durability`, loopback ports 9199/8180, and Firestore
Emulator 1.22.0. Rules additionally require that demo token audience. Nine
synthetic Auth accounts were reused across cohorts. Ordinary ID tokens authorize
every financial assertion. Emulator-owner credentials were used only to seed
diagnostic documents/near-limit revision fixtures and enumerate audit data;
accounting reconciliation itself used a current member's credentials.

### Durable schema

| Path under `groups/{groupUUID}` | Meaning |
| --- | --- |
| Group document | Immutable creator/admin/creation metadata and initial-member descriptor for this proof |
| `ledger/control` | Checked control/accounting revisions, phase, active operation UUID, validation/posting cursors, verified spending total and member count |
| `accounts/{opaqueAuthUID}` | Retained identity/name/membership snapshot, integer net paise, account revision and last applied operation/component |
| `operations/{operationUUID}` | Immutable pending expense command, original actor, scoped record UUID, expected/accepted revision and server creation timestamp; later an immutable terminal receipt |
| `records/{recordUUID}` | Committed expense only; immutable revision 1 in this create-only proof, with exact shares and publication metadata |

BEGIN atomically writes control plus the pending operation. The group lock and
immutable command establish scoped record ownership before any balance posting;
there is no separate unpaired reservation or global record namespace. Every step
binds the same operation and absent scoped head. A second request cannot replace
the pending command or reuse its operation UUID. COMMITTED/CANCELLED receipts
retain identity/result metadata and remove the pending financial payload.
Committed records and receipts cannot be deleted/recreated. A cancelled,
**never-published** record key may be used by a new operation; the cancelled
operation UUID remains permanently consumed.

State machine:

1. `IDLE → PREPARE`: admit command, reserve control/total/revision headroom.
2. PREPARE: validate payer, each sorted participant/share and resource headroom
   for every posting occurrence. No balance changes yet.
3. APPLY: credit payer then debit each participant; each account delta/marker
   and cursor/revision increment form one atomic request.
4. FINALIZE: atomically publish expense, mark receipt COMMITTED and release
   control to IDLE; update spending/accounting revision exactly once.

Cancellation is safe only in PREPARE: it releases control and records CANCELLED
without changing accounts/history. **There is no APPLY rollback.** Any current
member can finish a frozen APPLY operation. No lease expiry or original-actor
permission is required. Recovery reads the command through the recovering
member's own token; it does not receive the original client's draft.

## Executed verification

Three bounded campaigns (180/120/60-second process limits) completed in
5.802/4.376/1.115 seconds respectively. **11/11 scenario cohorts completed**;
these are custom REST/emulator checks, not Android instrumentation test counts.
Expected diagnostic rejections are distinguished below from baseline failures.

| Scenario | Result / evidence |
| --- | --- |
| Atomic group/accounts and pending-operation creation | PASS: three registered members initialize at zero in one commit; control-only and command-only admission attempts rejected; accepted pending command readable by another member |
| Exact splits/posting | PASS: 101 paise splits 34/34/33 in sorted UID order; account deltas and spending reconcile exactly |
| Repeated requests/stale revisions | PASS for observed outcomes: duplicate BEGIN, repeated CHECK/POST and terminal writes rejected without double posting; reading terminal receipt is idempotent |
| Recovery at every request boundary | PASS: cuts 0–13 after BEGIN cover every CHECK, every POST and committed-but-acknowledgement-lost state of the three-participant expense; a different member completes each |
| Cancellation/reuse/progress | PASS: cancellation after 0, 4 and 7 checks leaves accounts unchanged; terminal receipt survives retries; new operations proceed after cancellation/completion; APPLY cancellation denied and completion succeeds |
| Concurrent requests | PASS: two current members submitting the same next revision produce exactly one 200 and one 403; cursor advances once and recovery completes |
| Maximum-size supported expense | PASS: eight participants, separate ninth-member payer, 200-character title, 2,000-character description, 999,999,999,999 paise; 18 checks and nine postings complete |
| Numeric admission/recovery | PASS for observed outcomes: exact remaining 14-control-increment budget finishes at revision 999,999,999; one less increment rejects admission; insufficient account revision headroom rejects a preflight check, allows another member to cancel, and a valid subsequent expense excluding that exhausted account completes |
| Premature publication / inconsistent writes | Observed rejection: early FINALIZE, changed pending amount, forged net delta and unpaired account/control writes; state unchanged and valid recovery succeeds. Rejection-cause qualification below applies |
| Recovery authorization | Observed rejection: registered nonmember cannot read the command or submit its next step; a current member cancels successfully |
| Final independent reconciliation | PASS: seven synthetic groups, 39 retained accounts, 41 committed expenses and 59 terminal receipts; all groups IDLE, all account nets equal committed-history reconstruction, net sum zero and spending totals exact |

The near-limit counter fixtures were seeded with the emulator bypass; this tests
ordinary-client transitions from those states, **not** a claim that a user can
set those counters. The tested group at the final counter value is intentionally
read-only for further financial mutations; versions never reset or wrap.

An initial harness classifier stopped on an intentionally invalid missing-pair
write because the emulator returned both null and expression-limit diagnostics.
The classifier was corrected to record rejection without attributing its cause;
then the baseline campaign ran once. Rules were not weakened. No valid expense
failure was retried or hidden.

**Security-evidence qualification:** the campaigns captured 20 explicit
negative-write rejection assertions; 17 responses also contained the emulator's
1,000-expression diagnostic. Therefore these outcomes establish rejection and
atomic non-mutation, but those 17 cannot independently be credited as clean
semantic tests of the intended invariant. Clean, cause-specific negative
coverage remains necessary after reducing Rules cost. This is not a complete
adversarial security audit or proof that every malformed command is harmless.

## Measured Rules budgets

All baseline complex valid requests passed, including participant-side debit,
full preflight and three-document FINALIZE. POC-only padding adds independent
reads of existing diagnostic documents or chunks of 16 comparison terms.
Requests with denied padding were retried **without padding solely to safely
complete/cancel the unchanged operation**, not to mask a baseline failure.

| Maximum-size workflow branch | Extra reads: 4 / 7 | Extra comparison terms: 16 / 32 / 64 |
| --- | --- | --- |
| BEGIN | PASS / DENIED | DENIED / DENIED / DENIED |
| Last participant/share CHECK | PASS / DENIED | PASS / PASS / DENIED |
| Last resource CHECK | PASS / DENIED | PASS / PASS / DENIED |
| Payer POST | PASS / PASS | PASS / PASS / DENIED |
| Last participant POST | PASS / PASS | PASS / PASS / NOT TESTED |
| FINALIZE | PASS / PASS | PASS / DENIED / DENIED |
| PREPARE CANCEL | PASS / PASS | PASS / PASS / DENIED |

Expression-padding denials explicitly report the 1,000-expression ceiling.
Seven-read CHECK probes report a service-call failure at the seventh extra
document as well as expression diagnostics. BEGIN's seven-read rejection also
has expression diagnostics; it does **not** isolate an access-count boundary.
Extra comparisons incur function/field/boolean overhead as well as the nominal
term count. The diagnostic scaffolding itself is included in baseline costs.

These are **actual accepted/rejected request measurements**, not inferred
per-operation instruction totals. Aggregate Rules coverage was saved, but does
not expose exact per-request expression or cached/billable read counts. Do not
subtract padding from ten and assert an exact access count. Aggregate atomic
request limits and a stripped/noninstrumented Rules build were not separately
calibrated here. BEGIN's rejection with just one extra chunk and FINALIZE's
limited margin mean sufficient future integration headroom is **not proven**.

For eight participants the protocol needs **29 atomic requests / 41 document
writes**: BEGIN 2, 18 CHECK writes, nine paired POSTs (18), FINALIZE 3. This
excludes initialization, client reads, retries, profiles and listener delivery.
Recovery rereads authoritative command/control; cost and serialized group
throughput require budgeting against Spark quotas. No live quota or plan claim
is inferred from emulator success.

## Compatibility and limitations

- The actual existing profile/username/public-identity Rules were copied into
  this isolated fixture unchanged except the demo audience gate. Normal profile
  creation and name snapshots pass with opaque UIDs and no cross-user emails.
- Group metadata preserves name/type/creator/admin/timestamp concepts, but
  **this is not drop-in compatible with `FirestoreGroupRepository`**: current
  application membership lives in `memberIds`/`members` on the parent, while
  this proof uses retained account documents and an initialization descriptor.
  Existing group-list queries/parsers, financial paths and observers need the
  explicitly versioned integration adapters defined in the contracts document.
  No dual-written legacy membership authority was introduced or tested.
- Nine distinct accounts join normally; the eight-participant/initial-member
  operation bounds do **not** impose an eight-lifetime-member cap. Leaving,
  departed-member transitions/history edits and admin transfer are outside this
  proof and were not implemented/tested. Retained accounts are never deleted.
- During APPLY, account nets are provisional. Future observers must gate on
  control IDLE/accounting revision; exposing them as committed balances would
  be incorrect. Committed records remain absent until atomic final publication.
- Recovery simulates interruption by abandoning client progress and fetching
  durable documents with another account. It is not Android process-death,
  physical-device, emulator-reboot/export or offline SDK persistence coverage.
- No background recovery service is proved. Completion assumes a current member
  eventually acts with network/quota available. Repeated malicious admissions
  can cause contention; fairness/rate limiting and cross-user cancellation
  policy remain availability concerns. No timeout unlock bypasses integrity.
- Account revision/magnitude checks are conservative and may reject work before
  any delta. Fixed numerical/revision limits remain explicit; they are not a
  lifetime membership limit. Expense editing/deletion, settlement policy and
  zero-balance leaving still require separate validation and remain blocked.

## Evidence and next step

Sources: `generate.py`, `expense-body.rules`, generated `expense.rules`,
`run.py`, `boundaries.py`, `safety.py`, `firebase.json` in the isolated directory.
Reproduction instructions are in its README. Local ignored outputs:
`results.json`, `boundaries-results.json`, `safety-results.json` and emulator
debug logs. Additional local evidence: `/tmp/expense-durability-audit.json`,
`/tmp/expense-durability-rule-coverage.json`,
`/tmp/expense-durability-emulators.log`. Logs/tokens/private app data are not
committed. Task-started emulators were stopped after capture.

**Smallest next task:** reduce BEGIN/FINALIZE evaluation cost without removing
ownership, membership, exact-share, pairing or revision checks; then run the
affected valid branches and clean cause-specific rejection fixtures with a
predefined headroom target. This is a narrowly scoped POC correction, not
authorization for application integration or a new ledger redesign. Retain
the production financial security hold and unchanged settlement policy.

## 10 November: bounded Rules-cost correction

### One optimization pass

Only the isolated Rules sources/generated output were optimized:

- Bind before/after documents once in `updateControl(p,b,g)` instead of
  repeating `request.resource.data`/`resource.data` throughout dispatch.
- Pass the already loaded operation into `pending(g,c,x)`. Every caller obtains
  it from `op(g,c.activeOperationId)` using the before-control state, never an
  arbitrary client snapshot. No pending/ownership predicate was removed.
- Skip the 11 read-pad and 16 expression-chunk helper walks for zero diagnostic
  padding. Nonzero probes retain the original helper bodies. These helpers
  are instrumentation, not financial validation.

Source hotspots: BEGIN's full payload shape/type/identity/bounds checks;
repeated document expressions in dispatch; duplicate operation resolution in
POST/FINALIZE and their paired account/publication validations; always-walked
diagnostic helpers. FINALIZE still verifies control, receipt and record in both
directions. This is source-level attribution corroborated by before/after
margins, **not an exact instruction profile or isolated saving per edit**.

All financial predicates remain: current membership, frozen command/actor,
scoped ownership, exact shares, positive bounded integer paise, resource
headroom, sequential cursors, revisions, exact account deltas, immutable account
fields, paired writes, complete publication, immutable receipts and PREPARE-only
cancellation. No client-supplied balance becomes trusted. No ablation/redesign
loop or removal of a costly invariant was used.

### Verification after correction

| Check | Actual result |
| --- | --- |
| Existing affected cohorts, once each | 11/11 completed in 7.948 + 5.225 + 1.409 seconds; bounded at 180/120/60 seconds |
| Recovery/concurrency/cancellation | All existing interruption cuts, another-member recovery, cancellation, stale/repeated writes, next-revision race and subsequent-operation progress still pass |
| Maximum-field fixture | Nine retained accounts, eight participants plus separate payer, exact maximum 10^12 paise, 200-character title, 2,000-character description, 30-character registered names and 100-character group-name fixture |
| New cost-review cohorts | 2/2 completed in 4.347 seconds within a 120-second bound |
| Individually submitted invalid requests | 39/39 rejected: 3 clean false-rule denials, 3 dependency-evaluation denials, 33 budget-or-mixed diagnostics; detailed classification below |
| Real authorization-cost variant | All three unpadded valid branches and intermediate recovery steps completed; finite probes completed in 3.011 seconds, bounded at 90 seconds |
| Final accounting audit | Eight groups, 54 retained accounts, 62 committed expenses, 99 terminal receipts; all IDLE; exact account/history/spending reconciliation and zero-sum nets |
| Preservation | 207 application-source/existing-Rules hashes unchanged; no application tests/build needed; measurement variant restored and task-owned emulators stopped |

The long group-name fixture used emulator-owner only for nonfinancial metadata.
Profiles/registries/public identities, accounts, joins and financial transitions
used normal tokens. Existing numeric-boundary tests retain the privileged
revision setup disclosed in the original report.

The authorization harness initially reconstructed test clients with default
names instead of their registered 30-character names. Setup was rejected. The
harness was corrected to read each actual own profile, without Rules changes,
and the bounded measurement then completed once. This invalid fixture is not
evidence of a valid-operation budget failure. No valid failure was retried.

### Measured expression and access margins

PASS/DENIED refers to extra diagnostic load on an otherwise valid request.
Function/field/boolean overhead exceeds the nominal comparison count.

| Branch | Original accepted / rejected extra terms | Optimized accepted / rejected extra terms | Optimized distinct extra reads |
| --- | --- | --- | --- |
| BEGIN | 0 / 16 | 16 / 32, 64 | 4, 7 PASS |
| Last share CHECK | 32 / 64 | 16, 32 / 64 | 4 PASS; 7 DENIED |
| Last resource CHECK | 32 / 64 | 16, 32 / 64 | 4 PASS; 7 DENIED |
| Payer POST | 32 / 64 | 16, 32, 64 / not tested above 64 | 4, 7 PASS |
| Last participant POST | 32 / not tested above 32 | 16, 32, 64 / not tested above 64 | 4, 7 PASS |
| FINALIZE | 16 / 32, 64 | 16, 32 / 64 | 4, 7 PASS |
| CANCEL | 32 / 64 | 16, 32 / 64 | 4, 7 PASS |

The maximum-field fixture independently confirmed the 32/64-term and 4/7-read
outcomes for all six branches other than payer POST (last participant POST used
instead). No unpadded valid request exceeded limits. BEGIN still misses the
cost-review's predefined 32-extra-term target. Comparison denials explicitly
contain the 1,000-expression diagnostic. Seven-read CHECK denials include a
service-call error at the seventh added document plus expression diagnostics.

These are actual request margins, **not exact instruction, uncached-access or
billable-read totals**. Aggregate coverage cannot supply those per-request
counts. Positive and zero-padding instrumentation paths differ; removing the
zero walk is a diagnostic-overhead saving, not a reduction in security checks.
The protocol still costs 29 atomic requests / 41 writes for eight participants,
excluding reads, retries, initialization and observation.

### Actual authorization-cost measurement

`authorization-measurement.rules` is a generated measurement-only variant,
never selected by `firebase.json`. A loopback emulator update temporarily adds
actual checks for the retained active account's UID/group, group schema/type,
the actor's own private profile UID, and public identity UID/normalized-name
matching the account. These are actual documents and authorization predicates,
not dummy reads. The original profile/registry Rules remain intact. Loaded
optimized proof content was confirmed restored afterward.

| Maximum-size branch with authorization variant | No padding | +16 terms | +32 terms | +1 / +4 reads |
| --- | --- | --- | --- | --- |
| BEGIN | PASS | DENIED | DENIED | PASS / PASS |
| Last participant POST | PASS | PASS | PASS | PASS / PASS |
| FINALIZE | PASS | PASS | PASS | PASS / PASS |

The variant does not implement the application's legacy parent
`memberIds`/`members` authority, observers, admin transfer or versioned adapters.
It establishes these additional checks fit for the tested retained-account
model, not drop-in application compatibility. Full maximum initial-group atomic
creation and every future query/schema combination were not revalidated here.

### Negative cases: observed denial versus intended cause

Every request has a separate named result, expected invariant and raw diagnostic
in ignored `cost-results.json`, plus unchanged-control assertions. Final audit
confirms no leaked financial effects.

| Classification | Cases |
| --- | --- |
| Clean rule denial (3) | Pending payload modification, terminal receipt modification, committed expense deletion; no budget/service/dependency diagnostic |
| Dependency evaluation (3) | Missing control during BEGIN, POST and FINALIZE; absent/unchanged paired-state evaluation errors, not clean false-rule evidence |
| Budget or mixed (33) | Amount/type/cap, forged identity/group/UUID, empty/duplicate participants, expected revision, missing command, nonmember actor/recovery/payer, unequal share, duplicate operation, forged net/account revision/marker/name/cursor, missing account, APPLY cancellation, premature publication, replay, altered final amount/total/revision/actor, missing receipt/head |

For the mixed class, **the intended semantic rejection cause is not
established**. A verbose response contains an expression-limit diagnostic,
sometimes alongside false predicates/dependency errors. Do not suppress it or
credit the case as clean validation. Nor does the mixed response expose an
exact per-write count. Clean cause-specific negative coverage remains an
unresolved security-evidence requirement, despite all writes being rejected.

### Artifacts and conclusion

Added POC-only sources: `cost_review.py`, `authorization_cost.py`, generated
`authorization-measurement.rules`; updated Rules sources/generated output,
isolated README/output ignores and this report. Local ignored evidence:
`cost-results.json`, `authorization-results.json`, updated original results;
`/tmp/expense-cost-audit.json`, `/tmp/expense-cost-coverage.json`,
`/tmp/expense-cost-emulators.log`, `/tmp/expense-cost-protected.json`.
No credentials/tokens or diagnostic logs were added to tracked files.

**INCONCLUSIVE: further application-schema validation required.** One bounded
optimization preserves source predicates and tested recovery, but BEGIN with
actual authorization has no demonstrated 16-term reserve, and intended-rule
negative verification remains incomplete. No fundamental unpadded-valid limit
failure or unrecoverable posting was demonstrated; a PASS is also unsupported.
Stop here: no second rewrite, Android integration, settlements, leaving,
production Rules/deployment/data, billing or automatic commit. Keep the
production financial hold and unchanged settlement policy.
