# SplitEase architecture

- Application ID / namespace: `com.example.splitease`. Minimum SDK 24. Use stable SDKs supported by the pinned Android Gradle plugin.
- Write application code in Java, XML layouts, Android Views and Material Components. Enable ViewBinding; do not add Kotlin application sources or Compose.
- Keep one MainActivity with AndroidX Navigation and Fragments. Navigation graph destination IDs must match bottom navigation menu IDs. Release Fragment bindings in `onDestroyView`; collect updates only during the appropriate view lifecycle.
- Keep Activities and Fragments concerned with presentation and navigation. Never put SQL or Firebase queries in UI classes.
- `model` contains plain Java entities; all local entity IDs are canonical `UUID.randomUUID().toString()` values, never numeric database IDs. Firebase authentication identities use opaque SDK UIDs unchanged, scoped by SessionState.Backend; never insert them into SQLite or apply local UUID validation to cloud UIDs. Use the immutable Milestone 2 model snapshots; association identities are composite UUID foreign keys.
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
- Bundled ML Kit Latin recognition is on-device. Reuse ReceiptOcr and ReceiptTotalDetector. OCR uses upright source pixels (bounded to 16 MP), independently of attachment compression, with overlapping tiles on both axes and a 45-second recognition deadline. Preserve word geometry, remove overlapping fragments, join close decimal fragments and compensate row skew before associating labels/amounts. Do not add image filters without measured evidence.
- Rank payable labels and corroborating payment/component evidence deterministically. Do not prefer NET AMOUNT above later taxes or mistake zero outstanding for zero spending. Reject IDs/dates/percentages; ambiguous or uncorroborated numeric OCR repairs require manual entry. Arithmetic is checked paise. Keep HIGH/REVIEW/UNCERTAIN reliability and supporting labels; never auto-save or silently overwrite amounts.
- OCR diagnostics are explicit debug-only UI, gated by BuildConfig.DEBUG; no production receipt-text/candidate logging or persistence. Do not commit private receipt photos/transcripts. The opt-in ReceiptRealImageTest loads a manually provisioned app-private cache fixture; synthetic text, generated-image ML Kit tests and camera-contract tests are separate evidence.
- Keep capture/OCR/file IO off the UI thread in platform/data services. Close Fragment bindings, keep drafts session-bound, suppress late/cancelled work and preserve camera activity-result recreation. Keep manual entry available on permission, camera, recognition or storage failures.
- Firebase Storage is future scope: maintain the same logical-ID/byte-payload contract, stage uploads before a member-checked Firestore link transaction, and use compensating deletion plus orphan cleanup. Storage and Firestore must both enforce current membership; no client-only authorization. Fresh Firebase accounts/data only; no local receipt/account migration or uploads are implemented.
- Record M8 evidence and remaining device coverage in docs/milestone-8-verification.md. Stop after M8.
## Firebase Spark plan and zero-cost restrictions

- SplitEase must remain entirely on Firebase's **Spark (no-cost) plan**.
- The project must never require payment, a billing account, or a credit/debit card.
- Never enable Blaze, pay-as-you-go billing, or Google Cloud billing.
- Never automatically upgrade the Firebase project or suggest enabling billing to solve an implementation problem.
- Use Firebase Authentication with email/password and Cloud Firestore only within the Spark plan's no-cost quotas.
- Optimize Firestore queries, listeners, writes, and document structures to minimize quota consumption.
- Do not add Firebase Cloud Storage, Cloud Functions, or any Firebase/Google Cloud feature that requires billing.
- Do not assume a product is available on Spark merely because its pricing page mentions a free allowance.
- Keep receipt attachment storage provider-independent. Preserve working local receipt storage until a compatible no-billing cloud alternative is approved.
- Do not add third-party cloud services without first verifying their no-cost availability and receiving explicit approval.
- If a feature cannot be implemented without billing, report the limitation and propose a zero-cost alternative.
- Prefer temporary feature limitations over introducing any possibility of cloud charges.
- Never change these financial restrictions without explicit user approval.

## Milestone 9A Firebase foundation

- Google Services plugin 4.5.0 and Firebase BoM 35.1.0 pin Auth/Firestore only. Keep Java 17, minSdk 24, XML/ViewBinding and the existing toolchain. Real app/google-services.json is local/ignored, must match com.example.splitease, and must never be replaced with fake config. Never log API keys/options wholesale.
- Default FirebaseApp auto-initializes through FirebaseInitProvider. Keep LocalAuthService and SqliteRepositories active in SplitEaseApplication; no second auth/session store, data reset, cloud adapter selection or local-to-cloud migration is authorized.
- FirebaseDiagnostics is build-variant scoped: debug Profile UI only; release hook is empty. Its local SDK getters and one manual fixed-host HTTPS HEAD are not cloud authorization tests. Never add Firestore document reads/writes, Firebase sign-in/account creation, service enablement or rules deployment to a diagnostic. Do not transmit local data/credentials/API keys in probes.
- JSON/SDK initialization and network reachability cannot verify Spark status, enabled services or rules. Report those separately; require owner console verification of Spark before later cloud feature activation. Never enable billing or assume a paid-plan free tier satisfies the Spark-only restrictions above.
- Future Firebase adapters reuse AuthService and asynchronous repositories at the composition root; SDK types stay out of UI/domain/contracts. Fresh cloud accounts/data only. ReceiptStorageRepository stays unchanged/local; the historical M8 Storage design does not authorize Cloud Storage under current cost restrictions.
- Record evidence in docs/milestone-9a-verification.md. Stop after 9A; no Firebase auth replacement or Firestore repositories yet.


## Milestone 9B Firebase authentication adapter

- Keep LocalAuthService/SqliteRepositories as the application default. FirebaseAuthService is an isolated adapter, not authorization to route cloud users to local data. MainActivity accepts only LOCAL sessions for normal destinations. Preserve the explicit SessionState.Backend boundary and never equate Firebase UIDs with local UUIDs.
- Reuse FirebaseAuthService, FirebaseAuthBackend and FirebaseSdkAuthBackend. Keep SDK types in the data/composition boundary. Async callbacks use supplied serial executors; subscriptions cancel queued notifications. Drain uncancellable SDK work after logout/close and suppress stale authentication successes. Validate cached users via reload/forced SDK refresh; keep content hidden on unavailable/invalid restoration.
- Firebase owns credentials and durable auth persistence. Never copy Firebase passwords, tokens or hashes into SQLite, custom preferences, logs or application-managed session storage. SDK-required password Strings are transient; clear owned char arrays. Preserve backup/transfer exclusions for all shared preferences and the database.
- Preserve AccountValidation signup policy and normalized emails. Use sanitized AuthException codes, generic invalid-credential/reset responses and no custom attempt limit. Password reset remains Firebase-only; local auth does not pretend to send recovery mail.
- The explicit debug sandbox is a separately named FirebaseApp with demo-splitease-auth + Auth Emulator only, loopback port 9099 via ADB reverse. No live fallback, default-app sign-in, Firestore access or release selector. Install its dynamic navigation destination before restoring a graph. Use test credentials only and opt-in emulator tests; never add real credentials/config exports to Git.
- A cloud auth snapshot has its exact UID/email and no reserved username/profile/UPI yet. Do not claim the signup username is unique or persisted. 9C must gate application readiness on idempotent username-reservation/profile creation in one Firestore transaction after Auth creation. Handle cross-service partial failures, ambiguous commits, retry/reconciliation and carefully validated compensation; never delete an Auth account blindly on a network failure. No Firestore writes/rules deployment, cloud migration or account enablement in 9B.
- Keep Spark-only restrictions. Emulator verification does not establish production plan/provider configuration, live email delivery or Firestore authorization. Record mocked, actual SDK/emulator and live-backend evidence separately in docs/milestone-9b-verification.md. Stop after 9B.


## Milestone 9C Firestore profiles and registry

- 9C supersedes 9B's auth-only sandbox restriction: only the explicitly selected demo sandbox uses Auth + Firestore Emulators. Keep LocalAuthService/SqliteRepositories and normal navigation active by default; no release selector, local migration, group repository or production cloud activation. Never mix Firebase UIDs with local UUIDs.
- Reuse CloudProfileRepository/CloudProfile, FirestoreProfileRepository and ProfiledFirebaseAuthBackend around the existing FirebaseSdkAuthBackend. UserRepository's local credential operations must not be emulated in Firestore. Keep SDK types in data/composition; callbacks use supplied serial executors, cancel stale-account delivery and close observations with lifecycle. Server reads/confirmed snapshots establish profile readiness, never cache/pending writes.
- profiles/{opaque Firebase UID} is private to its owner. usernames/{normalized username} contains only UID/displayUsername for authenticated exact lookup. Never query/list directory collections or expose another user's email/UPI. Derive profile actor from the scoped SDK identity, never arbitrary UI input.
- CloudProfileValidation bounds cloud names to trimmed 3–30 ASCII letters/digits/underscores; Locale.ROOT lowercase is the deterministic registry key. This is cloud-only. UID, canonical name, email and creation timestamp are immutable; optional unverified UPI and display-case-only changes are permitted. Update registry display together with profile display. No rename/email-change/delete workflow in 9C.
- Create profile + registry in one Firestore transaction with getAfter-enforced rules pairing. Existing complete profile makes retries idempotent. Resolve verified concurrent collisions using the exact public key; never turn every permission error into a duplicate. No partial reservation/profile writes, blind overwrite, username hijacking or client-only uniqueness.
- FirebaseAuthService PROFILE_REQUIRED is a valid Auth identity without application readiness. Gate SIGNED_IN on the server profile. Keep partial/lost-ack outcomes recoverable through login/restore/explicit completeProfile; do not delete Auth accounts on errors, store passwords/tokens/pending usernames in app preferences, or create a second session store. Logout/close must suppress late profile completion just as late SDK sign-in.
- firestore/firestore.rules and firebase.emulator.json are candidate rules/emulator config only. Keep production deny-all rules unchanged until separately reviewed/approved. Rules enforce owner get/update, allowed shapes/fields, Auth email, timestamps, immutable identities and atomic reservations; default deny all unimplemented collections. No billing, Storage, Functions, production test accounts or load tests.
- Use opt-in FirestoreProfilesEmulatorTest (profilesEmulator=true), pending UI test and separate-process restoration fixture. Test actual raw SDK denials, concurrent claims, minimal lookup, updates, switching and recovery. Mocked failure-injection tests are distinct from actual SDK/emulator evidence. Record exact results/limits in docs/milestone-9c-verification.md. Stop after 9C.


## Milestone 9D Firestore groups and membership

- Keep the normal application local. Cloud groups are available only through the explicit named demo Firebase sandbox and nested debug graph. Reuse GroupsViewModel/Groups/CreateGroup/GroupDetail via GroupDependencies, never a global auth/data mode toggle. Release CloudGroups hook returns no dependencies. The cloud graph must never instantiate SQLite expense/settlement/recurring screens or expose local data; identity changes clear its navigation state and gate content until profile readiness.
- FirestoreGroupRepository implements GroupRepository with AuthService-sourced actors plus scoped SDK identity checks. Never trust the interface actor argument alone. Group IDs are UUIDs; Firebase user UIDs are opaque. Keep SDK/document code in data/composition, not Fragments/domain. Close streams on stop/cancellation/account change and suppress queued stale results.
- One groups/{UUID} document holds current memberIds and retained membership snapshots, immutable creatorId/createdAt, transferable adminId, server updatedAt and checked revision. Create group/members atomically; only admin adds/rejoins or transfers to a current member. Never delete membership history. Rejoin preserves original joinedAt/latest leftAt. Client joined/created timestamps are not authoritative.
- Eight initial members (including creator) is the verified Rules-budget bound; later single-member adds can exceed eight. Keep the Java limit/UI guidance/Rules boundary test aligned. Retain history on document-size errors; the 1 MiB technical limit is not permission to prune. Rules use exact initial-row maps to stay below expression limits.
- Retry a Rules-rejected stale revision only after a fresh server read proves a newer revision and continued admin/current membership, at most two extra transactions. Never retry actual authorization loss. Check duplicate IDs; no lost updates or privilege self-assignment. Group deletion remains unsupported.
- Cloud financial eligibility is unavailable. LeaveGroup and observeBalances return UNSUPPORTED; Rules prohibit leave/remove/delete, and UI explains the deferral. GroupDetails.accountingAvailable=false means totals/balances are unknown, not zero. Hide financial summaries/Add Expense/Settle Up and show explicit placeholders. Never silently return a successful empty/zero balance. Preserve all local accounting and eligible local leaving.
- publicIdentities/{uid} is the minimal UID/name directory required by existing UID-based group APIs. Profile creation/case updates maintain it with profile/username documents in one transaction; owner idempotent creation can repair legacy demo mirrors. Authenticated exact get only; no email/UPI/timestamps or collection listing. Private profiles stay owner-only. Group member names are public-name snapshots at join/rejoin, not profile listeners per member.
- Home uses one array-contains memberIds query; detail one document listener. Confirm server snapshots only, ignoring pending/cache-only state. Initial server timeout is ten seconds; errors terminate the subscription and UI Refresh reconnects. Do not claim offline editing or automatic listener recovery after a terminal error. No composite index is needed; retain memberIds array indexing and the members-map index exemption candidate.
- Candidate rules retain profile uniqueness/privacy and deny unimplemented financial/recurring collections and membership subcollections. No live rules/index deployment without explicit approval; keep Spark/no-billing restrictions. Document client/Rules read patterns, technical bounds and emulator-vs-device/live limitations in docs/milestone-9d-verification.md. Stop after 9D; no cloud accounting/settlements/recurring implementation.
