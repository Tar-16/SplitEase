# SplitEase

Java Android expense-sharing app for Indian users. Milestones 1–8 provide local groups, expenses, settlements, recurring bills and receipt OCR/attachments. Milestones 9A–9E.1 add Firebase configuration, isolated authentication/profiles, Firestore groups and expenses in an explicit emulator test mode while SQLite and local authentication remain active. Local authentication and profiles are preserved. Recurring now tracks independent monthly contributions. The existing SQLite foundation, single-Activity navigation and light/dark identity are preserved. Firebase uploads are not included. UPI handoff does not verify payments.

## Setup and toolchain

Open the directory in Android Studio supporting AGP 9.2 (Panda 4 or newer). Install SDK Platform 37 and Build Tools 36.0.0. Configure your SDK in untracked `local.properties` (`sdk.dir=/absolute/path/to/Android/sdk`) or `ANDROID_HOME`.

- Application ID / namespace: `com.example.splitease`.
- Java 17 source/target; minimum SDK 24; compile/target SDK 37.
- AGP 9.2.0; Gradle wrapper 9.4.1 with pinned distribution checksum.
- The existing daemon JVM configuration selects JDK 25; verification used JDK 25. Java application code remains Java 17.
- Existing dependencies: AppCompat 1.7.1, Core 1.17.0, Activity 1.12.4, Fragment 1.8.9, Navigation Fragment/UI 2.9.8, Material Components 1.13.0.
- Added: Bouncy Castle `bcprov-jdk18on:1.83` for portable Argon2id across API 24+, AndroidX Test Runner 1.6.2 and AndroidX Test JUnit 1.2.1. JUnit 4.13.2 remains for local tests.
- Lifecycle ViewModel/LiveData 2.11.0; WorkManager 2.12.0 for local reminders; application version name 0.8.0.
- Bundled ML Kit text-recognition 16.0.1 and ExifInterface 1.4.2 for receipt processing. Milestone 9A adds Firebase Authentication and Firestore via BoM 35.1.0 and Google Services plugin 4.5.0. Cloud Storage, Functions and Analytics SDKs are not included.
- XML Android Views and ViewBinding; no Kotlin application sources or Compose. Dependencies may internally use Kotlin.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest   # running API 24+ device/emulator required
./gradlew installDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Initial dependency resolution requires Google Maven, Maven Central and Gradle distribution access.

## Structure and integration

```text
app/src/main/java/com/example/splitease/
  SplitEaseApplication.java   Composition root; opens database asynchronously
  ui/                         Activity, auth, Profile, Groups and Expenses screens/ViewModels, Recurring tracker
  auth/                       AuthService, LocalAuthService, FirebaseAuthService and local SessionStore
  model/                      Immutable User, Group, GroupMember, Expense,
                              ExpenseSplit, Settlement, RecurringBill,
                              RecurringBillCycle, RecurringBillContribution
  domain/                     Rules, Balances, SettlementCalculator, RecurringSchedule
  repository/                 Five feature interfaces, callbacks, observable queries
  data/                       SplitEaseDatabase, SqliteRepositories, PasswordHasher
  reminders/                  WorkManager checks and session-scoped notifications
  platform/receipts/           Private capture, EXIF/compression and bundled OCR
  util/                       Exact INR display formatting
app/src/test/                 Android-independent monetary and scheduling tests
app/src/androidTest/          Real SQLite and navigation/recreation tests
app/src/main/res/xml/         Database backup/transfer exclusions
```

`SplitEaseApplication` owns one app-scoped `SqliteRepositories` instance and exposes only feature interfaces through `users()`, `groups()`, `expenses()`, `settlements()` and `recurringBills()`. Database initialization errors are logged and accessible through `initializationError()`; each repository call also reports its own failure. Profile consumes the authenticated account through AuthService and an Activity-scoped AuthViewModel; Groups uses a Fragment-scoped session-bound GroupsViewModel; Recurring now tracks independent monthly contributions.

Repository operations return promptly and execute on a single database worker. Validation, membership checks and multi-table writes run in one transaction. Failed operations roll back and report `onError`; successful operations deliver exactly one non-null result. Missing records report an error rather than null. Caller-supplied callback executors determine delivery threads and must be serial, remain accepting, and run callbacks that do not throw. Use a main-thread executor for UI callbacks, with lifecycle checks. The original callback/observable contracts are retained.

Observers deliver an initial snapshot and refresh after successful writes through the same repository owner. Lists are immutable. Closing a subscription suppresses queued notifications; an already-running callback can finish. UI owners must close subscriptions with their view lifecycle. Notifications are currently coarse-grained across queries; external SQL writes and other repository owners do not trigger them. Production must use the single application owner. `close()` prevents new operations, cancels observations and queues database closure behind accepted work.

Feature interfaces expose no Android, SQLite or Firebase types, allowing future Firestore adapters. The shared SQLite owner implements the five contracts to keep transaction and threading behavior consistent. Raw SQL belongs only in `data` (and SQLite tests).

## Groups and members (Milestone 4)

- Home observes only the authenticated account’s current groups, with Flat/Trip type, current member count/initials and actual creation date. Actual total expenses, your net balance and latest expense date are shown. There is no modification/activity-log column.
- Create a group with a name, type and optional existing users found by exact, case-insensitive username. Remove selections before saving. Creator membership and all selected memberships are committed atomically, with the creator as initial admin.
- Current members can view details. Only the current admin can add members or transfer ownership to another current member. Additions take effect immediately, without invitations, after confirmation. Transfer and departure also require confirmation.
- Leaving requires exactly zero derived balance and prior ownership transfer if you are admin. Departures retain the membership row and historical records; rejoining preserves original join time. A sole admin cannot leave until another member is added and made admin.
- `MemberIdentity` exposes only UUID and username. `GroupDetails` is an immutable authorized snapshot; detail observations recheck current membership on every refresh. Search does not return emails, UPI IDs or credentials. Existing lower-level accounting APIs remain trusted internal contracts, not a public API/security boundary.
- `GroupsViewModel` obtains the actor from AuthService, cancels observations when stopped, ignores stale callbacks after identity changes, and saves non-sensitive creation drafts in SavedStateHandle. The Activity guards creation/detail destinations and shows bottom tabs only at Groups/Recurring/Profile roots. Logout/account changes discard the prior navigation state.
- No schema migration, new dependency, authentication change or recurring-model change was required. Lists are currently unpaginated for local use. Expenses/Balances are implemented in Milestone 5; no payments or settlement-entry UI were added.

## Expenses and balances (Milestone 5)

Group Detail now has Members, Expenses and Balances tabs. Expenses are grouped by their UTC calendar date (newest first), with category glyph/label, payer, total, participant count, your share and your signed net impact. A full-width teal “+ Add Expense” action sits below the tabs only in Expenses and remains above the scrolling list; it opens the existing editor for that group. The group summary can collapse while tabs/action stay pinned. Viewing an expense offers edit and confirmed deletion. Any current group member can edit/delete.

The editor accepts a title, positive INR amount (plain digits with up to two decimal places), payer, one or more checkbox participants, Food/Travel/Rent/Bills/Other category, and a calendar date. Exact shares preview immediately using the existing sorted-UUID remainder rule. No implicit rounding, percentage or custom splits. New payers/participants must be current members; an old expense can retain its original departed payer/participants, visibly labeled former members. Existing legacy category text can be retained on edit, but new entries use the five categories.

`ExpenseViewModel` derives its actor from AuthService and keeps a SavedStateHandle draft across recreation. It uses the existing GroupRepository authorized snapshot stream and ExpenseRepository mutations. Fragments handle views/navigation only. SQLite atomically saves expense/split replacements, checks membership inside transactions and rolls back invalid/overflowing changes. `ExpenseAmounts` parses with BigDecimal and checked long conversion; summaries and per-expense impact use checked integer paise. The total-expense aggregate is checked before committing a save, even when net balances cancel to zero.

`GroupDetails` includes immutable expense, balance and public-name snapshots in the same worker read. It retains current members and historical identities referenced by expenses or recorded settlements. Balances derive from the existing `Balances` domain logic; positive is owed to the member, negative is owed by the member, zero is settled. These aggregate nets do not imply specific debtor/creditor payment pairs. They are never persisted and recurring contributions never enter them. Home and Detail refresh after mutations and account switches. The summary total means all expense amounts, not outstanding debt; latest expense date is an actual expense date, not an invented modification timestamp.

Milestone 5 required no schema or dependency changes. Milestones 6–7 extend that release with settlements/UPI handoff and recurring tracking, described below. Milestone 8 adds local receipt OCR below; Milestone 9A adds SDK foundation only, not cloud authentication or data adapters.

## Schema and migrations

Database: app-private `splitease.db`, version **5**. Foreign keys are enabled on every connection. All standalone entity identifiers are canonical String UUIDs validated at repository boundaries. Association rows use composite keys of the referenced UUIDs rather than redundant identifiers. Financial columns require SQLite integer storage; Java uses `long` paise.

| Table | Purpose and constraints |
| --- | --- |
| `users` | Unique normalized username/email; optional UPI ID; salt, hash, algorithm and cost parameters |
| `groups` | Name, FLAT/TRIP type, current admin, original creator and creation timestamp |
| `group_members` | `(group_id,user_id)` identity, ACTIVE/LEFT, original join and latest departure dates; never removed on leaving |
| `expenses` | Group, title, positive amount, payer, immutable creator, category and expense date |
| `expense_splits` | `(expense_id,user_id)` and explicit nonnegative share; group-consistent foreign keys |
| `settlements` | Separate accounting records; distinct payer/payee, amount and date |
| `recurring_bills` | Group, amount, monthly due day, optional handler, reminder lead days and active state |
| `recurring_participants` | Normalized bill/member association |
| `recurring_cycles` | Historical amount, due date, handler; unique `(bill_id,year,month)` |
| `recurring_contributions` | Historical participant shares and paid flag, keyed by `(cycle_id,user_id)` |
| `recurring_schedule` | One persisted next-month cursor per bill |
| `recurring_reminders` | Durable unique cycle/recipient notification receipt |
| `receipts` | Logical UUID, group/optional expense link, draft owner, dimensions/size/time; no image BLOB |

Group/date indexes support expense and settlement lists; membership, active-bill and contribution indexes support scoped queries. Composite foreign keys prevent splits/contributions from referencing a different group's members.

Migration chain: v1 creates core accounting tables; v2 adds recurring tables; v3 adds immutable creator attribution; v4 adds recurring cursors and reminder receipts; v5 adds receipt metadata. New databases apply the same chain. Upgrades are additive and transactional under SQLiteOpenHelper; unsupported upgrades/downgrades throw instead of deleting data. Because v1/v2 did not record original creator identity, the v3 migration backfills their current admin as the best available attribution. New groups retain their actual creator through admin transfers. Milestone 1 shipped no database schema.

Deletion semantics are explicit: referenced users cannot be deleted. Leaving changes membership status and preserves financial references. Bills with historical cycles cannot be deleted; deactivate them instead. Group deletion also fails if recurring history would be orphaned. Groups without recurring history can be deleted with their owned records in a transaction. No user-facing deletion workflow is introduced.

## Monetary and membership rules

- All money is integer paise. No float/double monetary operations. Display conversion uses BigDecimal.
- Equal splits sort participant UUIDs lexicographically, divide by participant count and assign each leftover paise to the first IDs. Input order does not change results. Zero shares are allowed when there are fewer paise than participants. Totals must be positive.
- Expense writes require explicit shares matching that exact equal split and summing to the total. Replacing an expense and its splits is atomic. Other split methods are not enabled yet.
- Balances are derived from expenses and settlements only: positive means owed to the member. Payers receive expense credit, participants owe shares; settlement payers reduce their debt and payees reduce their credit. Checked arithmetic fails on overflow rather than persisting unusable records. Balances are not persisted; deterministic greedy settlement suggestions are derived separately.
- Current group members may modify group records and any expense. New expense participants/payers must be current; edits can retain historical participants/payers already attached to that expense. Expense group/creator and group creation metadata are immutable.
- Current members may edit settlement amount/date; parties/group are immutable. Edits overwrite prior values. Positive partial settlements are accepted; duplicate-looking records require an explicit warning acknowledgement.
- A member can leave only at exactly zero derived balance. The admin must first transfer ownership to another current member. Only the current admin performs that transfer or adds members. Former members cannot mutate group records.
- Membership retains the original join timestamp; leaving records the latest departure. Rejoining reactivates the same row and clears its current departure timestamp. This is not an exhaustive membership-event audit trail.
- The current AuthService owns the active identity. Profile updates derive their user ID from that session, never from a view-provided account ID. Future group/expense presentation must likewise use the authenticated identity; raw membership repository methods are not themselves authentication boundaries.

## Recurring bills are separate

Recurring operations never create expenses or settlements and never affect derived balances. Any current group member can mark or unmark any contribution, including a historical participant's contribution. Paid means that participant contributed to the recorded handler; it does not record a normal settlement.

Every cycle snapshots amount, handler, participants and equal shares. Later bill edits do not rewrite cycles. Each new cycle starts entirely unpaid; duplicate months fail the unique constraint. Cycles and shares cannot be edited/deleted through the public interface, except for the confirmed paid/unpaid flag.

Bill templates require active participants/handler when active. If someone leaves, the template must be updated before creating another cycle; historical cycles remain intact. Deactivating a template is still possible after departures. An optional handler may be null; no handler-selection workflow is inferred.

Dates use UTC epoch milliseconds. Monthly scheduling uses year/month (1–12) and due day (1–31); days beyond month end clamp to the last day. The resulting due date is a UTC calendar-date representation, not a notification instant. Bill lead time and participant IDs support M7’s UTC reminder windows and WorkManager scheduling.

## Local credentials

User snapshots omit password material. Credentials are stored separately in columns within the user row: Argon2id v1.3, random 16-byte salt, 32-byte hash, 19 MiB memory, two iterations and parallelism one. Bouncy Castle's lightweight implementation avoids depending on newer Android crypto providers. Comparisons use `MessageDigest.isEqual`. Repository-owned password copies are cleared after work or error delivery; callers own and should clear their original `char[]` arrays. No plaintext password is stored or logged.

These parameters follow the [OWASP password-storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html); implementation is supplied by [Bouncy Castle](https://www.bouncycastle.org/download/bouncy-castle-java/). Stored algorithm/cost metadata permits future upgrades, but automatic rehash, password-change flows and recovery remain outside this milestone. Local sessions are implemented; there is intentionally no fixed attempt limit or automatic lockout. SQLite itself is not encrypted. Android app-private storage is used; backup is disabled and Android 12+ database and local-session preference cloud backup/device transfer are explicitly excluded. `fullBackupContent=false` also explicitly disables the pre-Android-12 full backup path.

## Local authentication and sessions (Milestone 3)

Signup validates username presence, normalized email format and matching passwords. A live checklist displays all five requirements: minimum eight characters, uppercase, lowercase, number and non-whitespace special character. Password fields have visibility toggles; validation failures are visible; submissions show loading and cannot be duplicated. Login checks the real Argon2id credential through UserRepository with a generic incorrect-email-or-password error. No reset link, hardcoded credential, attempt counter or lockout exists.

The existing authentication schema remains unchanged; overall database version is now **5** with additive recurring and receipt migrations. UserRepository gained normalized `findUserByEmail`; duplicate-account failures use a storage-independent typed error. SQLite UNIQUE constraints remain authoritative. Registration validation is in the auth boundary; existing Milestone 2 accounts can still log in even if their older password would not meet the new signup policy. Username/email normalization remains trim + Locale.ROOT lowercase for both create and lookup.

`AuthService` is the replaceable authentication/session interface. `LocalAuthService` uses the existing asynchronous UserRepository, coordinates on its own serial worker, maps internal errors to safe codes and publishes session snapshots through the existing ObservableQuery pattern. `AuthViewModel` observes on the main thread and exposes lifecycle-aware LiveData. Fragments bind views and forward actions; they do not query SQL, hash passwords or access preferences. AuthService derives the profile owner from its own authenticated snapshot. Logout invalidates in-flight authentication callbacks so a late registration/login cannot restore a logged-out session.

`PreferencesSessionStore` stores only `user_id`, a UUID, in application-private `local_session.xml`. No password or hash is stored there. Reads and checked, synchronous `commit()` writes run on the auth worker; success is reported after the write completes. On process startup, the saved UUID is validated against an actual user record. Missing/deleted/malformed identities are cleared. Storage/database failures leave a gated retry screen rather than exposing authenticated content. The identifier is intentionally not encrypted: it is not a password, and the protection boundary is Android app-private storage. Devices with root/debug access are outside this local-account security boundary.

MainActivity restores Navigation behind a loading gate and shows it only after session resolution. It rebuilds and clears saved tab stacks on login/logout/account changes, preserving the current tab during same-account Activity recreation. Bottom navigation exists only on Groups, Recurring and Profile. Attempts to navigate into an authenticated destination while signed out are redirected. Back from signup returns to login; Back after logout cannot reach prior accounts. Root Back may background the task on modern Android.

Password arrays owned by the service/repository are cleared. Password text is never retained by the ViewModel or saved view hierarchy; rotation clears password fields, so an unsubmitted form requires re-entry. In-flight submission state survives recreation. Profile shows username/email and an optional editable UPI ID; saving blank removes it. The ID is explicitly unverified; payment handoff is available only through Settle Up. This release does not claim that a stored email address is verified.

Local accounts/data are device-specific. Firebase will start fresh; no local-account migration to Firebase is planned.

## Verification

Milestone 9A: debug/release builds and **32/32 unit tests** passed; **7/7 focused API 37 tests** verified Firebase initialization, a non-writing HTTPS probe and local authentication/navigation. Lint remains **0 errors, 12 existing warnings**. Authenticated Firestore access and console Spark status are unverified. See [Milestone 9A verification](docs/milestone-9a-verification.md).

Milestone 8: **28/28 local tests**, **46 API 37 emulator tests passed, 2 opt-in tests skipped**, build passed; lint **0 errors, 12 pre-existing warnings** and no new receipt warnings. The receipt force-stop/relaunch test passed separately, checking restored identity, metadata and image SHA-256. Real camera permission denial/capture, light/dark, landscape and 1.3× text were checked on the emulator. Evidence and limitations are in [Milestone 8 verification](docs/milestone-8-verification.md). Earlier reports remain historical evidence. API 24 and physical-device coverage remain pending.

## Settlements and UPI (Milestone 6)

Open Group Detail → Balances → Settle Up. Suggested transfers and recorded settlements are separate lists. Select a suggestion to record a full/partial payment, or select a record to correct its amount/date. Recording requires independent confirmation that payment happened. Either current payer or payee can record; any current member can edit. Historical parties and records are preserved.

The pure-Java calculator greedily matches largest remaining debts and credits, breaking ties by UUID. It conserves integer paise and rejects overflow; it does not guarantee the mathematically minimum number of payments. Recurring bills are excluded. New amounts cannot exceed the current suggested pair transfer. Editing excludes the old record and computes the pair’s debt/credit capacity; its ceiling is the greater of that capacity and the old amount, so corrections may retain/reduce an old amount after expenses change. Parties/group cannot change. Duplicate warnings compare group, payer, payee, amount and UTC calendar date (excluding the edited record); users can explicitly proceed.

Recipient UPI metadata is exposed only through the membership-authorized settlement snapshot for suggested recipients. UPI IDs are unverified. The chooser receives an encoded INR intent for the selected suggestion’s full amount; changing the manual record amount does not change that intent. Returning or cancelling never records or confirms payment. Missing UPI apps/IDs leave “Already paid? Record it” available. No bank integration, transaction verification or automatic reconciliation exists.

Milestone 6 added no dependencies or migration. Milestone 7 added WorkManager 2.12.0 and schema 4; Milestone 8 adds receipt processing and schema 5. Milestone 6 verification is documented in [Milestone 6 verification](docs/milestone-6-verification.md). Earlier reports are historical evidence.

## Recurring shared bills (Milestone 7)

The Recurring tab lists bills across current groups with an optional group filter. Create/edit name, exact positive INR amount, current participants, handler, due day 1–31 and reminder lead time 0–31 days (for example 1, 3 or 7). Bills can be deactivated and reactivated. Each bill shows the current cycle’s paid progress and historical/upcoming cycles. Any current member can mark/unmark any contribution after confirmation; paid means contributed to the handler. This never creates expenses/settlements or changes net balances.

Cycles snapshot amount, handler, participants, exact sorted-UUID shares and clamped due date. Generation is transactional/idempotent and runs on load/resume and background checks. The schedule begins in the creation month and fills missed months through the current UTC month. A next-month cycle is generated early when its reminder lead window opens. Existing snapshots never change. Before edits, missing months are generated using the old template; otherwise missing months use the latest available template. There is no historical template-version audit trail.

Deactivated bills preserve history without generating. Reactivation resumes in the current month and skips inactive gaps. If template members/handler have left, generation pauses with an actionable message; edit current identities to resume, or deactivate while retaining the historical template. UTC calendar months/dates are consistent with existing records; day 31 clamps to shorter month end.

Schema 4 adds only recurring_schedule (generation cursor) and recurring_reminders (unique cycle/recipient receipt). Existing bills start from their earliest known cycle, or the upgrade month if no history exists; earlier history is not invented. Existing accounts, groups and accounting records remain intact.

WorkManager 2.12.0 runs an immediate check on session/resume/settings updates plus a unique 12-hour periodic check that survives normal process death/reboot. Android may delay work; there is no exact-minute guarantee. Force-stopped apps cannot deliver background reminders until reopened. Notifications identify bill, group and due date, and open an authenticated, membership-checked bill destination. Permission/global/channel denial prevents claiming reminders; notification settings remain accessible.

Local phase: only the currently signed-in unpaid participant receives a reminder during the lead window through the due day. Other local accounts receive eligible reminders when signed in, so this device cannot notify all accounts simultaneously or reach other devices. Account changes clear notifications and cancel prior work; paid/deactivated/inaccessible bills are pruned. Lock-screen public content is generic. Durable receipts avoid repeat reminders after restart, account switching or paid/unpaid toggles. Receipts are claimed before posting: a crash or permission race can miss a reminder rather than deliver a duplicate. Android notifications are advisory; the app remains the source for contribution status.

## Receipts (Milestone 8)

Add/Edit Expense includes Scan Receipt. Grant camera permission, capture with the installed camera app, then review the private preview, retake, view/zoom, or remove it. A suggested total is shown only when payable labels and their monetary/layout context can be resolved reliably (for example Grand/Final Total, Net/Amount Payable, Total/Bill Amount, Amount Charged/Paid or Total). Pre-tax net amounts are not preferred over supported final totals. Cash/tendered/change, subtotal, tax totals and conflicting totals are excluded. The detected result uses a small label, prominent teal amount and secondary caution text in a rounded card. The check beside the amount explicitly applies it to the expense field and shows “Amount applied”; manual edits clear that confirmation. Manual correction always remains available. Scanning never creates an expense. Save applies financial and attachment changes together.

The bundled Latin ML Kit model runs on-device using bounded upright source pixels separately from attachment JPEG compression, with overlapping tiles on both axes. Word positions, skew, duplicate fragments and split decimal words are handled before deterministic candidate ranking. Exact subtotal/tax/discount and payment evidence can corroborate a candidate; dates/IDs/percentages and unrelated fields are rejected. Ambiguous totals or uncorroborated digit/separator repairs fall back to manual entry. The card shows HIGH/REVIEW reliability in plain language plus its supporting label. Debug APKs offer an explicit OCR details toggle (recognized geometry/text, extracted amounts, scores and rejection reasons); release builds do not collect/display this report. Recognized text is transient and never logged or persisted by production code. OCR is a suggestion, not a guarantee: blur, handwriting, unusual layouts, unsupported scripts and ambiguous totals require manual entry. No gallery/import feature is inferred. Camera permission denial, cancellation and missing camera apps leave manual entry available.

Photos are normalized for all EXIF orientations, resized without changing aspect ratio and re-encoded as JPEG (quality 88, falling back to 82/76 if needed; maximum 4 MiB). Storage normalization targets at most 12 megapixels, 1,600 px short side and 16,000 px long side, retaining smaller images at original resolution. Extremely long images that would reduce an originally readable short side below 640 px are rejected for retake. The zoom/pan viewer preserves the full stored raster; EXIF/GPS metadata and original full-resolution captures are not retained after successful processing.

ReceiptStorageRepository exchanges logical UUID metadata and encoded byte payloads, never filesystem paths. The local adapter uses app-private no-backup files. Drafts are creator-private and require current membership; published historical receipts remain accessible to current group members. Every read/write checks permissions. ExpenseRepository’s ReceiptChange overload atomically saves expenses/splits and attachment links; an ordinary save keeps the current receipt. Replacement failure preserves the old attachment and retryable new draft. Removal/deletion commits metadata first and then cleans obsolete files. Startup/explicit cleanup removes unreferenced objects and drafts older than 24 hours; capture cache has the same expiry. Failed orphan deletion is safely retried on later writes/startup. Missing/corrupt files show a recoverable error rather than a broken image.

### Future receipt adapter contract (not approved for cloud deployment)

Replace the local storage adapter and expense unit of work while keeping logical IDs, metadata, byte payloads and callbacks. A future adapter must stage an immutable object before a Firestore transaction publishes its expense link; a failed transaction retains a retryable draft or compensates by deleting the unpublished object. Never delete the previous object before the new metadata commit. Removed/replaced/deleted objects need idempotent deferred cleanup and an expiry job for abandoned uploads; a database and object store do not share one transaction. Define metadata states/version checks to handle interrupted uploads and concurrent edits.

Both Firestore metadata rules and Cloud Storage object access must verify current group membership from authoritative membership records, with unpublished objects limited to their creator and no public download URLs. Rules must enforce group/expense identity, allowed image type/size and immutable logical references. Client checks are insufficient. This is historical adapter design, not authorization to enable Cloud Storage. Current Spark/no-billing restrictions prohibit adding that SDK/service. Keep local receipt storage until a compatible no-billing alternative is explicitly approved. Future cloud accounts/data will start fresh; local UUIDs, credentials, metadata and images are **not migrated**. M9A adds only Auth/Firestore SDK foundation, with no upload, rules deployment or cloud migration.

Current evidence: [Milestone 8 verification](docs/milestone-8-verification.md). Earlier milestone verification remains historical evidence.

## Firebase foundation (Milestone 9A)

Supply the real `app/google-services.json` downloaded for the existing Spark project, with an Android client for `com.example.splitease`. It is ignored by Git and must be provided locally/through secure CI configuration; builds intentionally fail if it is absent or no matching package exists. Do not generate placeholder configuration. The supplied file was verified for project `splitease-9acc8`; API keys are not printed in diagnostics or documentation.

Google Services **4.5.0** processes configuration into resources; Firebase BoM **35.1.0** pins Authentication **25.0.0** and Firestore **27.1.0**. Existing AGP/Gradle, Java 17, minSdk 24, SDK 37, XML and ViewBinding are preserved. FirebaseInitProvider initializes the default FirebaseApp from generated resources before Application.onCreate. No second initialization path is needed. SplitEaseApplication still constructs LocalAuthService and the same app-scoped SqliteRepositories. No local database/session reset, migration or cloud synchronization occurs; schema remains 5.

In debug builds, Profile → **Firebase setup · debug only** → **Check Firebase setup** verifies local options/SDK instances and sends one unauthenticated HTTPS HEAD to `https://firestore.googleapis.com/`. No project ID, API key, local identity, credentials or document paths are transmitted by the probe. Any HTTP response demonstrates DNS/TLS/host reachability; it does **not** prove a Firestore database exists, providers are enabled, security rules authorize access, or the project is on Spark. Local accounts remain the active identity. The probe is manual, runs off the UI thread with timeouts, and its executor/observers follow the view lifecycle. Release builds contain a no-op hook with no diagnostic UI/probe.

**Cost boundary:** Spark only. No billing account, payment method, Blaze upgrade, Cloud Storage, Functions, Analytics, collection creation, rules deployment, Firestore query/write, Firebase sign-in or account creation is performed. A config file cannot prove the current plan: the owner must confirm Spark in the Firebase console before any later cloud feature activation. No billing/plan setting was changed. [Firebase pricing](https://firebase.google.com/pricing) distinguishes Spark from paid-plan allowances; do not enable billing to resolve a limitation.

Future adapters belong behind AuthService and the existing asynchronous repository contracts; choose them only in the composition root when a later milestone authorizes replacement. Keep SDK types, documents and queries out of UI/domain/contracts, use Firebase Auth UIDs as opaque cloud user identities, keeping local UUID identities separate, retain member-checked authorization and transactional accounting, and use fresh Firebase accounts/data with no local migration. ReceiptStorageRepository remains unchanged and local. No adapters or cloud feature flows were implemented in 9A; 9B adds only the isolated authentication adapter described below.

Evidence, exact checks and limits: [Milestone 9A verification](docs/milestone-9a-verification.md). Official setup/version references: [Android setup](https://firebase.google.com/docs/android/setup), [release notes](https://firebase.google.com/support/release-notes/android).

## Firebase authentication adapter (Milestone 9B)

`FirebaseAuthService` implements the existing asynchronous AuthService using `FirebaseSdkAuthBackend`. It supports email/password signup/login/logout, SDK session restoration, invalid-session validation, auth-state changes and password reset. It preserves the existing signup policy, normalized email and safe errors. Firebase owns credential persistence; the application does not store Firebase passwords, hashes or tokens. Shared preferences are excluded from backup/device transfer. Restoring a cached session requires online reload/token validation; network failure keeps the sandbox unavailable with retry/logout rather than exposing authenticated data.

**The application still uses LocalAuthService and SQLite by default.** Release builds have no selectable Firebase mode. Debug Login and Profile offer **Open Firebase Auth emulator · debug**. This opens a Fragment using the existing signup fields, ViewBinding and AuthViewModel, with its own injected adapter. In 9C this adapter also gates readiness on a complete Firestore profile. A separately named FirebaseApp targets only `demo-splitease-auth` on loopback port 9099; the real/default FirebaseApp is untouched. Cloud sign-in stays inside that screen and cannot open local groups or adopt a SQLite identity. The 9C username is now reserved atomically with its private profile in the Firestore Emulator; the normal local application is unchanged. Firebase UIDs are used unchanged, never mapped to local UUIDs or inserted into SQLite.

Start the official local Auth and Firestore Emulators (Node 20+; verification used Node 24 and CLI 15.34.1):

```sh
npx --yes --package firebase-tools@15.34.1 firebase emulators:start \
  --only auth,firestore --project demo-splitease-auth \
  --config firebase.emulator.json
adb -s emulator-5554 reverse tcp:9099 tcp:9099
adb -s emulator-5554 reverse tcp:8080 tcp:8080
./gradlew installDebug
```

Use only test credentials. No CLI login, live Firebase accounts, real reset emails, production Firestore or billing is needed. Debug permits cleartext only to loopback for the emulator; release keeps its normal network policy. Missing emulator connectivity yields a recoverable error, with no production fallback. Remove both ADB reverses when finished: `adb -s emulator-5554 reverse --remove tcp:9099` and `adb -s emulator-5554 reverse --remove tcp:8080`. Emulator accounts are temporary unless explicitly exported; no export or credentials belong in Git.

Opt-in SDK tests use `-e authEmulator true`; ordinary connected runs skip them. Reproducible commands, the separate force-stop restoration check, results and limits are in [Milestone 9B verification](docs/milestone-9b-verification.md).

### Auth/profile coordination (implemented in 9C)

Cloud application access must wait for a complete Firestore profile and unique normalized username reservation keyed to the Firebase UID. Firebase Auth creation and a Firestore transaction cannot commit atomically together. Create the Auth identity, then idempotently transact the username reservation plus profile together; treat an authenticated identity without a completed profile as **onboarding pending**, never application ready. Retry/reconcile after restart and ambiguous network outcomes. A username conflict should allow another choice. Only compensate by deleting a newly created Auth account after establishing that no profile/reservation committed; never blindly delete on a timeout. If compensation fails, retain a recoverable pending account. Later member-scoped rules must enforce ownership, reservation uniqueness and profile consistency server-side; no client-only security. 9C implements this coordination with recoverable pending onboarding, using the emulator-tested profile/registry transaction. It retains incomplete Auth accounts for recovery instead of deleting accounts after failures. The version-controlled candidate rules have not been deployed.

Cloud repositories, session selection and navigation must switch together when authorized, with an explicit backend/identity boundary. Existing local UUID validators must not reject opaque Firebase UIDs. Local accounts/data/receipts will not be migrated. Verify the real project remains Spark and enable/configure email/password through the owner before any live rollout; do not add billing, Storage, Functions or Analytics.

## Firestore profiles and usernames (Milestone 9C)

The debug sandbox now uses `ProfiledFirebaseAuthBackend` around the existing Firebase SDK backend and `FirestoreProfileRepository`. `FirebaseAuthService` publishes **PROFILE_REQUIRED** for a valid Auth identity without a profile; signup/login/restoration cannot publish SIGNED_IN until the server profile loads. The pending screen lets the user choose/retry a username or log out, including after recreation/relaunch. No cloud user can open local groups. Local authentication, UUIDs, database and financial/receipt repositories are untouched.

`CloudProfileRepository` reuses existing callbacks/executors and observable subscriptions. The local `UserRepository` includes password hashing/verification and deletion, so the cloud adapter deliberately implements the narrower profile contract instead. Its owner is derived from the scoped FirebaseAuth instance, never a caller-supplied UID. Operations: idempotent create, server read, exact normalized username lookup, permitted update, and cancellable owner observation. Callbacks reject account changes; listeners close on cancellation/account changes. Debug Firestore uses memory cache; owner reads and ready-state resolution require the server, and observations exclude pending/cache-only snapshots.

| Path | Data and access |
| --- | --- |
| `profiles/{Firebase UID}` | Exact UID, normalized/display username, normalized Auth email, optional unverified UPI ID, server-created/server-updated timestamps. Only owner get/create/permitted update; no list/delete |
| `usernames/{normalizedUsername}` | UID and display username only. Authenticated exact get; no list/delete. Atomic paired profile required for reservation/update |

Cloud usernames use **3–30 ASCII letters, digits or underscores**, trimmed at input and lowercased with Locale.ROOT for the deterministic key; display case is retained. This cloud-only rule avoids document-path/Unicode-fold ambiguities and is enforced in rules. Local username behavior is unchanged. UID, canonical username, email and creation time are immutable. Permitted updates are UPI and display-case changes of the same canonical username; display-case changes update the registry in the same transaction. No rename, email-change or account-deletion workflow is implemented. Exact lookup exposes no email, UPI or private timestamps. It is intended for later member search; 9D reuses this lookup for the isolated cloud group screens.

A Firestore transaction creates the private profile and public reservation together. In 9D it also publishes a minimal `publicIdentities/{uid}` mirror containing UID, normalized username and display username, so group APIs can resolve selected UIDs without accessing private profiles. Exact authenticated reads only; no collection listing, email or UPI. Display-case updates keep all three documents consistent. Existing demo profiles can repair a missing UID mirror through idempotent owner profile creation; no live/local migration is performed. Concurrent claimants cannot both win. On a failed create, the adapter checks the exact public reservation to distinguish a real collision from unavailable/permission errors. Retry after a lost acknowledgement returns the original committed profile, even if another username is entered. Missing profiles remain recoverable across login/restart; no passwords, tokens or pending usernames are saved in SQLite/preferences, and no automatic account deletion occurs. No cross-service transaction is claimed.

Candidate [Security Rules](firestore/firestore.rules) validate shapes, immutable identities, Auth email, server timestamps and `getAfter` pairing. All unimplemented paths default deny. [Emulator config](firebase.emulator.json) is deliberately separate from production deployment configuration. Live deny-all rules remain untouched. Production activation requires rule review/approval, Spark/provider verification, an explicit deployment plan and later coordinated cloud repositories/navigation. Do not point the sandbox at production or enable billing.

See [9C verification](docs/milestone-9c-verification.md) for focused tests, offline/recovery limits and deployment steps. Earlier reports remain historical. Rules design follows Firebase's [atomic operation validation](https://firebase.google.com/docs/firestore/security/rules-conditions) and [transaction semantics](https://firebase.google.com/docs/firestore/manage-data/transactions); emulator setup follows the [official guide](https://firebase.google.com/docs/emulator-suite/connect_firestore).

## Firestore groups and membership (Milestone 9D)

In debug Login/Profile, open the Firebase sandbox, register/sign in to the **demo emulator**, then select **Cloud groups · emulator**. This opens a nested Navigation graph reusing Groups Home, Create Group and Group Detail/Members with a scoped GroupsViewModel and FirestoreGroupRepository. Normal navigation still uses LocalAuthService/SQLite. The cloud graph contains no expense, settlement or recurring destinations and never falls back to local data. Release has no cloud selector. Auth identity changes clear the child stack; restored cloud content stays hidden until the Firebase profile session is ready.

`FirestoreGroupRepository` implements the existing GroupRepository. Group IDs remain generated UUIDs; membership identities are opaque Firebase Auth UIDs. The actor is derived from AuthService, checked against the scoped SDK identity, and compared with the existing interface's actor argument. Home uses one `memberIds array-contains currentUid` listener; Detail uses one authorized group-document listener. Views unsubscribe on stop; streams terminate on account/permission changes. Cache-only and pending writes are never shown as confirmed group state. An initial server deadline is 10 seconds; connection loss clears the view with a Refresh action. Refresh resubscribes after reconnection; this is a deliberate server-authoritative policy, not offline group editing.

Each `groups/{UUID}` contains name/type, immutable creatorId/createdAt, transferable adminId, server updatedAt, checked revision, current memberIds and a membership map. Map entries snapshot public username, ACTIVE/LEFT status, original joinedAt and latest leftAt. One document makes creation/current membership/admin ownership atomic, avoids a fan-out of membership listeners, and retains historical entries. New groups include their creator as admin automatically; Flat/Trip are supported. Only admin can add/rejoin registered identities or transfer to another current member. A rejoin preserves the old join/departure metadata. Names are 1–100 characters. Creation accepts **up to eight people including the creator** because of Rules budgets; add further members individually afterward. This is an initial-write bound, not an eight-person group-size policy. Firestore's 1 MiB document limit remains the technical bound; never prune historical membership to fit a write.

Mutations are transactions with server timestamps/revisions. A concurrent conflict that Rules rejects before SDK retry is retried at most twice only after a server read confirms a newer revision and continuing admin authority. Actual permission loss is never retried as authorization. Duplicate memberships are rejected; a retry of an already created group ID returns that group rather than overwriting it. Admin name/type update exists behind the repository contract; no new rename UI or delete workflow was added.

**Cloud leaving is disabled** at the API, Rules and UI levels until an approved financial eligibility rule exists in 9E. Unknown balances are never assumed zero. `GroupDetails.accountingAvailable=false` means financial fields cannot be interpreted/displayed; Home hides totals/balances and Detail shows explicit unavailable placeholders. Add Expense/Settle Up are absent from this graph. Local financial features and zero-balance leaving remain unchanged.

### Indexes and read/write patterns

`firestore/firestore.indexes.json` declares no composite index and exempts the unqueried `members` map from indexing. Keep the default **array-contains index on memberIds**. Home sorts the returned group list by creation time/ID on the client, so it needs no composite sort index. UID/name lookup is an exact document get. See Firebase's [index overview](https://firebase.google.com/docs/firestore/query-data/index-overview); the emulator does not prove live index configuration.

- Home: one listener, initial matching group reads and changed group documents thereafter; no per-group listener or private-profile read. Detail: one document listener, not one listener per member. Normal navigation stops the previous screen's stream.
- Creation: transaction reads the new group ID plus each initial public UID identity, writes one group document, then reads the committed group to return server metadata. Admin add: group + target public UID read and one group write. Transfer: group read + one write. Rules validation may add dependent identity reads; account switching, reconnects and bounded conflict retries can add reads.
- Profile signup now atomically writes private profile + username reservation + public UID mirror. Existing complete-profile retries read the mirror and write only when repair is needed. Public mirrors expose no additional private profile fields.

All checks use local Auth/Firestore Emulators. No production writes, indexes/rules deployment, billing, Blaze, Storage or Functions. Rules/indexes require separate review/approval, owner Spark/provider verification and controlled production checks before any later activation. Evidence and remaining limitations: [9D verification](docs/milestone-9d-verification.md).

## Remaining scope

API 24/physical-device coverage and exhaustive accessibility/device testing remain future checks. Receipt OCR remains local. Firebase authentication is implemented behind an isolated emulator path; production auth selection and cloud financial repositories remain future work with fresh accounts/data. Live Firebase account/reset-email behavior is not verified. Milestone 9E.1 adds isolated cloud expenses; cloud settlements/balances remain future scope. See AGENTS.md for architecture rules.

## Firestore expenses (Milestone 9E.1)

**9E.1 expense-specific verification supports finalization.** The refresh/delete check passed; the later account-switch/member-add failure was isolated to premature test readiness checks and corrected in test code. One isolated regression passed with server-confirmed membership. Existing 55 combined instrumentation tests, 60 unit tests, debug/release builds and lint (zero errors, 12 existing warnings) remain passing. The complete corrected monolithic cloud UI journey has not been rerun; see the verification report for that pending aggregate result and device/live limitations.

The explicit debug demo-emulator graph now reuses Add/Edit Expense and the group Expenses tab with `FirestoreExpenseRepository`. Normal SQLite mode remains the default. Cloud members can create, view, edit and delete real shared expenses, including an optional description. Receipts are hidden in cloud mode; local receipt scanning/storage are unchanged. Cloud balances/settlements and leaving remain unavailable. This section supersedes 9D's expense-placeholder restriction; no production activation is included.

Each `expenses/{UUID}` stores an atomic current revision with group/creator, title/description, category/date, payer, sorted participant UIDs and exact integer-paise shares. The same equal-split algorithm assigns leftover paise in sorted identity order. Candidate Rules independently validate every share, unique/sorted identities, group membership and immutable fields. The bounded Rules implementation supports up to **8 participants and 1,000,000,000,000 paise (₹10 billion)** per expense; larger values are rejected. These bounds do not change local expenses.

Edits and deletion require the viewed revision. Concurrent/stale changes fail with a refresh message instead of overwriting another member's work. Deletes retain a tombstone and advance the revision, preventing stale recreation; ordinary observation hides tombstones. Revisions replace shares atomically without retaining edit history. Historical participant/payer identities remain usable on their existing expense, while new selections must be current members.

Home retains its single group query. A cloud detail/editor combines group and expense observations; expenses use one group-filtered query plus a membership guard listener. Subscriptions close on stop/account change. This currently duplicates the parent group guard in the combined detail stream; there are no listeners per participant and no expense queries per Home card. Each save transaction reads group + expense and writes one expense; delete reads the same pair and writes a tombstone. Rules use the parent group document, with repeated accesses cached by the rules engine. A groupId equality query uses its default single-field index; no composite index is required. Tombstones are read by that query and filtered client-side, so retained deletions increase future read volume. Pagination/compaction need a separately reviewed design.

See [9E.1 verification](docs/milestone-9e1-verification.md) for results and deployment limits. Candidate rules remain emulator-only; review/approval, owner Spark/no-billing verification and live configuration checks are prerequisites to any future deployment. No production writes or deployment, local migration, receipt upload, Storage, Functions or Analytics are included.
