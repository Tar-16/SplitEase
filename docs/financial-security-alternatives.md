# Financial security alternatives — 9E redesign

Date: 2026-11-08. Design assessment only. No implementation, Rules deployment,
cloud data write or new service activation. Keep SQLite default and the existing
production financial security hold.

**Recommendation: A, redesigned as a bounded-operation ledger with one retained
account per member and small resumable posting steps.** Do not revive the global
eight-slot vector or staged greedy matcher. Make greedy recommendations advisory;
enforce a precise debtor/creditor capacity policy against Rules-verified accounts.
This is a candidate for one small falsification experiment, not a production
security approval. No simple, single-request design satisfying all requirements
has been established. The recommended protocol adds availability costs described
below; those costs must not be concealed as ordinary atomic transactions.

## Evidence and constraints

Reviewed AGENTS.md, FirestoreExpenseRepository, FirestoreSettlementRepository,
FirestoreFinancialRevision, CloudExpenseValidation, SettlementCalculator, the
current candidate Rules, and the earlier ledger design/POC results and source.

Current expense Rules validate sorted equal shares for up to eight participants
per expense. That existing **per-record technical bound is not a group membership
or lifetime bound**. The repository checks payment ceilings against history;
Rules do not. The demo audience gate protects production activation. Existing
groups keep all current/historical memberships in one document, which has a
technical size ceiling even though later additions can exceed eight.

The previous POC accepted zero bootstrap but its first valid eight-way expense
hit the engine's 1,000-expression ceiling. Settlements, concurrency and matching
were not verified. Its successful access probes do not establish the financial
validator's read count. These findings reject that implementation, not every
possible Rules-verified aggregate. See `financial-ledger-poc-results.md`.

Rules can require paired writes with getAfter(), but cannot query/sum unbounded
history. Limits remain ten access calls per operation, twenty per atomic request
and 1,000 evaluated expressions. A caller's use of a transaction, acknowledgement
flag, hash or claimed net balance does not prove financial correctness.
[Rules conditions](https://firebase.google.com/docs/firestore/security/rules-conditions),
[Firestore limits](https://firebase.google.com/docs/firestore/quotas).

## Comparison

| Criterion | A. Bounded-operation, verified per-member ledger | B. Independent records + history-derived balances | C. Realtime Database financial authority on Spark |
| --- | --- | --- | --- |
| Hostile expense writes | Record shape, deterministic shares and every account delta must be Rules-verified | Strong per-record validation is possible; no trusted group/member net follows from it | Potentially enforceable with a different Rules model; no completed proof |
| Settlement ceiling | Constant-size checks against two verified accounts; partial payments allowed | Positive amounts/immutable parties can be enforced. History-derived debt capacity cannot. Mutual confirmation proves consent, not capacity | Needs verified accounts or another financial proof too; changing databases does not establish debt |
| Concurrent changes | Expected record revisions plus one group operation gate; bounded account steps cannot be skipped/replayed | Record revision checks work; a group fence orders records but cannot establish capacity or zero balance | Root/subtree transactions or revision-checked multipath updates; requires new concurrency proof |
| Membership scalability | One document per retained UID; no fixed lifetime member count | Scalable membership documents possible, but leaving eligibility remains unproved | Per-UID membership possible; additional database capacity/connection limits |
| Historical edits | Reverse actual old components, apply new components, retain former accounts/identities | Recompute history correctly on cooperative clients; no safe server-side zero/ceiling decision | Possible only with equivalent reversal/deletion checks; migration/rewrite risk |
| Rules limits | Validate one participant/account per step; no whole-group vector or greedy scan | Lowest per-record cost, but required aggregate decisions are absent | Different validation/access semantics; parent grants and null/deletion behavior need care |
| Quotas and latency | More writes/round trips per mutation; cheap balance reads; serialized per group | Cheap writes, growing history-query/listener reads; confirmation may add writes | Bandwidth-based usage and additional SDK/operations surface; not automatically cheaper |
| Existing feature fit | Preserves exact money, history, partial payments and zero-at-leave semantics; changes server settlement policy and save availability | Best storage compatibility, but fails required secure leaving/overpayment constraints | Largest adapter/authority change; cannot safely mirror Firestore membership with client trust |
| Assessment | Recommended for a bounded proof, conditional on the protocol/security tests | Reject under the stated non-negotiables | No demonstrated security advantage justifying the rewrite |

### A: what is bounded, and why it differs from the failed POC

Bound the amount of work in **one request**, not the number of people who may
ever join. A large history creates more retained account/record documents, not a
larger global balance vector or membership map checked by every financial write.
No member slot is recycled and no history is pruned to make validation fit.

A naive atomic fan-out is not an established substitute: editing an expense can
touch the union of old/new participants and old/new payers. Eight participants in
each revision can touch eighteen distinct identities. Checking the completeness
of that fan-out can exceed a single write's access budget, even if each account
update is individually correct. A forged omitted account is not repaired by a
transaction. Nor can we rely on unspecified cache behavior to promise a fit.

Use a single protocol with small verified steps rather than silently prohibiting
edits whose old/new participant union is large. Retain the existing cloud bound
of eight participants per expense for the first experiment; expansion beyond it
is a separate technical proof, not permission to limit the group to eight users.

### B: why a simpler payment policy does not solve secure leaving

Membership checks, fixed settlement parties, integer/date bounds, revisions and
duplicate warnings are useful, but insufficient for the zero-balance rule.
Allowing any signed claim, requiring both parties to approve it, or recording it
against a particular expense does not prove the member's group-wide net is zero.
Two malicious consenting accounts can also make claims exceeding group debt.

Per-expense reimbursement caps would change the current group-net settlement
model and still need protection against duplicate allocations and aggregate
leaving checks. Client history queries, a client-uploaded zero certificate and
the current financial fence supply no independent server proof. B is acceptable
only if overpayment constraints and secure zero-net leaving are relaxed or
removed; those changes violate this task, so B is not the recommendation.

### C: a genuinely no-billing option, without assuming it is safer

Firebase Realtime Database has a Spark allocation. Its Rules expose old/new
state and cross-path validation; its documented Spark simultaneous-connection
limit is 100. Thus it merits comparison without proposing a paid backend.
[Firebase pricing](https://firebase.google.com/pricing),
[RTDB limits](https://firebase.google.com/docs/database/usage/limits).

However, Rules validation is ignored on deleted/null values, and broad parent
write grants can bypass intended child write restrictions. Reversal completeness
and exact arithmetic would require a fresh proof, particularly for expense edits
removing participants. Root transactions over growing group history increase
transfer/retry costs. [RTDB Rules conditions](https://firebase.google.com/docs/database/security/rules-conditions).

Moving only payments to RTDB while treating a client-maintained mirror of
Firestore membership as authority is unsafe: there is no atomic transaction
spanning these databases. Making RTDB authoritative for membership and all
finances is a significant rewrite, adds another service/SDK and leaves the same
aggregate-proof problem. Numeric serialization and exact safe integer bounds
would also need explicit validation. No RTDB feature is enabled or recommended
here. Hosted “free tiers,” Functions and services requiring a billing account
are not substitutes considered safe under the cost restriction.

## Recommended schema and security argument

Proposed future paths, not implemented:

- `groups/{g}`: bounded metadata, immutable creator and transferable admin. Do not
  retain all membership history in this document.
- `groups/{g}/accounts/{uid}`: immutable group/UID, original join/latest departure,
  ACTIVE/LEFT status, public name snapshot, verified net paise, account revision
  and last applied operation/step marker. Retain documents after leaving.
- `groupControls/{g}`: monotonic operation version, IDLE/PREPARING/APPLYING phase,
  a single immutable pending command, validation/posting cursors, verified spending
  and gross settlement totals. No member vector or whole-history list.
- Existing expense/settlement heads: canonical UUID ID, financial payload,
  revision, immutable identity fields, server metadata and expense tombstone.

Normal repository operations still derive their actor from AuthService; Rules
derive authority from request.auth.uid. Membership/permission checks use exact
account paths. Own-membership queries and authorized per-group member queries
replace the existing embedded-history approach; any discovery mirror must be
atomically paired and narrowly readable. Do not expose emails/UPI via membership
or discovery records. This is a real future storage adaptation, not a drop-in
change to today's composition root.

Each first membership/account is initialized to zero with an authorized join;
initialization is allowed only when that retained account never existed. Rejoin
preserves the old net and history. No account deletion/reset, self-assigned
membership, nonzero opening balance or client-imported historical checkpoint.
Start with fresh versioned cloud groups; legacy demo history cannot simply be
declared trusted. No local migration is proposed.

The proof is induction: zero accounts plus an exact complete sequence of expense
and settlement components implies exact per-member nets. Security comes from
Rules enforcing that sequence and its publication, not from the client maintaining
an aggregate. Persisted verified accounts require an explicit cloud-only exception
to the current no-persisted-balances architecture rule. If that exception is not
accepted, no qualifying solution has been established here.

## Small-step financial protocol

1. **BEGIN, while IDLE.** Require current membership, expected head revision,
   fresh operation version and correct permission. Capture the actual old head
   and candidate new head in a bounded immutable command. Validate IDs, fields,
   positive bounded long paise, dates, sorted unique participants and exact share
   keys. Freeze group financial and membership mutations. New settlement actor
   must be payer or payee. Its ceiling is checked against the two stable accounts.
2. **PREPARE.** Check one new participant/payer at a time against retained
   membership and the existing historical-retention rule. For participant index
   i, verify its stored share is exactly quotient plus deterministic remainder
   paise. Candidate amount/list/shares cannot change between checks. Check all
   prerequisite existence and numeric bounds **before any balance changes**.
   An invalid candidate must be safely abortable during PREPARING; otherwise a
   malicious last participant could permanently wedge a half-posted operation.
3. **APPLY.** Advance exactly one cursor together with exactly one account write.
   Rules derive the target UID and delta from the immutable command and cursor.
   For an expense edit, reverse old payer credit and each old share, then apply
   new payer credit and each new share. Each identity can occur more than once;
   every occurrence is a specified component, not an arbitrary full delta.
   Creation/deletion omit the absent/deleted side. Settlements reverse the old
   two-party effect and apply the new two-party effect.
4. **FINALIZE.** Only after every required component is acknowledged, atomically
   publish the new record head/revision, update verified totals and return to
   IDLE. Exact head/control coupling denies independent publication, skipped
   components and a second unrelated financial record in the batch.

An APPLY account write must require a matching, fresh cursor transition. The
control write must require that same target account's new revision and operation/
step marker. Reject all changes to unrelated account fields, membership status,
command contents and other accounts. Version/cursor checks prevent replay or
cross-operation acknowledgements. Financial heads cannot change outside FINALIZE;
no alternative “metadata update” branch may reset the control or net values.

After APPLY begins, completion is mandatory; no timeout unlock or cancellation
that leaves partial deltas. Any current member may resume verified maintenance
steps without becoming the settlement's recorder. The original initiating actor
and permissions are bound at BEGIN. PREPARING can abort without financial effects;
APPLYING cannot. Group membership/admin changes remain frozen until IDLE, so a
validated account cannot disappear or lose required eligibility mid-operation.

This is **not one atomic business transaction**: each step is atomic, publication
is gated, and partially applied accounts are provisional. Normal financial
screens must show processing/unavailable until IDLE; do not label partial nets
as balances. Maintenance clients may need authorized reads of provisional
accounts. Rules must prohibit new financial decisions and leaving against them,
rather than pretending such reads can always be hidden from every client.

An abandoned APPLY or exhausted Spark quota can make the group unavailable until
another client resumes or quota becomes available. No client/server background
delivery guarantee exists. This is the major cost of avoiding a trusted paid
executor. If uninterrupted availability or single-request commits are required,
this candidate does not meet those additional requirements.

## Enforceable settlement and leaving policy

For stable nets B, payer p and recipient q, propose:

`0 < payment <= min(-B[p], B[q])`, requiring `B[p] < 0` and `B[q] > 0`.

This rejects zero-debt and excessive new payments using two authenticated,
Rules-verified account documents. It permits full/partial payments to any eligible
creditor. It does not invent pairwise obligations from aggregate nets.

**Explicit product-policy change:** current repository new-payment limits use
the exact greedy suggested pair. The proposed server policy uses debtor/creditor
capacity instead. The existing greedy UI can continue recommending the same
transfers, but the server would also permit other capacity-valid pairs. Do not
describe these policies as equivalent. This change needs explicit acceptance
before implementation; if exact greedy-pair enforcement is mandatory, this
recommendation is insufficient without another, independently proven protocol.

Editing preserves the current exception: exclude the actual old record s by
`baseP = B[p] - s.amount`, `baseQ = B[q] + s.amount`; pair capacity is zero unless
baseP is negative and baseQ positive. Require new amount at most
`max(s.amount, min(-baseP, baseQ))`, taking that min only for the valid signs.
Any current member may edit amount/date; parties/group/recorder remain immutable.
This intentionally allows retaining/reducing an old payment after expense changes.
Duplicate warnings remain advisory, because confirmed duplicates are allowed.

For leaving: while IDLE, require the actor's trusted account net exactly zero,
current membership and actor != current admin. Atomically set LEFT and advance
the same control version used to serialize financial BEGIN; preserve the entire
account/net/history. A concurrent financial operation or admin change must
conflict/re-evaluate. A client-supplied zero or separate membership-only write
cannot authorize leaving. Admin transfer remains a prior authorized operation.
Later permitted historical corrections may create a balance for a former member,
as under the existing local policy; zero is guaranteed at departure time only.

Nonnegative integer dates, positive integer amounts, canonical splits, conserved totals and exact account
deltas are enforceable goals. Whether a real expense/payment happened is not:
current members remain authorized to enter/correct claims. Do not introduce a
new approval workflow or call manual records independently verified payments.

## Limits, costs and compatibility

No phase scans every group member or history entry. Each account step aims for
at most four document accesses per write/eight per two-write request without
assuming caching: control before/after, one target account and acting membership.
BEGIN/FINALIZE have separate budgets. These are targets, not measured guarantees;
the complete Rules request and its error branches must fit before adoption.
Membership count increases retained documents, not this per-step work.

Keep money/counters strictly bounded before arithmetic. Existing per-record
10^12-paise bounds can remain; a proposed aggregate spending/gross-payment bound
of 10^15 each provides headroom for component intermediates within checked long
range. Verify exact totals at BEGIN/FINALIZE and forbid reset/overflow escape
paths. No recurring records enter the component stream. Retained history and
groups still face finite storage/quota; no architecture promises infinite free
capacity or permission to delete history when full.

For n old and m new expense participants, APPLY uses at most n+m+2 components;
an eight-to-eight edit needs up to eighteen two-document writes. PREPARE needs
up to m+1 small checks, plus BEGIN/FINALIZE. If BEGIN writes only the control and
the last preparation step also enters APPLY, the sketch is
1 + (m+1) + 2(n+m+2) + 2 document writes: up to 48 for that edit before retries
or discovery mirrors. A new eight-way expense costs about 30 writes because
there is no old side to reverse; a new settlement needs about seven if BEGIN
validates both parties/ceiling and enters APPLY, plus any separate preparation transition.
These are protocol estimates, not billing measurements or acceptable latency
claims. A later POC must specify/count every phase transition exactly.

Account reads avoid repeated full-history queries for balances/leave/payment
capacity. History remains paginated for expense display/reconciliation. Observe
only relevant control/accounts/records; control progress itself causes listener
reads. Ignore UI-only progress changes without assuming that saves billed reads.
Rules-dependent reads, denied attempts and reconnects also consume capacity.
[Firestore usage accounting](https://firebase.google.com/docs/firestore/pricing).

Standard free quota is project-wide: 50,000 reads/day, 20,000 writes/day, 1 GiB
stored and 10 GiB outbound/month. At 48 writes/edit, even 400 such edits consume
19,200 writes before other use. This is a small-app trade-off, not a scalable
free-backend promise. Keep Spark and fail safely on quota exhaustion; do not
enable billing, paid backups/TTL, Functions or Storage.
[Standard quota](https://firebase.google.com/docs/firestore/quotas).

Unchanged user behavior can include equal shares/paise, expense editing/deletion,
historical references, partial settlement entry, familiar greedy suggestions and
zero-at-leave. Required changes are the server ceiling policy, verified account
storage, membership document layout and a longer resumable save/processing state.
The earlier explicit eight-slot lifetime cap is withdrawn as a candidate; do not
weaken or modify the existing hold to implement any of this automatically.

## One small falsification POC — proposal only

Do not implement a new app adapter or full ledger. Use a separate disposable
Auth/Firestore emulator project and one bounded scenario, at most 180 seconds:

1. First test the smallest BEGIN + one account/cursor step + FINALIZE pairings
   with direct authenticated REST writes. Prove that missing account writes,
   missing control writes, cursor jumps, replay, forged deltas, different target
   UIDs and unrelated account writes cannot succeed. Stop at the first semantic
   exploit or valid-write evaluation-limit failure.
2. If that holds, populate twelve retained membership/accounts through the
   proposed authorized zero-join path. Exercise one 101-paise eight-person
   expense and one edit rotating old/new participants across all twelve UIDs.
   Compare final accounts with independent integer history reconstruction.
   Check the largest BEGIN/PREPARE/APPLY/FINALIZE requests, not merely one easy
   intermediate step. No eight-person group/lifetime restriction is permitted.
3. Include one invalid last participant in PREPARE: abort must leave every balance
   unchanged. Stop an APPLY halfway and resume with another current account;
   partial publication, manual unlock and starting a second payment must fail.
4. Test one overpayment rejection, one partial capacity-valid settlement, and one
   zero-balance leave racing BEGIN. Membership/account reset, stale edits and
   unpaired LEFT writes must fail. Preserve one departed participant through a
   valid historical correction. Keep these focused assertions in the same
   bounded run; no large UI journey or quota load test.

Observe actual engine failures, controlled access-call boundaries, coverage
limitations, request counts and measured elapsed time. Do not count intended
invalid writes rejected solely by budget exhaustion as proof their semantic
guards ran. Any accepted forged balance/premature publication, unrecoverable
valid operation, nonzero leave, or valid worst-case request exceeding limits
disproves the candidate **as written**. Stop and report; do not iterate through
weaker checks, smaller hidden lifetime limits or paid fallbacks.

If the policy change, verified-account exception or availability/quota cost is
unacceptable, no qualifying simple solution is established by this assessment.
Keep production financial access blocked rather than selecting history-only B
and claiming client checks enforce leaving/overpayment security.

No tests or POC were run for this document. Existing app code, candidate Rules,
POC artifacts, services and data were preserved; no commit was made.
