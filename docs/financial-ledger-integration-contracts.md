# Financial ledger recovery and integration contracts

Date: 2026-11-09. **Proposed contract, not implementation or production approval.**
The ownership-fixed POC is the evidence baseline. This document defines the missing
durability and integration requirements; none of its schema/API extensions has
been implemented or measured. Keep SQLite/local authentication default and the
production financial hold. No application/Rules changes, experiments, deployment,
data writes, paid services or commits were performed for this document.

**Critical unresolved blocker:** the current settlement policy requires the exact
greedy suggested-pair ceiling. The POC proves selected capacity-policy cases, not
that policy. New-ledger settlement admission must remain disabled until an
equivalent server-enforced proof is validated. Client recomputation alone is
insufficient. The alternative policy in the appendix is not adopted.

## 1. Authority and exact document paths

All group, record, initialization-request and operation identifiers are canonical
String UUIDs. Firebase UIDs remain opaque and unchanged. The financial record key
is **(groupId, recordId)**; operations are addressed by **(groupId, operationId)**.
No global-record lookup or local UUID/cloud UID substitution is permitted.

| Path | Required data / authority |
| --- | --- |
| `groups/{g}` | schemaVersion; name; Flat/Trip type; immutable creatorId/createdAt; transferable adminId; metadata revision/updatedAt; immutable initialization descriptor containing requestId, selected initial UIDs, initial name/type |
| `groups/{g}/ledger/control` | controlRevision; accountingRevision; phase IDLE/PREPARE/APPLY; activeOperationId or null; validation/posting cursors; verified totalExpensesPaise/grossSettlementsPaise/currentMemberCount; updatedAt |
| `groups/{g}/accounts/{uid}` | immutable groupId/uid; ACTIVE/LEFT; original joinedAt/latest leftAt; public username snapshot; verified netPaise; accountRevision; last applied operationId/component index |
| `groups/{g}/records/{r}` | kind expense/settlement; groupId; expected/current revision; amountPaise and UTC occurredAtMillis; immutable creator/recorder/createdAt; updatedAt; lastOperationId/accountingRevision; expense tombstone; kind-specific fields below |
| `groups/{g}/operations/{o}` | immutable operationId/groupId/recordId/kind/original actor/expected record revision/accepted control revision/createdAt; PENDING or immutable COMMITTED/CANCELLED receipt; pending command while needed; resulting revision/terminal timestamp when finished |

Expense fields retain title, description, category, payerId, canonical sorted
participantIds and exact sharesPaise. Settlement fields retain immutable payerId,
payeeId and recordedBy; only amount/date are editable. Allowed field sets must be
kind-specific, not a permissive union. Use server creation/update timestamps and
existing normalized public identities. Accounts/discovery must not expose emails
or UPI IDs. Private profile/username-registry contracts remain unchanged.

The pending operation stores the actual old head and proposed new head. They are
immutable after admission. Progress has **one authority**, the control document;
the operation remains PENDING during PREPARE/APPLY rather than duplicating cursors.
At termination, atomically replace the pending payload with a minimal immutable
receipt. Retain identifiers, actor, expected/result revision and terminal status;
do not retain superseded amounts/dates as an edit-history feature. Retain receipts,
account history and tombstones; no client deletion, TTL or ID recycling.

Persisted account nets are a proposed **cloud-only Rules-verified checkpoint**
exception to the existing no-persisted-balances guideline. They are never directly
editable or trusted just because a client wrote them. Local balances remain
derived exactly as today. This exception requires explicit architecture acceptance.
Fresh versioned cloud groups only; no client import of nonzero legacy checkpoints,
local migration or dual-authority accounting is specified.

## 2. Operation state machine and transactions

| Transition | Atomic writes | Mandatory condition |
| --- | --- | --- |
| Admit BEGIN | control + new operation | IDLE; current actor; immutable scoped ownership; actual expected head; exact command shape/permissions; capacity/headroom admission; unique operation ID |
| PREPARE step | control | Same active operation/version; one validation cursor increment; frozen command; actual captured head unchanged; all prerequisites checked before APPLY |
| APPLY step | control + exactly one account | One deterministic component/cursor; exact integer delta; account revision and operation marker paired in both directions; no unrelated field/account changes |
| FINALIZE | control + scoped record + terminal operation receipt | All checks/components complete; actual head still equals captured before; stored group/revision preserved; totals updated exactly; COMMITTED receipt and IDLE publication together |
| Cancel PREPARE | control + terminal operation receipt | No components applied; fresh expected control version; unchanged heads/accounts/totals; CANCELLED receipt and IDLE together |

State progression is `IDLE → PREPARE → APPLY → IDLE`; receipts terminate as
COMMITTED or CANCELLED. All money operations use preflight, including settlement
and deletion commands whose POC currently skips PREPARE. There is no APPLY
cancellation, timeout unlock, ownership lease expiry or unverified compensating
balance write. Any change in expected state causes reload/reconciliation, never
a blind stale overwrite.

For an expense revision, components reverse the actual old payer/share effects
then apply the new ones. Deleted/absent sides contribute nothing. A settlement
reverses the old two-party effect and applies the new effect. Repeated appearances
of one UID are separate, fixed components; marker/cursor checks prevent double
posting. Every phase rechecks membership and scoped ownership through paired
Rules, not just publication. Another current member may maintain the frozen
operation; they may not replace its actor, amounts, parties or expected revision.

### Completion headroom is an admission invariant

Retain checked long paise and explicit technical caps. The POC uses per-record
10^12 paise, spending/gross totals bounded by 10^15, account magnitudes bounded by
4×10^15, and group/account revision maxima of 999,999,999. These are candidate
contract bounds pending validation, not permission to accept work at an exhausted
counter. Do not roll over or reset versions.

Before APPLY, validate **every affected account**, including old-only participants
and departed members. For each UID, reserve enough revision headroom for its
remaining occurrences and a conservative magnitude bound for all its components.
Membership/account-metadata changes stay frozen during the operation. Checking
only new participants, as in the current POC's preparation, is insufficient for
this stronger admission contract.

Let P be the component count, at most 18 for the existing eight-to-eight expense
edit. Let Q be candidate payer/share checks, at most 9. Specify a deterministic
preflight sequence of Q checks plus P account-resource checks, with duplicate
UIDs allowed; this avoids an unbounded scan or a client-supplied affected set.
Then C = Q + P ≤ 27. The operation's accepted control revision R must satisfy
`R + C + P + 1 ≤ CONTROL_REV_MAX`. Account revision A must satisfy
`A + remainingOccurrences(uid) ≤ ACCOUNT_REV_MAX`. New head revision must also
fit before BEGIN. An early preparation failure must still leave room to cancel.

These reservations must be Rules-enforced before balances change. If a BEGIN
cannot establish affordable structural/control bounds, reject it without creating
pending work. Account-resource failures discovered during PREPARE cancel safely.
Once preflight completes, numeric bounds cannot be left as avoidable mid-APPLY
surprises. This is a required correction to prove, not an existing tested guarantee.

## 3. Retry, acknowledgement and lifecycle contract

- Allocate a stable operation UUID before the first submission; keep the record
  UUID stable too. Persist only a private logical reference tagged with backend
  and signed-in UID when needed. Never persist passwords/tokens or local paths.
- A repeated BEGIN with the same ID cannot create a second operation. An existing
  PENDING ID can be resumed only for its exact canonical command. Different
  content conflicts. Terminal IDs cannot be reused, including CANCELLED IDs.
- A terminal receipt is retrieved through **reconcile**, not treated as acceptance
  of an arbitrary new command carrying the same ID. It proves that operation's
  outcome even if its record has since been edited again. Do not invent an old
  Expense snapshot from the current head or retain all old payloads to do so.
- A lost response is UNKNOWN until reconciled. Retry the same logical submission,
  not a fresh operation/payment. A missing read alone cannot prove an in-flight
  BEGIN will never commit. Cancellation requires a confirmed pending PREPARE;
  absence or a network failure must not be reported as CANCELLED.
- PREPARE cancel versus APPLY progression uses fresh control compare-and-set.
  A losing cancel returns the current state/NOT_CANCELLABLE, not success. Repeated
  terminal reconciliation returns the same immutable receipt without reposting.
- App termination, account switch, logout or observer closure stops delivery to
  that view/session; it does not roll back remote work. On restored authenticated
  profile readiness, reconcile saved references and the current group's active
  control before any new submit. Never attach a previous user's completion event
  to another account's UI. Another authorized member can resume with their own
  credentials; the initiator's password, token and device are unnecessary.

**Recovery guarantee is conditional:** while a usable current-member identity,
server access/quota, valid invariant-preserving data and reserved resources remain,
PREPARE can cancel and APPLY can advance to completion. POC recovery evidence
covers selected cases only. There is no guaranteed wall-clock completion, fair
scheduling under competing authorized requests, offline posting, or trusted
executor when no member returns. Loss of all usable identities and quota/storage
exhaustion can suspend progress indefinitely. Never clear that suspension by
discarding posted components. These availability limitations require acceptance.

## 4. Atomic membership initialization and administration

Preserve `createGroup(actor, group, memberIds, ...)`: **one atomic transaction**
creates group metadata, initial IDLE control, and zero accounts for the complete
initial selection, including creator/admin exactly once. Bind an immutable
creation request UUID and descriptor. Authoritative public identity documents
validate selected users. All accounts are ACTIVE, with server join time, no
departure, zero net and initial revisions/markers. Group/control Rules require
all selected accounts; account Rules require the matching group descriptor.
There is no visible partially initialized group or sequential-join success.

The existing technical maximum of eight **initial** members may remain for the
first contract proof. It is not a lifetime/current-group cap. New authorized
admin joins create additional independent zero accounts; rejoining updates the
existing retained account and preserves its balance/original join/latest departure.
Do not reset an account just because its status was LEFT. A repeated creation
request reconciles the immutable descriptor; conflicting request data fails.

After initialization, membership mutations are IDLE-only atomic transitions:

- **Add/rejoin:** current admin; registered target; control version increment,
  validated account creation/status update, exact member-count delta. No invitations
  or copied private profile data. Group metadata need not embed growing UID maps.
- **Admin transfer:** current admin and target both ACTIVE; group metadata/admin
  revision plus control revision change together. Preserve creator/createdAt,
  accounts, net values and count. No empty-admin state. Retry requires current
  authority and expected versions, not a blind successful overwrite.
- **Leave:** current non-admin; IDLE; authoritative net exactly zero. Update account
  to LEFT with server departure time and preserve all financial/history fields;
  decrement count and advance control atomically. Caller-supplied balance/time
  cannot establish eligibility. Transfer admin first. No leave/removal shortcut
  during pending work; resolve it before membership changes.

Account/control revisions must have capacity before each single administrative
transaction too. Group deletion remains unsupported. A later permitted historical
expense correction may give a departed member a new balance; zero is guaranteed
at departure time only, preserving the existing policy.

Admin name/type updates are also IDLE-only paired metadata/control transactions;
they preserve creator, initialization descriptor, financial values and membership.

## 5. Repository and observer integration

Proposed additive storage-independent boundary, not Java code to add now:

| Operation | Result / lifecycle contract |
| --- | --- |
| submit(command, stable operation reference) | One asynchronous acceptance/rejection/unknown result; not financial completion |
| observeOperation(groupId, operationId) | Typed immutable phase/progress or terminal receipt; supplied serial executor and cancellable subscription |
| advance(operation reference, expected control revision) | One verified step or current-state conflict/terminal result; no caller-supplied replacement financial data |
| cancelPrepared(reference, expected control revision) | CANCELLED receipt only when atomically safe; otherwise state/NOT_CANCELLABLE/UNKNOWN |
| reconcile(reference) | Authoritative pending/terminal/unknown result; no duplicate financial mutation |

Scope the coordinator to existing cloud repository ownership and AuthService/SDK
UID checks via GroupDependencies. SDK types, cursors and lifecycle objects stay
out of contracts. No second authentication/session store, global mode toggle,
UI-side scheduling/queries or unnecessary independent financial database owner.

FirestoreExpenseRepository maps full Expense models and ReceiptChange. Cloud
receipt changes remain unsupported. Require group+expected snapshot for deletes;
cloud ID-only deletion stays rejected. FirestoreSettlementRepository retains
immutable parties, original recorder, partial corrections and duplicate-warning
semantics. Existing one-shot callbacks remain one-shot: report domain success
only after matching publication. Accepted/unknown work must carry a recoverable
reference in typed status/error handling, not an ordinary “nothing saved” failure.
Restored operations reconcile through receipts rather than replaying save calls.

FirestoreGroupRepository replaces embedded member maps/array-contains authority
with retained accounts and own-membership discovery; it still exposes existing
group/member feature interfaces. Preserve timestamps, names and live authorization.
Balances and summaries use coherent committed checkpoints, with independent
history reconstruction for validation. Never mix SQLite data with cloud UIDs.

Observers require confirmed server metadata **and** an IDLE control fenced before/
after the relevant reads/pages. Pending account values are provisional even if
hasPendingWrites is false. Use unavailable/loading status instead of zeros or an
empty-success list. Read all needed retained accounts across pages before claiming
a complete balance map; partial pages are not a group balance. Close subscriptions
and suppress stale delivery on view stop/account change. Do not fan out history
listeners across Home; own-account discovery plus group/control reads for loaded
groups has an explicit read cost to validate.

## 6. Query and index contract

Candidate queries/indexes below are exact intended shapes, **not deployed or
validated indexes**. Rules are not filters; authorize each query shape explicitly.

| Purpose | Query | Candidate index |
| --- | --- | --- |
| Own active groups | collectionGroup(accounts), uid == actor, status == ACTIVE, groupId ASC, limit 50 | COLLECTION_GROUP accounts: uid ASC, status ASC, groupId ASC, __name__ ASC |
| Current members | groups/g/accounts, status == ACTIVE, document ID ASC, limit 100 | COLLECTION accounts: status ASC, __name__ ASC; verify whether automatic indexes suffice |
| Complete balance pages | groups/g/accounts, document ID ASC, limit 100, cursor pagination | Document-name ordering; no net-value index |
| Expense/settlement pages | groups/g/records, kind == requested kind, deleted == false, occurredAtMillis DESC, document ID DESC, limit 50 | COLLECTION records: kind ASC, deleted ASC, occurredAtMillis DESC, __name__ DESC |
| Posting/reconciliation | Exact control/operation/account/head document gets or listeners | No collection scan or additional composite index |

Only the UID owner can use the own-membership discovery query; account UID/group
fields are immutable and path-bound. Current group membership authorizes member,
record, control and operation reads; define any former-member access narrowly,
without granting private group queries. Maintenance may need provisional-account
gets, but cannot start money/leave decisions against non-IDLE data. Operations
have no general collection-list/edit-history API. Exempt command payloads, share
maps, amounts/nets and markers from indexes not needed by these queries. Reuse
exact username/public-identity gets and private profile restrictions unchanged.
The own-group discovery shape also needs an explicit collection-group list
authorization proof; the existing document-get rules do not supply it. Any such
read grant must not create generic write authority for other collections.

## 7. Proposed validation budgets and unresolved proofs

POC evidence: five ownership and seven affected cohorts passed; the corrected
eighteen-component edit completed. A sampled POST accepted seven extra distinct
gets and rejected eight; two additional 16-comparison chunks passed, four hit
1,000 expressions. These are not exact instruction/billable-read counts or
measurements of the schema in this document.

Conservative source-call planning estimates below assume validation is factored
without duplicating full business checks in every document rule. No estimate is
a demonstrated Rules guarantee; actual before/after views, caching and complete
error branches must be measured against the existing 10/20 access limits.

| Transition | Planned dependency-call estimate | Status |
| --- | --- | --- |
| Expense BEGIN, two writes | about 7 across request; largest single validator about 5 | Unmeasured; receipt/command split adds reads |
| Settlement BEGIN | about 9 before any exact-greedy evidence | **Not sufficient**: required policy proof has no established budget |
| One PREPARE check | about 5: operation, actor, target account, actual-head existence/data | Unmeasured for full fields/resource checks |
| One POST, two writes | about 5 per validator / 10 request before cache | POC shape passed; new operation-document dependency unmeasured |
| FINALIZE, three writes | about 13 request; largest validator about 6 | Unmeasured durable-receipt addition |
| PREPARE cancellation, two writes | about 8 request | Unmeasured receipt stripping/pairing |
| Add/rejoin/leave/admin transfer | roughly 8–12 request depending operation | Full metadata/query authority not measured |
| Initial N-member atomic creation | roughly 3N+2 source calls before shared-path reuse; root checks N account heads plus control | At N=8 the uncached estimate exceeds 20. Actual overlay/cache effects and equivalent factored checks require a dedicated proof; do not assume success or weaken atomicity |

For the proposed headroom preflight, an eight-to-eight edit has C≤27 validation
steps and P≤18 posting steps: up to 47 control writes, 18 account writes, two
operation writes and one head write — **68 document writes before retries**.
An eight-person create is about 41. These are contract estimates, not measured
billing. Receipts grow storage; finite Spark quotas can pause work. No paid TTL,
Storage, Functions, billing upgrade or unsafe receipt pruning is an escape path.

## 8. Unchanged settlement policy and smallest next validation

Required policy stays **EXACT_GREEDY_V1**: deterministic largest-debtor/creditor
matching with existing UID tie order, no minimum-payment optimality claim; new
payments cannot exceed the current exact suggested pair. Edits keep the current
old-record-excluded max(old amount, pair capacity) exception. Duplicate warnings
remain same group/parties/amount/UTC day, excluding edited ID, with explicit UI
acknowledgement and repeated financial validation.
Schema version fixes that policy; no client-writable operation/group override
may select the experimental capacity rule.

The POC capacity branch does not satisfy new-payment admission under this policy.
No complete server proof for exact matching over flexible membership is specified
or validated here. Fail closed for new-ledger settlements rather than retaining
capacity in Rules and calling a client greedy check protection. Existing local
and isolated application behavior is not changed by this contract document.

**Separate proposal, approval required:** replacing the exact suggested-pair
ceiling with debtor/creditor capacity while keeping greedy suggestions advisory.
This could simplify admission, but it is a product/security-policy change and is
not an active option, switch or fallback in these contracts.

Smallest next task, only if separately authorized: a disposable **expense-only
durability/headroom vertical slice** with atomic three-member creation, one stable
operation UUID, lost-ack reconciliation, PREPARE cancellation and another current
UID completing APPLY. Check the terminal receipt after a later record revision;
it must not repost or fabricate an old snapshot. Exercise admission headroom with
defensive boundary fixtures and measure full BEGIN/PREPARE/POST/FINALIZE/cancel
requests. Stop at a valid-operation blocker. Do not add UI, settlements, migration
or a large new POC; eight-initial-member and full metadata budgets remain separate
unmeasured gates after that smallest slice.

**Integration is still blocked** on (1) unchanged settlement-policy enforcement,
(2) completion/resource admission proof, and (3) atomic initialization/full-schema
Rules budgets. The contracts clarify required behavior; documentation alone
cannot resolve those validation gaps or claim unconditional recovery.
