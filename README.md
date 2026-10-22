# SplitEase

Java Android expense-sharing app for Indian users. Milestone 4 adds real local groups and member management alongside signup/login, durable sessions, logout and account profiles. Recurring remains a placeholder; expense and balance screens are reserved for Milestone 5. The existing SQLite foundation, single-Activity navigation and light/dark identity are preserved. Firebase, payments, OCR and notification scheduling are not included.

## Setup and toolchain

Open the directory in Android Studio supporting AGP 9.2 (Panda 4 or newer). Install SDK Platform 37 and Build Tools 36.0.0. Configure your SDK in untracked `local.properties` (`sdk.dir=/absolute/path/to/Android/sdk`) or `ANDROID_HOME`.

- Application ID / namespace: `com.example.splitease`.
- Java 17 source/target; minimum SDK 24; compile/target SDK 37.
- AGP 9.2.0; Gradle wrapper 9.4.1 with pinned distribution checksum.
- The existing daemon JVM configuration selects JDK 25; verification used JDK 25. Java application code remains Java 17.
- Existing dependencies: AppCompat 1.7.1, Core 1.17.0, Activity 1.12.4, Fragment 1.8.9, Navigation Fragment/UI 2.9.8, Material Components 1.13.0.
- Added: Bouncy Castle `bcprov-jdk18on:1.83` for portable Argon2id across API 24+, AndroidX Test Runner 1.6.2 and AndroidX Test JUnit 1.2.1. JUnit 4.13.2 remains for local tests.
- Milestone 3 adds Lifecycle ViewModel/LiveData 2.11.0; application version name is 0.4.0.
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
  ui/                         Activity, auth, Profile, Groups screens/ViewModel, Recurring placeholder
  auth/                       Replaceable AuthService, LocalAuthService and SessionStore
  model/                      Immutable User, Group, GroupMember, Expense,
                              ExpenseSplit, Settlement, RecurringBill,
                              RecurringBillCycle, RecurringBillContribution
  domain/                     Rules (validation/equal splitting/monthly dates), Balances
  repository/                 Five feature interfaces, callbacks, observable queries
  data/                       SplitEaseDatabase, SqliteRepositories, PasswordHasher
  util/                       Exact INR display formatting
app/src/test/                 Android-independent monetary and scheduling tests
app/src/androidTest/          Real SQLite and navigation/recreation tests
app/src/main/res/xml/         Database backup/transfer exclusions
```

`SplitEaseApplication` owns one app-scoped `SqliteRepositories` instance and exposes only feature interfaces through `users()`, `groups()`, `expenses()`, `settlements()` and `recurringBills()`. Database initialization errors are logged and accessible through `initializationError()`; each repository call also reports its own failure. Profile consumes the authenticated account through AuthService and an Activity-scoped AuthViewModel; Groups uses a Fragment-scoped session-bound GroupsViewModel; Recurring remains a placeholder.

Repository operations return promptly and execute on a single database worker. Validation, membership checks and multi-table writes run in one transaction. Failed operations roll back and report `onError`; successful operations deliver exactly one non-null result. Missing records report an error rather than null. Caller-supplied callback executors determine delivery threads and must be serial, remain accepting, and run callbacks that do not throw. Use a main-thread executor for UI callbacks, with lifecycle checks. The original callback/observable contracts are retained.

Observers deliver an initial snapshot and refresh after successful writes through the same repository owner. Lists are immutable. Closing a subscription suppresses queued notifications; an already-running callback can finish. UI owners must close subscriptions with their view lifecycle. Notifications are currently coarse-grained across queries; external SQL writes and other repository owners do not trigger them. Production must use the single application owner. `close()` prevents new operations, cancels observations and queues database closure behind accepted work.

Feature interfaces expose no Android, SQLite or Firebase types, allowing future Firestore adapters. The shared SQLite owner implements the five contracts to keep transaction and threading behavior consistent. Raw SQL belongs only in `data` (and SQLite tests).

## Groups and members (Milestone 4)

- Home observes only the authenticated account’s current groups, with Flat/Trip type, current member count/initials and actual creation date. There is no last-activity column, so no invented activity or balance is shown.
- Create a group with a name, type and optional existing users found by exact, case-insensitive username. Remove selections before saving. Creator membership and all selected memberships are committed atomically, with the creator as initial admin.
- Current members can view details. Only the current admin can add members or transfer ownership to another current member. Additions take effect immediately, without invitations, after confirmation. Transfer and departure also require confirmation.
- Leaving requires exactly zero derived balance and prior ownership transfer if you are admin. Departures retain the membership row and historical records; rejoining preserves original join time. A sole admin cannot leave until another member is added and made admin.
- `MemberIdentity` exposes only UUID and username. `GroupDetails` is an immutable authorized snapshot; detail observations recheck current membership on every refresh. Search does not return emails, UPI IDs or credentials. Existing lower-level accounting APIs remain trusted internal contracts, not a public API/security boundary.
- `GroupsViewModel` obtains the actor from AuthService, cancels observations when stopped, ignores stale callbacks after identity changes, and saves non-sensitive creation drafts in SavedStateHandle. The Activity guards creation/detail destinations and shows bottom tabs only at Groups/Recurring/Profile roots. Logout/account changes discard the prior navigation state.
- No schema migration, new dependency, authentication change or recurring-model change was required. Lists are currently unpaginated for local use. Expenses/Balances are explicitly labeled placeholders; no accounting UI or payments were added.

## Schema and migrations

Database: app-private `splitease.db`, version **3**. Foreign keys are enabled on every connection. All standalone entity identifiers are canonical String UUIDs validated at repository boundaries. Association rows use composite keys of the referenced UUIDs rather than redundant identifiers. Financial columns require SQLite integer storage; Java uses `long` paise.

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

Group/date indexes support expense and settlement lists; membership, active-bill and contribution indexes support scoped queries. Composite foreign keys prevent splits/contributions from referencing a different group's members.

Migration chain: v1 creates core accounting tables; v2 adds recurring tables; v3 adds immutable creator attribution. New databases apply the same chain. Upgrades are additive and transactional under SQLiteOpenHelper; unsupported upgrades/downgrades throw instead of deleting data. Because v1/v2 did not record original creator identity, the v3 migration backfills their current admin as the best available attribution. New groups retain their actual creator through admin transfers. Milestone 1 shipped no database schema.

Deletion semantics are explicit: referenced users cannot be deleted. Leaving changes membership status and preserves financial references. Bills with historical cycles cannot be deleted; deactivate them instead. Group deletion also fails if recurring history would be orphaned. Groups without recurring history can be deleted with their owned records in a transaction. No user-facing deletion workflow is introduced.

## Monetary and membership rules

- All money is integer paise. No float/double monetary operations. Display conversion uses BigDecimal.
- Equal splits sort participant UUIDs lexicographically, divide by participant count and assign each leftover paise to the first IDs. Input order does not change results. Zero shares are allowed when there are fewer paise than participants. Totals must be positive.
- Expense writes require explicit shares matching that exact equal split and summing to the total. Replacing an expense and its splits is atomic. Other split methods are not enabled yet.
- Balances are derived from expenses and settlements only: positive means owed to the member. Payers receive expense credit, participants owe shares; settlement payers reduce their debt and payees reduce their credit. Checked arithmetic fails on overflow rather than persisting unusable records. No balance table or settlement optimizer exists.
- Current group members may modify group records and any expense. New expense participants/payers must be current; edits can retain historical participants/payers already attached to that expense. Expense group/creator and group creation metadata are immutable.
- Current members may edit settlement amount/date; parties/group are immutable. Edits overwrite prior values. Positive partial settlements are accepted; duplicate-looking settlements are not blocked. No duplicate-warning UI is implemented.
- A member can leave only at exactly zero derived balance. The admin must first transfer ownership to another current member. Only the current admin performs that transfer or adds members. Former members cannot mutate group records.
- Membership retains the original join timestamp; leaving records the latest departure. Rejoining reactivates the same row and clears its current departure timestamp. This is not an exhaustive membership-event audit trail.
- The current AuthService owns the active identity. Profile updates derive their user ID from that session, never from a view-provided account ID. Future group/expense presentation must likewise use the authenticated identity; raw membership repository methods are not themselves authentication boundaries.

## Recurring bills are separate

Recurring operations never create expenses or settlements and never affect derived balances. Any current group member can mark or unmark any contribution, including a historical participant's contribution. Paid means that participant contributed to the recorded handler; it does not record a normal settlement.

Every cycle snapshots amount, handler, participants and equal shares. Later bill edits do not rewrite cycles. Each new cycle starts entirely unpaid; duplicate months fail the unique constraint. Cycles and shares cannot be edited/deleted through the public interface, except for the confirmed paid/unpaid flag.

Bill templates require active participants/handler when active. If someone leaves, the template must be updated before creating another cycle; historical cycles remain intact. Deactivating a template is still possible after departures. An optional handler may be null; no handler-selection workflow is inferred.

Dates use UTC epoch milliseconds. Monthly scheduling uses year/month (1–12) and due day (1–31); days beyond month end clamp to the last day. The resulting due date is a UTC calendar-date representation, not a notification instant. Bill lead time and all participant IDs are stored for future reminders; timezone-aware notifications and scheduling are not implemented.

## Local credentials

User snapshots omit password material. Credentials are stored separately in columns within the user row: Argon2id v1.3, random 16-byte salt, 32-byte hash, 19 MiB memory, two iterations and parallelism one. Bouncy Castle's lightweight implementation avoids depending on newer Android crypto providers. Comparisons use `MessageDigest.isEqual`. Repository-owned password copies are cleared after work or error delivery; callers own and should clear their original `char[]` arrays. No plaintext password is stored or logged.

These parameters follow the [OWASP password-storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html); implementation is supplied by [Bouncy Castle](https://www.bouncycastle.org/download/bouncy-castle-java/). Stored algorithm/cost metadata permits future upgrades, but automatic rehash, password-change flows and recovery remain outside this milestone. Local sessions are implemented; there is intentionally no fixed attempt limit or automatic lockout. SQLite itself is not encrypted. Android app-private storage is used; backup is disabled and Android 12+ database and local-session preference cloud backup/device transfer are explicitly excluded. `fullBackupContent=false` also explicitly disables the pre-Android-12 full backup path.

## Local authentication and sessions (Milestone 3)

Signup validates username presence, normalized email format and matching passwords. A live checklist displays all five requirements: minimum eight characters, uppercase, lowercase, number and non-whitespace special character. Password fields have visibility toggles; validation failures are visible; submissions show loading and cannot be duplicated. Login checks the real Argon2id credential through UserRepository with a generic incorrect-email-or-password error. No reset link, hardcoded credential, attempt counter or lockout exists.

The existing schema remains **version 3** with **no new migration**. UserRepository gained normalized `findUserByEmail`; duplicate-account failures use a storage-independent typed error. SQLite UNIQUE constraints remain authoritative. Registration validation is in the auth boundary; existing Milestone 2 accounts can still log in even if their older password would not meet the new signup policy. Username/email normalization remains trim + Locale.ROOT lowercase for both create and lookup.

`AuthService` is the replaceable authentication/session interface. `LocalAuthService` uses the existing asynchronous UserRepository, coordinates on its own serial worker, maps internal errors to safe codes and publishes session snapshots through the existing ObservableQuery pattern. `AuthViewModel` observes on the main thread and exposes lifecycle-aware LiveData. Fragments bind views and forward actions; they do not query SQL, hash passwords or access preferences. AuthService derives the profile owner from its own authenticated snapshot. Logout invalidates in-flight authentication callbacks so a late registration/login cannot restore a logged-out session.

`PreferencesSessionStore` stores only `user_id`, a UUID, in application-private `local_session.xml`. No password or hash is stored there. Reads and checked, synchronous `commit()` writes run on the auth worker; success is reported after the write completes. On process startup, the saved UUID is validated against an actual user record. Missing/deleted/malformed identities are cleared. Storage/database failures leave a gated retry screen rather than exposing authenticated content. The identifier is intentionally not encrypted: it is not a password, and the protection boundary is Android app-private storage. Devices with root/debug access are outside this local-account security boundary.

MainActivity restores Navigation behind a loading gate and shows it only after session resolution. It rebuilds and clears saved tab stacks on login/logout/account changes, preserving the current tab during same-account Activity recreation. Bottom navigation exists only on Groups, Recurring and Profile. Attempts to navigate into an authenticated destination while signed out are redirected. Back from signup returns to login; Back after logout cannot reach prior accounts. Root Back may background the task on modern Android.

Password arrays owned by the service/repository are cleared. Password text is never retained by the ViewModel or saved view hierarchy; rotation clears password fields, so an unsubmitted form requires re-entry. In-flight submission state survives recreation. Profile shows username/email and an optional editable UPI ID; saving blank removes it. The ID is explicitly unverified and no payment is initiated. This release does not claim that a stored email address is verified.

Local accounts/data are device-specific. Firebase will start fresh; no local-account migration to Firebase is planned.

## Verification

Milestone 4 final status: debug build/install successful, **14/14 local tests** and **18/18 API 37 emulator tests** passing; lint has **0 errors and 13 existing warnings**. Focused group tests cover atomic creation, normalized username lookup, duplicate rejection, admin-only mutations, transfer/leave eligibility, revoked access, account switching and recreation. The full suite also retains authentication, migration, money and recurring-isolation coverage. A post-suite singular/plural presentation correction was rebuilt, linted and checked on the installed APK.

Current verification and lint classification are in `docs/milestone-4-verification.md`; authentication evidence is retained in `docs/milestone-3-verification.md`; the earlier persistence report remains in `docs/milestone-2-verification.md`. Local tests cover leftover paise, order independence, long limits, overflow, partial settlements, invalid inputs and leap-month clamping. Instrumentation tests use actual Android SQLite for CRUD, constraints, reopening, transaction rollback after injected failures, recurring isolation/history/uniqueness, migrations, member/admin rules, callback threading and observer cancellation. A navigation test launches all three tabs and recreates the Activity.

## Next milestone

Milestone 5 may add the approved expense/balance UI using the current authenticated identity and existing accounting repositories; its detailed scope must be confirmed separately. Milestone 4 stops at group/member management. Keep recurring contributions separate from balances and ordinary expenses. Add minimum-SDK/physical-device coverage. Firebase Auth/Firestore remain Phase 2 with fresh accounts and data; password recovery belongs to that phase. Notifications, payments, OCR and final settlement optimization are outside the current milestone.

See `AGENTS.md` for mandatory architecture rules.
