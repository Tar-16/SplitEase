# SplitEase architecture

- Application ID / namespace: `com.example.splitease`. Minimum SDK 24. Use stable SDKs supported by the pinned Android Gradle plugin.
- Write application code in Java, XML layouts, Android Views and Material Components. Enable ViewBinding; do not add Kotlin application sources or Compose.
- Keep one MainActivity with AndroidX Navigation and Fragments. Navigation graph destination IDs must match bottom navigation menu IDs. Release Fragment bindings in `onDestroyView`; collect updates only during the appropriate view lifecycle.
- Keep Activities and Fragments concerned with presentation and navigation. Never put SQL or Firebase queries in UI classes.
- `model` contains plain Java entities; all entity IDs are canonical `UUID.randomUUID().toString()` values, never numeric database IDs. Use the immutable Milestone 2 model snapshots; association identities are composite UUID foreign keys.
- `domain` contains business logic independent of Android UI and persistence. It must not import Android, SQLite or Firebase types.
- Represent all money as `long` integer paise. Never use float/double for money calculations or storage. Use checked arithmetic for overflow and define rounding/remainder rules explicitly when implementing splits. Decimal conversion is permitted at input/display boundaries using BigDecimal, never via double.
- `repository` owns storage-independent interfaces. Operations are asynchronous, nonblocking and explicit about success/error, cancellation and callback executors. Use observable updates for live lists/balances; unsubscribe when owners stop. Never expose cursors, Firebase SDK types or UI lifecycle objects through contracts.
- `data` implements repositories: SQLite in Phase 1, Firebase Authentication/Firestore in Phase 2. Use the existing app-scoped SqliteRepositories owner and feature interfaces. Add new schema changes as explicit additive migrations, never by changing a previously applied version or using drop/recreate.
- Use strings resources, shared styles and semantic colors with light/dark variants. Preserve teal #0F9D8A branding; use accessible foreground variants. Keep layouts usable with large fonts, small screens, landscape and system insets. No fake actionable controls.
- Keep dependencies pinned and minimal. Do not introduce authentication, payments, OCR or new product assumptions without a scoped milestone request.
- Validate changes with `./gradlew assembleDebug testDebugUnitTest lintDebug`. For UI/navigation changes also check launch, all three tabs, Back, rotation, light/dark and large fonts on a device/emulator when available. Report blockers truthfully; source checks do not prove an Android build or runtime behavior.
- Maintain README setup, milestone status and verification notes. Preserve existing user work. Prefer simple implementations over unnecessary layers, frameworks or abstractions.

## Milestone 2 invariants

- SQLite schema version 5: v1 core accounting, v2 isolated recurring tables, v3 original creator attribution, v4 recurring generation cursors/reminder receipts, v5 receipt metadata. Never delete user data on upgrade or downgrade. Preserve foreign keys, parameter binding and atomic writes with permission checks inside transactions.
- Retain group creator separately from transferable admin ownership. Historical membership stays identifiable. Leaving requires exactly zero derived balance and prior admin transfer; no SQL deletes on leave.
- Equal splits use sorted UUID order for deterministic leftover paise; shares must equal the expense total. All arithmetic is checked `long` arithmetic. Do not persist balances.
- Expense edits may retain historical participants. Only current members can mutate group records. Settlement group/payer/payee are immutable; amount/date edits overwrite. Partial settlements are valid and duplicate-looking records are not blocked.
- Recurring bills/cycles/contributions must never generate ordinary expenses/settlements or enter balance calculations. Snapshot amount, handler and participants/shares per cycle; new cycles start unpaid; `(bill_id,year,month)` is unique. Any current member can toggle any cycle participant's paid flag.
- Do not destroy historical cycles when changing/deactivating a bill. Bills/groups with recurring history are protected from deletion. Preserve original join and latest departure metadata on membership rows; there is no full membership audit log.
- Keep callbacks on the explicitly supplied serial executor. Close subscriptions with UI view lifecycle. Production uses one repository owner; external writes do not participate in observation. Closing the owner queues closure after accepted operations.
- Local password credentials use salted Argon2id with stored algorithm/cost metadata. Never expose hashes/salts in User UI snapshots or store/log plaintext. Preserve database backup/transfer exclusions. Local authentication/session and profile flows are now implemented; actor IDs must come from AuthService, never arbitrary UI input.
- Run local tests and real SQLite instrumentation tests when an emulator is available, including the migration and recurring-isolation cases. Keep navigation/theme behavior intact. Record exact verification outcomes and device coverage in the milestone verification documents under docs/.

## Milestone 3 authentication rules

- Reuse AuthService/LocalAuthService, UserRepository and the existing Argon2id implementation; do not introduce parallel password/session infrastructure. Firebase adapters will replace AuthService later with fresh accounts/data; do not migrate local credentials to Firebase.
- Normalize username/email consistently with AccountValidation. Keep database UNIQUE constraints authoritative. Signup enforces the displayed five-rule password policy and confirmation. Login verifies real credentials off the main thread, without a fixed attempt limit or lockout.
- Never expose internal SQL/crypto errors or credentials to UI/logs. Use AuthException codes and DuplicateAccountException. Passwords are transient char arrays, never ViewModel/SavedStateHandle/navigation arguments, preferences or saved view hierarchy. Clear owned copies and Fragment password fields.
- Sessions store only the active UUID in application-private local_session preferences. Use checked durable writes on the auth worker. Validate restored IDs against users; clear missing/malformed identities. Failed storage must keep authenticated content hidden. Preserve backup exclusions for both database and session preferences.
- The Activity owns session-driven navigation. Keep its startup gate, clear tab/back-stack state when identity changes or logout succeeds, and never expose a restored authenticated destination before session validation. Preserve same-account Activity recreation and the three home tabs.
- Use lifecycle-aware AuthViewModel LiveData and viewLifecycleOwner observers. Fragments may only bind state and forward actions. No direct repository/persistence/crypto calls from authentication/profile Fragments. Derive the profile user ID inside AuthService.
- Prevent duplicate submissions; logout invalidates in-flight authentication work. Preserve safe callback threading and subscription cancellation.
- Optional UPI IDs are unverified profile metadata. Milestone 6 permits explicit UPI handoff only; never claim verification or payment confirmation. Groups, expenses/balances, settlements/UPI handoff and the recurring tracker are implemented in Milestones 4–7. Preserve all of them.
- Run both service/session tests and real UI navigation tests when changing authentication. Test duplicate accounts, invalid credentials, recreation, logout/back boundaries, account switching and restored missing identities. Record actual API/device and lint coverage.

- Final Milestone 3 evidence is in `docs/milestone-3-verification.md`. Keep completed automated checks distinct from manual device coverage; the full docked-keyboard, API 24 and physical-device checks remain outstanding. Do not treat a resumed verification request as authorization to start a new milestone.
## Codex development efficiency rules

- Read AGENTS.md and relevant existing files first.
- Inspect only files relevant to the current task.
- Reuse existing code; avoid unnecessary rewrites.
- Make sensible reversible decisions independently.
- Do not browse the web unless necessary.
- Run focused tests during development.
- Run full verification once at milestone completion.
- Do not repeatedly rerun passing tests without reason.
- Keep final reports concise.
- Never skip essential security, money, or data-integrity tests.
- Stop after completing the requested milestone.

## Milestone 4 group rules

- Reuse GroupsViewModel, GroupRepository, MemberIdentity and GroupDetails. Actor IDs originate only from AuthService; fragments supply actions/selected targets, never the acting identity.
- Use the atomic createGroup(actor, group, memberIds, ...) operation for UI creation; never assemble group creation from separate membership writes. The creator is automatically the initial admin. Reject duplicate selections; keep database uniqueness/FKs authoritative.
- Only the current admin adds/rejoins members or transfers admin to another current member. Check permissions inside the repository write transaction. Leaving is a status change, requires a zero derived balance, and cannot be performed by the current admin.
- Use authorized observeGroupSummaries/observeGroupDetails for screens. Detail observations recheck membership on every emission. Username discovery is exact/normalized and returns only public UUID/username; never show account emails. Only the authorized settlement snapshot may expose suggested recipients’ UPI IDs for payment.
- Keep group drafts in SavedStateHandle, close subscriptions on stop, and discard stale results/account state. Preserve the Activity’s authenticated-destination guard; bottom tabs appear only at the three root destinations.
- Show real creation date (no last-activity column exists). Do not fabricate expense totals/balances. Milestone 5 uses actual authorized accounting snapshots. No schema change was needed; version remains 3.
- Keep confirmation dialogs for immediate member additions, admin transfers and leaving. Group verification evidence is recorded in docs/milestone-4-verification.md.


## Milestone 5 expense rules

- Reuse ExpenseViewModel, ExpenseRepository mutations, authorized GroupDetails streams, ExpenseAmounts, Rules.equalSplit and Balances. No separate accounting database or persisted balances.
- Parse plain positive INR decimals to exact long paise. Reject more than two decimal places, overflow, empty participants and invalid inputs; never silently round. Check aggregate expense totals as well as net balances before committing saves.
- New payers and participants must be current members. Editing may retain the original departed payer/participants; preserve names and historical references and label former members. Repository permissions and atomic split replacement are authoritative.
- Categories for new entries are Food, Travel, Rent, Bills and Other. Old category text can be retained. Expense dates are displayed/grouped by UTC calendar date consistently; do not reinterpret them as modification timestamps.
- Balance displays include recorded settlements and referenced historical identities, but never recurring contributions. Show net owed/owing/settled amounts without inventing debtor-to-creditor pairs. Total expenses means spending, not outstanding debt.
- Keep expense drafts session-bound and lifecycle-aware. Close observations on stop, suppress stale callbacks on account change and require confirmation for deletion. The expense destination is authenticated and has no bottom tabs.
- No settlement entry, payment/UPI execution, recommendations, OCR, recurring UI or Firebase was authorized in Milestone 5. Verification evidence belongs in docs/milestone-5-verification.md.

## Milestone 6 settlement rules

- Reuse SettlementCalculator, SettlementRepository and session-bound SettlementsViewModel. Greedy largest-balance matching uses UUID tie breaks and checked integer paise; suggestions are not completed payments or a proof of minimum transfer count. Recurring data never participates.
- New records require a current member who is payer or payee and cannot exceed the current suggested pair amount. Historical parties remain valid. Any current member may correct amount/date; group and parties are immutable. Exclude the old record to calculate pair debt/credit capacity; edit ceiling is max(old amount, capacity), allowing retention/reduction after underlying expenses change.
- Duplicate heuristic: same group, payer, payee, amount and UTC calendar date, excluding the edited record. Require explicit acknowledgement; recheck permissions and amount limits transactionally even after acknowledgement.
- UPI handoff uses the selected current suggestion amount, encoded pa/pn/am/cu/tn fields and a system chooser. Missing apps must be safe. Never infer success from returning/cancelling or automatically record a payment. Manual recording always remains separate, with confirmation.
- No schema change or new dependencies for M6. Preserve records, lifecycle cancellation and authenticated navigation. Evidence belongs in docs/milestone-6-verification.md. Stop after M6; recurring UI, OCR and Firebase remain outside scope.

## Milestone 7 recurring tracker rules

- Reuse RecurringBillRepository, immutable recurring snapshots, RecurringViewModel and application-owned RecurringReminders. All screens obtain actors from AuthService; repository transactions check current membership. Recurring contributions never create expenses/settlements or enter balances.
- Use UTC billing months/dates and Rules.equalSplit. Clamp due days to month end. Existing cycle amount, handler, participants/shares and due date are immutable. Current members may toggle any historical contribution; paid means contributed to the cycle handler.
- Generation uses the persisted next-month cursor, transactional `(bill_id,year,month)` duplicate protection and the current template for missing months. Create through the current month, plus next month when its reminder window opens. Fill old missing months before template edits. Migration starts at the first known cycle, or the upgrade month when there is no history. Never invent pre-creation history.
- Inactive bills do not generate; reactivation restarts in the current month, excluding inactive gaps. Invalid templates with departed members pause generation until corrected; history remains visible and deactivation remains possible.
- WorkManager runs deferrable periodic checks and immediate resume/update checks. No exact-minute claims. Check runtime permission, global notifications and channel settings before claiming. Durable cycle/recipient receipts prevent duplicates, including account switches. Claims happen before posting: a process/permission race may miss a reminder rather than duplicate it.
- Local notifications only target the restored signed-in unpaid participant. Never fan out other accounts’ private notifications. Clear notifications/work on identity changes; prune notifications after membership/status/deactivation updates. PendingIntents include recipient and bill; validate session and membership before showing details. Never use a notification as authorization.
- Keep scheduling and SQL out of Fragments. M7 schema changes are additive only. Keep manual verification limits explicit in docs/milestone-7-verification.md. Stop after M7; OCR and Firebase remain future scope.

## Milestone 8 receipt rules

- Reuse ReceiptStorageRepository, ReceiptChange and ExpenseRepository’s atomic expense/receipt save overload. Receipt metadata has logical UUID references only. UI/domain/repository contracts must never contain absolute paths, Android file handles or Firebase SDK types. Camera content URIs are transient Android presentation/platform values, not persistent attachment references.
- Store immutable JPEG objects in app-private no-backup storage, never SQLite BLOBs. Stage the complete fsynced file before metadata; publish/replace/remove its expense link in the financial transaction. Rollback must retain the old attachment and keep the new draft available to retry. Delete obsolete files only after commit; deferred cleanup failures must not turn a committed expense into a reported failure.
- Check current membership for every receipt operation. Unpublished drafts are private to their creator; bound historical-expense receipts are readable by any current group member. Raw legacy observation contracts remain internal.
- Normalize all eight EXIF orientations, strip metadata by re-encoding, preserve aspect ratio and readable width for long receipts. Reject extreme captures rather than silently store illegible thumbnails. Originals live only in private capture cache and are deleted after processing/cancellation; abandoned captures/drafts expire after 24 hours on startup. Never delete a live linked object during cleanup.
- Bundled ML Kit Latin recognition is on-device. OCR uses overlapping readable vertical sections for long receipts; parser suggestions require an unambiguous payable label, checked exact paise and explicit user review. Never auto-save or silently overwrite an amount. Never log receipt images, recognized text or credentials.
- Keep capture/OCR/file IO off the UI thread in platform/data services. Close Fragment bindings, keep drafts session-bound, suppress late/cancelled work and preserve camera activity-result recreation. Keep manual entry available on permission, camera, recognition or storage failures.
- Firebase Storage is future scope: maintain the same logical-ID/byte-payload contract, stage uploads before a member-checked Firestore link transaction, and use compensating deletion plus orphan cleanup. Storage and Firestore must both enforce current membership; no client-only authorization. Fresh Firebase accounts/data only; no local receipt/account migration or uploads are implemented.
- Record M8 evidence and remaining device coverage in docs/milestone-8-verification.md. Stop after M8.
