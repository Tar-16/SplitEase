# Firestore financial ledger Rules POC results

Date: 2026-10-31. **Recommendation: NEEDS REDESIGN / BUDGET REDUCTION.** The tested
eight-slot validator cannot complete its first valid expense creation within the
emulator's expression budget. This is not evidence that every possible Spark-only
ledger is impossible; it is a concrete failure of this implementation of the
proposed design. Do not approve production activation or relax the security hold.

## Isolation and method

- Artifacts: `tools/financial-ledger-poc/{generate.py,ledger.rules,run.py,firebase.json,README.md}`.
- Dedicated project `demo-splitease-ledger-poc`, Auth Emulator 9199, Firestore
  Emulator 8180, loopback only. No production config/credentials, live network,
  deployment, billing, Blaze, Storage, Functions, application integration or commit.
- Real Auth Emulator email/password accounts and ID tokens; normal authenticated
  REST Commit/read requests exercise Firestore Rules. Assertions do not use an
  admin bypass. Emulator-owner access only sets up known-existing access-probe
  documents, lists/reuses synthetic test accounts and performs diagnostic readback.
- Firestore emulator v1.22.0 (Standard edition), installed Node v24.15.0, Java
  25.0.2; existing cached Firebase CLI 15.34.1. Generated Rules source: 12,157 bytes.
- The POC models all eight retained slots, exact equal splits, paired ledger/head
  writes, revisions, aggregate totals, partial/edit settlement ceilings and a
  one-step-at-a-time canonical matching verifier. Expense/settlement heads share
  a POC-only `records` collection. Registered UID mirrors are minimal synthetic
  fixtures, not a replacement for the real profile/username Rules.
- Each HTTP request has a 12-second timeout; harness invocations have a 180-second
  outer cap. No timeout, crash or availability failure occurred. No Android
  emulator or app suite was needed for this isolated server-Rules experiment.

## Results

| Scenario | Result | Observed evidence |
| --- | --- | --- |
| Atomic eight-member group + zero ledger creation | PASS | Two successful zero-initialized groups in bootstrap case; actual readback verifies eight zero balances, version zero and zero totals/count |
| Balanced but forged nonzero initial checkpoint | PASS — rejection only | Normal client batch with +1/-1 paise initial vector returned HTTP 403; see denial-budget caveat below |
| Independent ledger creation / independent group creation | PASS — rejection only | Both incomplete bootstrap writes returned HTTP 403; see denial-budget caveat |
| Ledger deletion / reset to initial state | PASS — rejection only | Both denied with HTTP 403; reset also encountered the expression-budget caveat |
| Nine-slot initial group | PASS — rejection only | Nine-slot request denied; does not establish support or a secure larger limit |
| First valid expense: 101 paise, eight equal participants | **BLOCKED / functional failure** | Paired head/ledger commit returned HTTP 403 with explicit maximum-of-1000-expressions error |
| Expense editing, deletion, resurrection protection | NOT VERIFIED | Same ordered case stopped at its first expense create |
| Exact transitions for all eight slots; forged balances/totals; missing pairs; stale revisions | NOT VERIFIED | Dependent adversarial financial case not run after valid-write blocker |
| Staged greedy plan, UID tie-breaking, exact transfers and invalidation | NOT VERIFIED | Dependent plan case not run; source implementation is not passing evidence |
| Partial settlement creation/editing and immutable parties | NOT VERIFIED | Dependent settlement case not run |
| Concurrent transactions | NOT VERIFIED | Dependent concurrency case not run |
| Historical/departed member balance manipulation | NOT VERIFIED | Dependent history case not run; trusted legacy-departure fixture never executed |
| Single-write access probes: 10 versus 11 distinct gets | PASS | Ten succeeded; eleven denied. Server log identifies get on existing probes/10 as the failing service call |
| Atomic request probes: 20 versus 24 distinct gets | PASS | Two writes × ten disjoint gets succeeded; three writes × eight disjoint gets denied. All probe documents existed; each individual write requested fewer than ten in the failing batch |
| Expression saturation control | PASS | Sixteen simple comparison terms succeeded; high control requesting 512 terms hit explicit 1,000-expression ceiling, including evaluation overhead |
| Atomic rollback after blocked expense | PASS — diagnostic readback | All three created ledger documents remained zero/version 0/count 0; zero financial records existed |

Harness-level summary: **2 cohorts passed, 1 cohort blocked, 5 cohorts not run**.
The expression-saturation observation is an additional diagnostic, not another
functional financial test. The completed ordered invocation took 0.469 seconds
on warmed local emulators (bootstrap 0.100s, blocked expense case 0.032s,
independent limit probes 0.058s). These are local measurements, not production
latency or load-capacity claims.

An initial generated division expression needed whitespace to compile. The
first data attempt then stopped on an *expected invalid-write denial* carrying
the budget error. The harness was corrected to run valid initialization first
and distinguish expected 403 denials from failed valid writes. The resulting
ordered invocation established the actual positive expense blocker above.
There was no validator redesign, member-limit tuning, weakened check or further
financial test retry after that blocker. Only independent engine probes and
read-only diagnostics followed.

## Confirmed guarantees and measurement limits

The Rules compiled and accepted the paired zero bootstrap; attempted unpaired,
nonzero, destructive and nine-slot initial writes did not succeed. No initial
checkpoint forgery was observed in those attempts. However, some invalid
bootstrap/reset requests exhausted the expression budget before evaluation
completed. Their rejection must **not** be described as proof that the intended
semantic check executed successfully. A different bypass might exist; absence
of a successful forgery in this small set is not a complete security proof.

The valid financial batch's engine error was:

> Unable to evaluate the expression as the maximum of 1000 expressions to evaluate has been reached.

The emulator stack points into the expense share-row validator at generated
`ledger.rules:40`, called through the ledger's financial-update branch at line
125. The batch did not finish validation or commit. Thus the current proof has
no demonstrated budget margin for full eight-slot transitions, settlement
ceilings, or staged plans. Repeating division/remainder expressions and repeated
field/function evaluation are implementation costs; this run does not prove
that an optimized equivalent validator or a smaller bound cannot fit.

The controlled access probes empirically exercised the ten-call single-write
boundary and twenty-call atomic-request boundary with **distinct pre-existing
documents**. Denial logs report a get service-call error, rather than explicitly
printing the numerical access limit. Passing lower controls and the disjoint
paths distinguish this from missing data or authorization. These probes measure
engine limits, **not the ledger validator's exact billable read count**.

The emulator ruleCoverage endpoint returned source-position value/count trees.
Snapshots were collected before/after the ordered run. The final cumulative
report contained 1,562 expression-node rows and 48,774 value observations across
setup, attempts, denials, reads and probes. These aggregate observations are
**not a per-request instruction counter**, not a precise number of expressions
required by the failed expense, and not a count of uncached document accesses.
No exact financial-write access-call count or successful-expression headroom was
established. The hard 1,000-expression failure is directly reported by the engine.

Document-size, maximum request-size, transaction duration, production quota,
SDK/app lifecycle and live-backend behavior were not stress-tested. A 12 KiB
Rules source does not imply low runtime expression cost. No safety claim rests
only on theoretical quotas or the earlier passing application tests.

## Restrictions and remaining risks

1. **Do not relax eight lifetime identities.** Nine slots are rejected by the
   current shape; simply changing the length bound would leave the ninth slot
   unvalidated and could permit forged balances or matching choices. Even eight
   is not operationally established here. A larger bound needs a separately
   measured complete validator, including historical identities and worst-case
   edits; no such result exists. Slot recycling/pruning and disabling checks are
   unsafe workarounds.
2. The checkpoint's inductive integrity, complete two-way coupling, settlement
   overpayment protection, concurrency and staged-plan correctness remain
   **unproven** by this POC. None of the unrun test functions count as coverage.
   The broad question of all possible independent-client initialization or
   financial bypasses remains open.
3. Rules cannot establish that a real purchase/payment happened. Current members
   retain the existing authority to correct historical records; immutability of
   all past amounts would contradict that policy. Duplicate warnings remain
   advisory. No application business rule was changed.
4. The POC omits production profile/username integration and membership mutation,
   registration recovery, leave, production migration and live deployment. It
   does not authorize importing a nonzero checkpoint from client history.
5. Probe collections deliberately permit synthetic diagnostic writes; this
   source is **not deployable product Rules**, even though its audience guard
   restricts it to the reserved demo identity. Keep it isolated and disposable.

## Recommendation and preserved boundaries

**Needs redesign/budget reduction; not viable as written and not production-safe.**
Stop this proof here. A separately scoped follow-up could attempt a more compact
equivalent validator or a smaller hard bound, with the same security checks and
full adversarial coverage. The checkpoint/greedy approach is not certified, and
no paid service or weaker policy is proposed as a remedy.

Production financial access must stay blocked. The app's repositories, UI,
existing candidate Rules, authentication, SQLite default, data and dependencies
were untouched. Protected Rules/repository SHA-256 checks were retained for
comparison. No deployment, live data/service change, billing operation, 9E.2B
work or automatic commit was performed.

Reproducible harness/configuration are in `tools/financial-ledger-poc/`.
Temporary raw evidence: `/tmp/ledger-poc-run.log`,
`/tmp/ledger-poc-results.json`, `/tmp/ledger-poc-readback.json`,
`/tmp/ledger-poc-coverage-before.json`, `/tmp/ledger-poc-coverage-after.json`,
`/tmp/ledger-poc-coverage-counts.json` and `/tmp/ledger-poc-emulators.log`.
Raw coverage contains synthetic emulator identity context; it is not committed.
Temporary logs may expire; the findings above are retained here. Protected
candidate Rules and both financial repository SHA-256 checks matched their
pre-task values; `git diff --check` passed. Only the identified POC servers were
gracefully stopped and their process exit was confirmed. The earlier design
document was preserved unchanged.
