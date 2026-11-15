# Financial ledger integration readiness

Assessment date: 2026-11-09. **Recommendation: NEEDS CORRECTION before staged
application integration.** The ownership-fixed POC is a useful foundation, but
its recovery contract, complete application schema and authorization/query model
are not yet integration-ready. Existing evidence does not require abandoning the
architecture; it also does not justify routing application writes through it.
Production readiness requires separate validation.

This is a documentation/source review only. No new security experiments, tests,
emulators, code/Rules changes, production operations or commits were performed.
The earlier interrupted experimental request was superseded by this assessment.

## Evidence used

Reviewed AGENTS.md; the POC Rules, harness and ownership tests; the recorded
`financial-posting-poc-results.md`; existing Firestore group, expense and settlement
repositories; their storage-independent interfaces; ObservableQuery,
RepositoryCallback, GroupDetails, Expense, Settlement, GroupMember and debug
dependency composition.

Recorded ownership-fix results: **5/5 focused cohorts**, **7/7 affected cohorts**,
and an independent audit of **11 groups, 38 accounts and 14 scoped heads**, all
consistent and IDLE. These remain historical results from their documented
environment, not tests rerun or extended by this review. The prior global-ID
failure was corrected with group-scoped paths; that does not establish complete
production-schema or lifecycle coverage.

## Schema and authorization compatibility

| Area | Application today | POC / required integration work |
| --- | --- | --- |
| Group metadata | name/type, immutable creator/createdAt, transferable admin, server metadata | POC control has admin/creator but omits name/type/timestamps and disallows admin changes. Preserve all application metadata and implement/test paired admin/metadata transitions |
| Membership | Embedded memberIds + retained members map; Groups Home uses array-contains | Separate retained account documents remove a fixed lifetime cap. Preserve names and original join/latest departure metadata; define authorized current/historical member queries and own-group discovery |
| Group creation | `createGroup(actor,group,memberIds,...)` promises atomic initial membership | POC creates the creator account, then joins others. This is not an equivalent contract. Specify an authorized atomic initial-member write or a completion-gated initialization protocol before adapting the API |
| Financial identity | Global expenses/{UUID} and settlements/{UUID}; models also carry groupId | Scoped groups/{g}/records/{UUID}, discriminated by kind. Always address records with group+UUID; preserve expected revision, creator/recorder and tombstones. No global-ID-only fallback |
| Financial fields | Full title/category/description, UTC dates, exact immutable split snapshots, creation/update metadata | POC contains minimal amount/date/participants/shares fields. Add and validate full field mappings; retest complete valid payloads before claiming budget compatibility |
| Balances | Server-confirmed history reads bracketed by financial/membership revisions, then checked domain calculation | Persisted Rules-verified account nets and control totals. This needs an explicit cloud-only exception to the current no-persisted-balances rule. Maintain reconstruction/reconciliation as an independent consistency check |
| Publication | One transaction per mutation, with group financial fence | Multiple server-confirmed preparation/posting commits before final publication. Local pending-write metadata alone cannot identify a completed business operation |
| Discovery/privacy | Existing profile/username/public-identity adapters expose minimum names; private profiles remain owner-only | Reuse that identity boundary. POC UID registration is a fixture, not a production profile implementation. Never put emails/UPI into general account/discovery documents |

The POC provides document `get` access but not application collection-list/query
authorization. Existing list listeners cannot simply be redirected to these paths.
Define and validate record-by-kind queries, membership/account queries and own-group
discovery, with only needed indexes. A discovery index must not become a trusted
membership mirror: authoritative account status must govern financial access.

Use fresh, versioned cloud test groups for future integration validation. Neither
local data nor old global cloud heads can be turned into trusted nonzero accounts
through client initialization. No migration, dual-authority write scheme or silent
combination of legacy and new accounting is approved. SQLite/local auth remain
default; Firebase UIDs remain separate from local UUIDs.

## Repository contracts that must be settled first

Keep AuthService-derived actors, the matching scoped SDK identity, asynchronous
I/O, serial callback executors and lifecycle cancellation. SDK/document types must
stay inside data/composition. Reuse GroupDependencies; do not create a global
local/cloud toggle or put posting orchestration in Fragments.

1. **Durable operation status and recovery.** ExpenseRepository and
   SettlementRepository currently return one terminal callback and no durable
   operation reference. RepositoryCallback promises exactly one success/error;
   CloudFinancialException has INVALID/FORBIDDEN/CONFLICT/UNAVAILABLE/CANCELLED/
   UNSUPPORTED, but no distinction for accepted-but-unfinished or unknown commit
   outcome. Specify a storage-independent group/record/sequence reference,
   observable posting state, and authorized resume/cancel semantics. A group
   posting coordinator behind the existing feature repositories is sufficient;
   a parallel authentication or accounting owner is not needed.
2. **Success, failure and retry semantics.** Success means committed publication,
   not BEGIN or a posted component. A timeout/logout/closed view after acceptance
   must not imply rollback. Reconcile server operation/head state before retrying;
   retain the original expected revision and logical operation reference. Never
   silently start a duplicate payment or overwrite a newer record to make a retry
   succeed. Cancellation of callbacks/subscriptions is not financial cancellation.
3. **Group-scoped deletion.** The existing version-aware
   `deleteExpense(actor, Expense expected,...)` already carries group+revision;
   use it. Keep cloud ID-only deletion rejected or explicitly change that contract.
   Settlement deletion remains unsupported. Tombstone identities cannot be reused.
4. **Observable committed snapshots.** Add a typed posting status stream alongside
   current data streams. GroupDetails has availability flags but no durable phase/
   operation progress. During pending work do not present provisional account nets
   as final, fabricate zeros, or interpret temporary unavailability as an empty
   group. Publish a coherent IDLE snapshot with matching control revision and
   rechecked membership; close subscriptions on lifecycle/account changes.
5. **Membership operations.** Existing GroupRepository methods can remain feature
   boundaries, but creation/add/rejoin/transfer/leave must coordinate with the
   posting gate. `leaveGroup` must ignore caller-supplied eligibility and use the
   authoritative zero balance. Query and timestamp semantics must preserve
   GroupMember history. Client-calculated zero alone remains insufficient.

The local adapters must retain their current atomic behavior and contracts.
Cloud receipt operations must stay explicitly unsupported; this assessment does
not authorize Storage, uploads or a new service.

## Normal workflows: verified portions and gaps

| Workflow | Existing recorded evidence | Still needed |
| --- | --- | --- |
| Expense create/edit/delete | Exact integer shares, rotated eight-person edit across twelve accounts, revision updates and tombstone protections passed | Full model fields/server metadata, SDK callbacks, authorized queries, process restoration and resource boundaries |
| Settlement recording/correction | Partial capacity-valid payments, any-member amount/date correction, immutable parties and selected invalid inputs passed | Application policy acceptance, production metadata/privacy, unknown-ack retry and lifecycle integration |
| Concurrent writes | Concurrent direct REST commits, one-winner operation claims, stale revisions and leave races passed | SDK transaction retry behavior and durable reconciliation after uncertain acknowledgements; no automatic stale overwrite |
| Interrupted work | Another current UID resumed selected APPLY work; PREPARE cancellation and stale continuation checks passed | General recovery preconditions, counter/resource headroom, auth/network/quota failure cases and fairness/availability limits |
| Historical membership | Ninth member joined after departures; twelve retained accounts; LEFT account retained a later balance and rejoin preserved it | Timestamp/name/profile mapping, scalable membership listings and the full admin-transfer workflow |
| Leaving | Tested current non-admin zero-at-IDLE checks and pending/nonzero rejection | Integration of prior admin transfer, full membership metadata and real lifecycle handling. Later historical corrections may still create a former member's balance |

The POC's new-payment rule is debtor/creditor capacity, while the existing
repository restricts new payments to exact greedy suggested pairs. The suggested
UI can look the same, but server authorization differs. Explicitly accept or
reject this product-policy change before implementation; do not label it an
equivalent adapter optimization. Duplicate warnings remain advisory and manual
claims are not independent payment verification.

## Can every interrupted operation be recovered?

**That universal guarantee is not established.** Selected valid PREPARE operations
were safely cancelled; selected APPLY operations were completed by another current
member without the initiator. APPLY cannot be cancelled or unlocked by timeout,
because account components may already be posted. That restriction is part of
the financial safety model, not an omitted cancel button.

Recovery depends on a usable authorized identity, server connectivity/quota,
unchanged valid prerequisites and sufficient remaining numeric/revision capacity.
If no current user returns, no no-cost trusted executor completes the operation.
Repeated competing authorized activity can also affect availability. The earlier
tests do not prove an adversarial progress/fairness guarantee or recovery after
every authentication/resource failure.

Source review additionally identifies finite group/account revision bounds without
an explicit documented admission reservation for all remaining posting writes.
Completion headroom must be checked/proven before accepting work, including
repeated updates to the same account. This is an **untested defensive concern**,
not a newly reproduced vulnerability or a confirmed deadlock in this review.
Do not claim universal recovery until that concern and terminal-boundary behavior
have been addressed and validated in separately authorized work.

## Rules complexity and quota evidence

The documented eight-way valid creation/edit path, including eighteen posting
components, completed after the ownership fix. The sampled real POST accepted
seven extra distinct reads; eight exhausted its access allowance. Two extra
16-comparison chunks passed; four reached the 1,000-expression ceiling. The
independent probes observed ten versus eleven single-write gets and twenty versus
twenty-four atomic-request gets.

Those measurements concern the minimal schema and sampled branches. Coverage
trees do not provide exact per-request instruction/billable-read counts. There is
no quantitative headroom proof for the most complex **full application** BEGIN,
PREPARE, FINALIZE, profile/member queries or metadata transitions. Adding the
missing fields/authorization must not be made to fit by weakening checks or
imposing a fixed lifetime-member cap.

Preparation, two-document posting steps, publication, retries and listeners all
consume finite Spark quotas. Longer edits need many writes even when their final
net effect is small. Partial quota exhaustion can delay completion. Existing local
emulator timings are not production latency, capacity or availability evidence.
Keep billing disabled; no paid service is proposed to resolve these limitations.

## Readiness gates and recommendation

**NEEDS CORRECTION.** Before staged mutating application integration:

- Specify durable pending/committed/unknown outcomes, idempotent reconciliation,
  lifecycle cancellation and recovery admission/headroom semantics.
- Resolve the settlement-policy and verified-checkpoint architecture exceptions.
- Complete the schema/query/atomic-creation/admin-transfer mappings while
  preserving group-scoped ownership, history and flexible lifetime membership.
- Obtain separately authorized defensive validation of those missing contracts
  and the most complex complete valid requests. Existing POC successes must not
  be silently promoted to coverage of new fields, queries or app behavior.

There is no new experimentally confirmed financial vulnerability in this review,
and no evidence here requiring a major architectural redesign. The unresolved
contracts and availability assumptions nevertheless prevent a READY verdict.
Keep the production hold and existing application behavior. No integration or
new experimental work is authorized by this document.
