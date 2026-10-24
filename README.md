# SplitEase

Java Android expense-sharing app for Indian users. Milestone 7 adds monthly shared-bill tracking, contribution history and local reminders alongside the existing expenses, groups and settlements. Local authentication and profiles are preserved. Recurring now tracks independent monthly contributions. The existing SQLite foundation, single-Activity navigation and light/dark identity are preserved. Firebase and OCR are not included. UPI handoff does not verify payments.

## Setup and toolchain

Open the directory in Android Studio supporting AGP 9.2 (Panda 4 or newer). Install SDK Platform 37 and Build Tools 36.0.0. Configure your SDK in untracked `local.properties` (`sdk.dir=/absolute/path/to/Android/sdk`) or `ANDROID_HOME`.

- Application ID / namespace: `com.example.splitease`.
- Java 17 source/target; minimum SDK 24; compile/target SDK 37.
- AGP 9.2.0; Gradle wrapper 9.4.1 with pinned distribution checksum.
- The existing daemon JVM configuration selects JDK 25; verification used JDK 25. Java application code remains Java 17.
- Existing dependencies: AppCompat 1.7.1, Core 1.17.0, Activity 1.12.4, Fragment 1.8.9, Navigation Fragment/UI 2.9.8, Material Components 1.13.0.
- Added: Bouncy Castle `bcprov-jdk18on:1.83` for portable Argon2id across API 24+, AndroidX Test Runner 1.6.2 and AndroidX Test JUnit 1.2.1. JUnit 4.13.2 remains for local tests.
- Lifecycle ViewModel/LiveData 2.11.0; WorkManager 2.12.0 for local reminders; application version name 0.7.0.
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
  auth/                       Replaceable AuthService, LocalAuthService and SessionStore
  model/                      Immutable User, Group, GroupMember, Expense,
                              ExpenseSplit, Settlement, RecurringBill,
                              RecurringBillCycle, RecurringBillContribution
  domain/                     Rules, Balances, SettlementCalculator, RecurringSchedule
  repository/                 Five feature interfaces, callbacks, observable queries
  data/                       SplitEaseDatabase, SqliteRepositories, PasswordHasher
  reminders/                  WorkManager checks and session-scoped notifications
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

Group Detail now has Members, Expenses and Balances tabs. Expenses are grouped by their UTC calendar date (newest first), with category glyph/label, payer, total, participant count, your share and your signed net impact. The floating add action opens the editor; viewing an expense offers edit and confirmed deletion. Any current group member can edit/delete.

The editor accepts a title, positive INR amount (plain digits with up to two decimal places), payer, one or more checkbox participants, Food/Travel/Rent/Bills/Other category, and a calendar date. Exact shares preview immediately using the existing sorted-UUID remainder rule. No implicit rounding, percentage or custom splits. New payers/participants must be current members; an old expense can retain its original departed payer/participants, visibly labeled former members. Existing legacy category text can be retained on edit, but new entries use the five categories.

`ExpenseViewModel` derives its actor from AuthService and keeps a SavedStateHandle draft across recreation. It uses the existing GroupRepository authorized snapshot stream and ExpenseRepository mutations. Fragments handle views/navigation only. SQLite atomically saves expense/split replacements, checks membership inside transactions and rolls back invalid/overflowing changes. `ExpenseAmounts` parses with BigDecimal and checked long conversion; summaries and per-expense impact use checked integer paise. The total-expense aggregate is checked before committing a save, even when net balances cancel to zero.

`GroupDetails` includes immutable expense, balance and public-name snapshots in the same worker read. It retains current members and historical identities referenced by expenses or recorded settlements. Balances derive from the existing `Balances` domain logic; positive is owed to the member, negative is owed by the member, zero is settled. These aggregate nets do not imply specific debtor/creditor payment pairs. They are never persisted and recurring contributions never enter them. Home and Detail refresh after mutations and account switches. The summary total means all expense amounts, not outstanding debt; latest expense date is an actual expense date, not an invented modification timestamp.

Milestone 5 required no schema or dependency changes. Milestones 6–7 extend that release with settlements/UPI handoff and recurring tracking, described below. OCR and Firebase remain outside scope.

## Schema and migrations

Database: app-private `splitease.db`, version **4**. Foreign keys are enabled on every connection. All standalone entity identifiers are canonical String UUIDs validated at repository boundaries. Association rows use composite keys of the referenced UUIDs rather than redundant identifiers. Financial columns require SQLite integer storage; Java uses `long` paise.

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

Group/date indexes support expense and settlement lists; membership, active-bill and contribution indexes support scoped queries. Composite foreign keys prevent splits/contributions from referencing a different group's members.

Migration chain: v1 creates core accounting tables; v2 adds recurring tables; v3 adds immutable creator attribution; v4 adds recurring cursors and reminder receipts. New databases apply the same chain. Upgrades are additive and transactional under SQLiteOpenHelper; unsupported upgrades/downgrades throw instead of deleting data. Because v1/v2 did not record original creator identity, the v3 migration backfills their current admin as the best available attribution. New groups retain their actual creator through admin transfers. Milestone 1 shipped no database schema.

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

The existing authentication schema remains unchanged; overall database version is now **4** with the additive M7 recurring migration. UserRepository gained normalized `findUserByEmail`; duplicate-account failures use a storage-independent typed error. SQLite UNIQUE constraints remain authoritative. Registration validation is in the auth boundary; existing Milestone 2 accounts can still log in even if their older password would not meet the new signup policy. Username/email normalization remains trim + Locale.ROOT lowercase for both create and lookup.

`AuthService` is the replaceable authentication/session interface. `LocalAuthService` uses the existing asynchronous UserRepository, coordinates on its own serial worker, maps internal errors to safe codes and publishes session snapshots through the existing ObservableQuery pattern. `AuthViewModel` observes on the main thread and exposes lifecycle-aware LiveData. Fragments bind views and forward actions; they do not query SQL, hash passwords or access preferences. AuthService derives the profile owner from its own authenticated snapshot. Logout invalidates in-flight authentication callbacks so a late registration/login cannot restore a logged-out session.

`PreferencesSessionStore` stores only `user_id`, a UUID, in application-private `local_session.xml`. No password or hash is stored there. Reads and checked, synchronous `commit()` writes run on the auth worker; success is reported after the write completes. On process startup, the saved UUID is validated against an actual user record. Missing/deleted/malformed identities are cleared. Storage/database failures leave a gated retry screen rather than exposing authenticated content. The identifier is intentionally not encrypted: it is not a password, and the protection boundary is Android app-private storage. Devices with root/debug access are outside this local-account security boundary.

MainActivity restores Navigation behind a loading gate and shows it only after session resolution. It rebuilds and clears saved tab stacks on login/logout/account changes, preserving the current tab during same-account Activity recreation. Bottom navigation exists only on Groups, Recurring and Profile. Attempts to navigate into an authenticated destination while signed out are redirected. Back from signup returns to login; Back after logout cannot reach prior accounts. Root Back may background the task on modern Android.

Password arrays owned by the service/repository are cleared. Password text is never retained by the ViewModel or saved view hierarchy; rotation clears password fields, so an unsubmitted form requires re-entry. In-flight submission state survives recreation. Profile shows username/email and an optional editable UPI ID; saving blank removes it. The ID is explicitly unverified; payment handoff is available only through Settle Up. This release does not claim that a stored email address is verified.

Local accounts/data are device-specific. Firebase will start fresh; no local-account migration to Firebase is planned.

## Verification

Milestone 7: **23/23 local tests**, **35/35 API 37 emulator tests**, build/install passed; final lint **0 errors, 12 pre-existing warnings**. Force-stop/relaunch retained the session, bill and Paid status; light/dark, landscape and 1.3 font-scale checks passed. Details are recorded in [the current report](docs/milestone-7-verification.md). Earlier milestone reports remain historical evidence. Use the build/test commands above; emulator coverage is Android 17 / API 37, with API 24/physical-device coverage still pending.

## Settlements and UPI (Milestone 6)

Open Group Detail → Balances → Settle Up. Suggested transfers and recorded settlements are separate lists. Select a suggestion to record a full/partial payment, or select a record to correct its amount/date. Recording requires independent confirmation that payment happened. Either current payer or payee can record; any current member can edit. Historical parties and records are preserved.

The pure-Java calculator greedily matches largest remaining debts and credits, breaking ties by UUID. It conserves integer paise and rejects overflow; it does not guarantee the mathematically minimum number of payments. Recurring bills are excluded. New amounts cannot exceed the current suggested pair transfer. Editing excludes the old record and computes the pair’s debt/credit capacity; its ceiling is the greater of that capacity and the old amount, so corrections may retain/reduce an old amount after expenses change. Parties/group cannot change. Duplicate warnings compare group, payer, payee, amount and UTC calendar date (excluding the edited record); users can explicitly proceed.

Recipient UPI metadata is exposed only through the membership-authorized settlement snapshot for suggested recipients. UPI IDs are unverified. The chooser receives an encoded INR intent for the selected suggestion’s full amount; changing the manual record amount does not change that intent. Returning or cancelling never records or confirms payment. Missing UPI apps/IDs leave “Already paid? Record it” available. No bank integration, transaction verification or automatic reconciliation exists.

Milestone 6 added no dependencies or migration. Milestone 7 adds WorkManager 2.12.0 and schema version 4. Milestone 6 verification is documented in [Milestone 6 verification](docs/milestone-6-verification.md). Earlier reports are historical evidence.

## Recurring shared bills (Milestone 7)

The Recurring tab lists bills across current groups with an optional group filter. Create/edit name, exact positive INR amount, current participants, handler, due day 1–31 and reminder lead time 0–31 days (for example 1, 3 or 7). Bills can be deactivated and reactivated. Each bill shows the current cycle’s paid progress and historical/upcoming cycles. Any current member can mark/unmark any contribution after confirmation; paid means contributed to the handler. This never creates expenses/settlements or changes net balances.

Cycles snapshot amount, handler, participants, exact sorted-UUID shares and clamped due date. Generation is transactional/idempotent and runs on load/resume and background checks. The schedule begins in the creation month and fills missed months through the current UTC month. A next-month cycle is generated early when its reminder lead window opens. Existing snapshots never change. Before edits, missing months are generated using the old template; otherwise missing months use the latest available template. There is no historical template-version audit trail.

Deactivated bills preserve history without generating. Reactivation resumes in the current month and skips inactive gaps. If template members/handler have left, generation pauses with an actionable message; edit current identities to resume, or deactivate while retaining the historical template. UTC calendar months/dates are consistent with existing records; day 31 clamps to shorter month end.

Schema 4 adds only recurring_schedule (generation cursor) and recurring_reminders (unique cycle/recipient receipt). Existing bills start from their earliest known cycle, or the upgrade month if no history exists; earlier history is not invented. Existing accounts, groups and accounting records remain intact.

WorkManager 2.12.0 runs an immediate check on session/resume/settings updates plus a unique 12-hour periodic check that survives normal process death/reboot. Android may delay work; there is no exact-minute guarantee. Force-stopped apps cannot deliver background reminders until reopened. Notifications identify bill, group and due date, and open an authenticated, membership-checked bill destination. Permission/global/channel denial prevents claiming reminders; notification settings remain accessible.

Local phase: only the currently signed-in unpaid participant receives a reminder during the lead window through the due day. Other local accounts receive eligible reminders when signed in, so this device cannot notify all accounts simultaneously or reach other devices. Account changes clear notifications and cancel prior work; paid/deactivated/inaccessible bills are pruned. Lock-screen public content is generic. Durable receipts avoid repeat reminders after restart, account switching or paid/unpaid toggles. Receipts are claimed before posting: a crash or permission race can miss a reminder rather than deliver a duplicate. Android notifications are advisory; the app remains the source for contribution status.

## Remaining scope

API 24/physical-device coverage and exhaustive accessibility/device testing remain future checks. Receipt OCR and Firebase are not implemented. Firebase Auth/Firestore will use fresh accounts/data. No work beyond Milestone 7 is included. See AGENTS.md for architecture rules.
