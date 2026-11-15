# Proposed Spark-only financial ledger

Status: design assessment, 2026-10-31. Not implemented, deployed, or proven within the Rules evaluation budget. Production financial access must remain blocked. No 9E.2B implementation is authorized.

## Recommendation and compatibility

Use a bounded, Rules-verified financial checkpoint, paired atomically with each expense or settlement revision. Preserve the exact existing greedy settlement policy through a **staged Rules-verified matching plan**, rather than trusting a client-computed suggestion or trying to evaluate all history in Rules.

This is a plausible Spark-only design, not a production approval. It requires an emulator feasibility proof and two explicit architectural restrictions:

- Initially cap each cloud group at **eight distinct retained identities over its entire lifetime**, including departed members. Existing groups can currently grow beyond eight through later additions; that behavior is incompatible with this initial bounded design. Rejoins reuse the original slot. Never recycle a departed member's slot or prune history to evade the cap.
- Permit a persisted **server-verified checkpoint** for cloud authorization. This is an explicit exception to the current prohibition on persisted calculated balances. It is not an independently writable balance table. SQLite and its existing derived-balance behavior stay unchanged. If this exception is unacceptable, production cloud financial writes must remain disabled.

All financial operations continue to use integer paise. Partial payments, current-member editing, immutable settlement identities, deterministic equal splitting, historical references, and the existing edit ceiling remain. Recurring bills never participate.

The design protects against hostile mobile/REST clients subject to Rules. It cannot prove an expense occurred or a manually recorded payment was actually made. Authorized current members can still correct old records under the existing policy. If “historical tampering” means forbidding even authorized corrections or retaining all superseded values, that conflicts with current overwrite-without-edit-history semantics and requires a separate product decision.

## Current implementation inspected

FirestoreExpenseRepository validates individual expenses/splits and atomically advances the shared group fence. FirestoreSettlementRepository brackets history reads with financial/membership revisions, applies the suggested-pair and old-record-excluded edit ceilings, and uses expected settlement revisions. Balances.calculate uses checked long additions and the existing sign convention. SettlementCalculator performs deterministic largest-debtor/largest-creditor matching with UID tie breaks; it does not guarantee a minimum transfer count.

Current Rules validate shapes, membership, exact bounded equal shares, immutable fields and paired revisions, but cannot derive history-based payment ceilings. The security review reproduced raw-client overpayments and fixed financial-fence removal. The demo-audience gate remains necessary. A transaction/fence alone does not validate the financial meaning of a write.

## Recommended documents

| Document | Proposed contents and authority |
| --- | --- |
| `groups/{groupId}` | Existing creator/admin/membership snapshots and membership revision; add immutable ledger schema version. Group IDs remain UUIDs; Firebase UIDs remain opaque. |
| `groupLedgers/{groupId}` | Schema version; monotonic ledger version; matching membership revision; eight stable UID slots with unused slots explicitly empty; eight integer net balances; total active expense paise; gross recorded settlement paise; retained record count; exact last-operation descriptor; verified matching-plan state; server update time. |
| `expenses/{expenseId}` | Existing complete revision snapshot, exact participants/shares, immutable group/creator/creation time, expected record revision, ledger version and deletion tombstone. |
| `settlements/{settlementId}` | Existing immutable group/payer/payee/recorder/creation time, amount/date, record revision and ledger version. No hard delete. |

The ledger is separate from the group document so plan-building writes do not update every Groups Home query result. Exempt vectors, matching-plan internals and membership maps from unnecessary indexing. Expense/settlement listing continues through authorized groupId queries; page history rather than rereading it for every balance update.

Suggested initial technical bounds, subject to the emulator proof: eight retained identities; eight expense participants; existing 10^12-paise per-record maximum; total active expenses and gross settlement amounts each at most 10^15 paise; at most 10,000 retained financial record IDs per group. Tombstones count toward the record limit. Limit strings, IDs, array sizes, timestamps, revisions and permitted map keys explicitly. These are proposed capacity restrictions, not existing verified limits or guarantees of fitting the project-wide free quota.

Bounding both aggregate totals keeps reconstruction intermediates and eight-slot calculations far below signed-long limits. Rules must check input types/bounds **before** arithmetic and validate exact integer results; they must not accept floats or depend on overflow wrapping. Reject operations exceeding limits. Do not silently reset totals, drop tombstones, prune members, or import an arbitrary nonzero opening balance. At a hard limit, preserve reads and reject unsupported writes; there is no automatic rollover carrying unverified debt.

No mandatory immutable revision journal is proposed: current authorized edits overwrite old values. Immutability applies to identity fields, tombstone semantics, and protected state transitions. An immutable audit trail would be an additional requirement with extra storage/writes, not a guarantee supplied by this design.

## Why the checkpoint can be authoritative

Trust comes from induction over Rules-validated transitions:

1. Group + ledger creation is atomic. Rules require all balances/totals/counts/version to start at zero, registered initial identities, and an exact membership mirror. Ledger deletion/recreation and nonzero initialization are denied.
2. Every permitted financial operation has exactly one target record. Rules read its actual old snapshot and final new snapshot, validate the record itself, and recompute its contribution to **every one of the eight slots**.
3. Rules require the new vector/totals/counts to equal the old values minus the old record's contribution plus the new contribution. Unrelated slots must also be checked, not merely the claimed “affected users.”
4. Both record and ledger require matching versions/target IDs through get()/getAfter(). Membership operations preserve financial values except adding a registered zero slot. No alternative write branch permits aggregate edits.

For identity u, define an expense contribution as `amount if u is payer, otherwise 0`, minus u's stored share. A deleted expense contributes zero. A settlement contributes `+amount` to its payer and `-amount` to its payee. Thus:

`newNet[u] = oldNet[u] - oldContribution[u] + newContribution[u]`

Expense totals change by new live amount minus old live amount. Gross settlement totals change by new amount minus old amount. New-record count increments only when the target did not exist; it never decreases on tombstoning. Exact shape, slot identity preservation, bounded integer values and zero-sum vector conservation are also required.

This blocks changing an untouched balance, inventing an aggregate, omitting the payer's credit, reversing settlement signs, changing only the ledger, and modifying a second record under the first record's pointer. It works for eight-way expense edits because Rules check all eight retained slots, including old/new payers and departed participants. It does not need to read the entire history.

## Transactions and authorization

For a financial mutation, the Android transaction reads the group, ledger and target record before writing the new ledger and target snapshot. Preserve the draft's expected record revision and expected ledger version; conflict means refresh/review, not incrementing a stale draft's revision and silently overwriting. Transactions may retry internally, but must not change the user's expected revision to make a stale edit succeed.

Rules must independently require:

- request.auth.uid is a current member of the same group. New settlement recording additionally requires the actor to be payer or payee; edits remain available to any current member.
- New expense payer/participants are current members. Edits can retain only the old record's original departed payer/participants. All referenced identities exist in retained slots/membership history.
- A single discriminated operation kind/target; ledger version increases by exactly one; record revision increases by exactly one; final record ledgerVersion equals the new ledger version.
- Exact record/ledger coupling in both directions, checked against actual before/after state. No finance operation may simultaneously masquerade as a membership or plan-maintenance operation.
- Group/party/creator/recorder/creation fields stay immutable as appropriate; server update timestamps, amount/date types, category, split keys/order/remainder and tombstone restrictions remain enforced.
- Group + ledger membership revisions stay paired. Adding/rejoining/transferring ownership/any future leave must use their dedicated atomic transition and cannot alter balances or bypass the ledger. Ordinary group edits cannot reset/remove financial metadata.

Transactions are useful for cooperative concurrency. Rules must reject invalid batched/REST writes too; they cannot assume the caller used the repository or even a transaction. Firestore's getAfter() supports requiring related documents to be updated atomically. [Atomic-operation documentation](https://firebase.google.com/docs/firestore/manage-data/transactions), [Rules conditions](https://firebase.google.com/docs/firestore/security/rules-conditions).

### Preserving the exact new-settlement ceiling

Do not silently replace the existing greedy suggested-pair ceiling with arbitrary debtor/creditor capacity. They are different policies.

After a financial or membership transition, the ledger initializes a plan for its new version: working vector exactly equals its verified net vector, empty transfer list, step zero. If every net is zero it is immediately READY; otherwise it is BUILDING.

Any current member can advance one plan step in an ordinary transaction. Rules, using fixed checks over eight slots, require:

1. The plan's base version equals the current ledger version; expected plan step advances exactly once.
2. Chosen debtor has the greatest remaining debt and chosen creditor the greatest remaining credit, using exactly the existing UID tie order.
3. The appended transfer equals min(debt, credit), is positive, and updates only those two working balances by that amount. All other working entries and the complete prior transfer prefix are unchanged.
4. The plan becomes READY only when all eight working balances are exactly zero. No truncated, skipped, reordered, arbitrary or oversized proof is accepted.

At least one nonzero identity is exhausted per step, so at most seven transfers are needed for eight identities. Rules never evaluate seven greedy steps in one request. A new settlement is accepted only against a READY plan at the current ledger version and only up to the exact amount in the matching suggested pair. Its transaction updates financial state and invalidates the plan again. Partial payments therefore produce a newly verified plan.

Plan-only writes cannot change financial/membership versions, true balances, totals, record counts, target records or identities. Financial writes cannot publish an arbitrary READY plan. Parallel builders use ledger-version/step checks; one wins and the other reloads. A changed expense/membership invalidates unfinished work. No Functions/background server is required: an active client drives these bounded steps. If it disconnects, another authorized client can resume. Until completion, suggestions/recording wait; there is no trusted “skip verification” path. Client tie ordering, including edge-case UID collation, must be proven identical to Rules.

For an existing settlement of old amount s, exclude it algebraically from the trusted vector: `basePayer = netPayer - s`; `basePayee = netPayee + s`. Capacity is `min(-basePayer, basePayee)` only when the former is negative and latter positive, otherwise zero. Rules enforce `newAmount <= max(s, capacity)` plus the normal positive/per-record/aggregate bounds. This preserves the current exception allowing retention/reduction after expenses change. It does not require a greedy plan for editing, and does not retroactively make an old manual payment invalid.

## History, concurrency and leaving

Historical expense corrections reverse the actual old contribution and apply the validated new one, including retained former members. Expense deletion uses a permanent tombstone; settlement identities/history cannot be deleted or reassigned. Existing payments are not rewritten when expenses change. Such corrections can reverse who owes whom; that is existing accounting behavior, not an overpayment authorization bypass.

One ledger document serializes a group's financial writes. Parallel partial payments, edits, deletion versus edit, membership change versus payment, and plan construction versus expense change must either commit against the same valid version or conflict. Never shard or reset the checkpoint merely to avoid contention: that would require a different consistency proof.

For a **future** leave design, require current non-admin membership, exact trusted net balance zero, and an atomic group + ledger transition against their current versions. Keep the historical UID slot and financial references. Admin transfer must occur first. A simultaneous expense changing that net causes one operation to conflict/re-evaluate. Zero is checked at departure time; existing permissions to edit historical expenses can create a later balance for a departed member. Preventing that would require restricting historical corrections, an explicit policy change. No leave code or Rules are enabled by this assessment.

## Rules limits, cost and feasibility gates

Current documented limits include 10 document-access calls per operation and 20 per multi-document atomic request, 1,000 evaluated expressions, 20 function-call depth, no recursion, 1 MiB documents and 10 MiB requests. Transactions have a 270-second limit and 60-second idle expiry. These are hard ceilings, not recommended operating sizes. [Firestore limits](https://firebase.google.com/docs/firestore/quotas).

Proposed conservative access-call targets **without assuming cross-operation caching**:

| Operation | Validation access-call target | Writes |
| --- | --- | --- |
| Financial ledger update | At most 6: group before/after, target existence/before/after with one spare | 1 |
| Paired expense/settlement update | At most 4: ledger before/after, group authorization and one spare | 1 |
| One plan step | At most 2: current/final group authorization consistency | 1 |
| Membership change | Group rule checks paired ledger; ledger checks group and bounded public identity where needed; total target below 20 | 2 |

The complete two-write financial request targets at most ten access calls, with each operation below ten. These are design budgets, **not measurements**; exact Rules must verify creation, missing documents, denial branches, membership changes and maximum eight-way edits. Do not duplicate the full transition validator in both record and ledger rules: the ledger validates the transition; the record rule validates its exact paired linkage. Check call/expression limits for the complete request, not isolated helpers. New-group creation must also retest the existing eight-profile lookup budget after adding paired ledger initialization.

Staged plan verification is intended to avoid a 1,000-expression failure for a whole greedy transcript, but even one step plus schema validation must be measured. If eight slots do not fit, reduce the hard bound and retest; do not weaken checks. Keep per-document payloads deliberately small, for example below a separately tested 64 KiB application cap. History is in separate bounded-size documents, not one ever-growing ledger array.

Ordinary financial changes use roughly three client document reads and two document writes, plus Rules-dependent reads, listeners and any transaction retry. Rebuilding a plan adds zero to seven single-document writes and client reads, plus authorization reads. A payment following a fully invalidated plan can therefore require up to nine writes in total. A listener receives plan-only changes too; ignoring their UI effect does not eliminate read consumption. Observe the ledger only while a relevant detail/settlement screen is active; avoid listener fan-out across all groups and page record history. Rules-dependent reads consume quota even for denied requests. [Firestore read/write accounting](https://firebase.google.com/docs/firestore/pricing).

For Firestore Standard, the documented free quota is 50,000 document reads/day, 20,000 writes/day, 20,000 deletes/day, 1 GiB stored and 10 GiB outbound/month, project-wide rather than per user/group. Use only the free database allowance. Indexes/history also consume storage; plan churn, reconnections and hostile authorized clients can exhaust quotas. Spark is a capacity limit, not an availability guarantee. Never enable billing to recover capacity; keep paid TTL/PITR/backup/restore features out of this design. [Standard free quota](https://firebase.google.com/docs/firestore/quotas).

## Alternatives and restrictions

- **Recommended, exact policy:** bounded verified checkpoint plus staged greedy plan. Preserves current new-payment and edit semantics at the cost of hard lifetime-member limits, extra round trips/writes and some waiting before settlement recording.
- **Simpler optional policy:** allow any debtor-to-creditor pair up to min(debt, credit), retaining the old-record-excluded edit exception. Same checkpoint proof, no staged plan. Greedy suggestions become advisory. This changes the existing new-payment rule and needs explicit approval; do not implement it as an equivalent optimization.
- **Read/sum history in Rules:** not viable for arbitrarily sized histories. Client queries/aggregation cannot become a trusted Rules proof by being placed in a transaction.
- **Per-member balance documents:** potentially useful later, but eight-way old/new payer/participant updates risk per-request access budgets and omitted-member proofs. They are more complex than a complete eight-slot vector; not the first design.
- **Single document containing all history:** soon hits size/expression limits and incurs large reads; would require severe record-count restrictions. Not recommended.
- **Client hashes, signatures, App Check or payer/payee confirmation alone:** do not prove arithmetic or debt capacity. They do not replace transition validation. Privileged Admin SDK/IAM operators bypass Rules and remain outside the mobile-client threat model.

No sufficiently secure **unbounded-member, no-persisted-checkpoint, exact-greedy, arbitrary-history** design has been established under these constraints. If the bounded/checkpoint/staged-plan restrictions are unacceptable or fail budget tests, keep production financial writes disabled. No paid backend is proposed.

## Adversarial proof and small-step implementation plan

1. Approve the schema exception, lifetime-member/capacity bounds and exact settlement policy. Keep current production gates. Specify bootstrap and permitted transitions before implementing adapters. Fresh cloud groups start at zero; never initialize this checkpoint from untrusted existing demo history through a client bypass.
2. In an isolated emulator-only Rules proof, test zero initialization and one expense transition. Exercise raw REST/SDK writes that omit or alter any slot, use wrong signs/remainders, forge identities, seed nonzero totals, bypass versions, mutate two records under one pointer, remove the ledger, recreate tombstones or mix membership and finance branches.
3. Add full eight-way edit/delete and retained-member tests, numeric/type/overflow boundaries and exact aggregate totals/counts. Compare every accepted generated operation sequence against the existing pure-Java reconstruction; every rejected operation must leave all documents unchanged. Verify source/compiled Rules and worst-case expression/access budgets with rejection branches included.
4. Prove one greedy plan step, then complete plans of zero through seven steps. Attack ties, fake working vectors, arbitrary transfers, prefix rewrites, skipped/excess steps, premature READY, stale base versions and plan-only financial changes. Compare complete plans exactly with SettlementCalculator, including UID ordering.
5. Add full/partial settlement and edit-ceiling proofs, stale/concurrent writers, departure races using fixtures, account switching and permission loss. Duplicate warnings stay advisory; do not claim hostile clients can be forced to display a dialog. Future leave authorization remains a separately approved milestone.
6. Only after the Rules proof passes with measured margin, integrate existing repository interfaces in the isolated demo graph. Cross-check ledger snapshots against history, cancellation/restoration and retry/idempotency behavior. Inconsistent preexisting data must be read-only/error, never silently “repaired” from client-supplied totals.
7. Independently review the resulting Rules, limits, cost profile and deployment plan. Explicit production approval is required before changing either the demo-only gate or live deny-all Rules. No production readiness is established by this document.

No new tests were run for this design-only task. Prior 9E.2A tests validate the existing emulator implementation, not this proposed checkpoint or staged verifier. Application code, Security Rules, cloud data, services and local data were not changed.
