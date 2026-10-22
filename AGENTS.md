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

- SQLite schema version 3: v1 core accounting, v2 isolated recurring tables, v3 original creator attribution. Never delete user data on upgrade or downgrade. Preserve foreign keys, parameter binding and atomic writes with permission checks inside transactions.
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
- Optional UPI IDs are profile metadata only: never claim verification or initiate payment. Recurring remains a placeholder; Groups is implemented in Milestone 4. Expenses/Balances UI is implemented in Milestone 5; settlement/payment flows require their own milestone.
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
- Use authorized observeGroupSummaries/observeGroupDetails for screens. Detail observations recheck membership on every emission. Username discovery is exact/normalized and returns only public UUID/username; never show account email or UPI to other members.
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
