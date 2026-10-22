# Milestone 4 verification

Completed 22 October 2026. Scope stops at groups/member management; no expense UI, Firebase or recurring functionality was added.

## Implementation

- Groups Home shows only the signed-in user's ACTIVE groups, type, current member count/initials and actual creation date. Empty/loading/error states and retry are supported. Last activity is not stored, so creation date is labeled honestly.
- Create Group supports Flat/Trip, required trimmed name, exact normalized username lookup, add/remove selections and automatic creator/admin inclusion. Group and all selected memberships commit atomically; duplicate members and invalid accounts fail without partial writes.
- Detail shows current members/admin. Only admin can add/rejoin members or transfer ownership. Add, transfer and leave require confirmation. Leaving checks exactly zero derived balance within the repository transaction; admins must transfer first. Historical membership/records remain intact.
- New public identity snapshots expose only UUID and username. Membership-checked detail observations revoke access after departure. Acting IDs come from AuthService in GroupsViewModel, never Fragment arguments. Group IDs and selected target IDs are navigation/action inputs, not actor identities.
- Fragment-scoped ViewModels use LiveData and SavedStateHandle for non-sensitive creation drafts, explicit main-thread callbacks and lifecycle subscription cancellation. Identity generations suppress stale completion. The Activity guards new destinations, clears account-specific navigation on logout/switch and shows bottom tabs only at the three roots.
- No schema migration: version remains 3. No new dependencies or changes to authentication/recurring models. Java 17, min SDK 24, compile/target 37, AGP 9.2.0 and Gradle 9.4.1 retained. Application version name is 0.4.0.

## Automated checks

On `Medium_Phone_API_37.0`, Android 17 / API 37 emulator:

| Check | Result |
| --- | --- |
| `assembleDebug` | Passed |
| `testDebugUnitTest` | 14 passed, 0 failures |
| `connectedDebugAndroidTest` | 18 passed, 0 failures |
| `lintDebug` | 0 errors; 13 existing warnings |
| Final `installDebug` | Installed successfully |
| `git diff --check` | Passed |

The full build/unit/emulator/lint command was run once at completion. Focused development checks preceded it: SQLite cases passed; the initial UI harness read its accessibility window before the confirmation dialog was ready. Waiting for the dialog fixed the test harness, and both focused group UI tests then passed. No production authentication workaround was added.

The final full run covered 11 SQLite tests, 3 local-auth instrumentation tests, 2 existing auth-navigation tests and 2 new group-navigation tests. The new repository cases cover atomic creation/rollback, normalized exact lookup, duplicate selections, creator attribution, admin-only addition, transfer to current members, nonmember/left-member denial, rejoin metadata and revocation of detail observations. Existing money tests plus the new eligibility test check nonzero-balance rejection and exact-zero departure. Existing migrations, recurring isolation, session/authentication and observation/cancellation tests still pass.

The new UI tests exercise both group types, selection/removal, draft and detail Activity recreation, real local users, immediate admin addition, transfer, departure, account switching, root tabs, light/dark recreation and signed-out destination/Back-stack boundaries.

Visual review and lint found the new string rendered “1 members.” It was replaced with an Android plural resource after the full suite. `assembleDebug lintDebug installDebug` then passed, and the installed APK's “Trip · 1 member” was checked directly. The passing business/security suites were not repeated for that presentation-only correction.

## Lint classification

The Milestone 3 baseline was 14 warnings. This milestone removes Groups' API-28-only accessibility-heading attribute by using ViewCompat. Its newly introduced plural warning was corrected. Final warnings:

- 11 dependency/tool version notices: 9 `GradleDependency`, 1 `AndroidGradlePluginVersion`, 1 `NewerVersionAvailable`. Existing compatible pinned versions retained.
- 1 `UnusedAttribute`: Recurring's XML accessibilityHeading is ignored below API 28. That unrelated placeholder was preserved.
- 1 `UnusedResources`: the existing brand_teal token is retained as the design reference; accessible theme foreground variants are used.

Reports: `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/androidTests/connected/debug/`, `app/build/reports/lint-results-debug.html`. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Visual/device checks

- Inspected light creation/detail and dark member management/Home during emulator UI tests. Rounded cards, teal theme, labels and current admin are visible; secondary destinations hide bottom tabs.
- Created a disposable review account and a real Trip group through the installed APK's UI. Verified creator/admin inclusion, visible detail and singular member count.
- Inspected Home and Detail at font scale 1.3; rotated Detail into landscape and scrolled it. No horizontal text clipping was observed. Original font/orientation settings were restored.
- Force-stopped and cold-relaunched the final APK: restored directly to Groups Home with the persisted Review trip and correct one-member count. The emulator is left signed in to that disposable review account.
- Screenshots in `docs/screenshots/milestone-4/` contain disposable test identities, not private account credentials. The dark screenshot predates only the singular/plural resource correction (its three-member label is unchanged).

## Limitations and authorization boundary

- No unresolved migration blocker. Repository methods are trusted in-process APIs: the UI gets its actor only from AuthService. Legacy raw accounting/member observation APIs still exist for the established persistence foundation and must not be exposed directly to future untrusted callers or new screens. New group screens use the authorized projections.
- Local username discovery is exact and case-insensitive, not a prefix directory. It intentionally reveals only public UUID/username. Lists are unpaginated for local use; there is no full historical membership event log, only preserved rows/original join/latest departure metadata.
- No last-activity column; no invented activity, totals or balances. Expenses and Balances remain labeled Milestone 5 placeholders; Recurring remains its existing placeholder. A sole admin must add another registered member and transfer ownership before leaving.
- API 24, physical-device and TalkBack exploration remain outstanding. Large-font checks cover Home/Detail at 1.3, not every form at every font size. Full docked-software-keyboard behavior is not claimed; the emulator uses its compact input toolbar.
- Firebase remains a future fresh-account phase. No cloud authorization rules, account migration or payment behavior were added.

## Files

Created:

- `model/MemberIdentity.java`, `model/GroupDetails.java`
- `ui/groups/GroupsViewModel.java`, `GroupScreenFragment.java`, `CreateGroupFragment.java`, `GroupDetailFragment.java`
- `res/layout/fragment_create_group.xml`, `fragment_group_detail.xml`, `item_group.xml`, `item_member.xml`, `view_member_search.xml`
- `src/androidTest/java/com/example/splitease/ui/GroupNavigationTest.java`
- This report and `docs/screenshots/milestone-4/`

Modified:

- `data/SqliteRepositories.java`, `repository/GroupRepository.java`
- `ui/MainActivity.java`, `ui/groups/GroupsFragment.java`
- `res/layout/fragment_groups.xml`, `res/navigation/main_nav_graph.xml`, `res/values/strings.xml`
- `src/androidTest/java/com/example/splitease/data/SqliteRepositoriesTest.java`
- `app/build.gradle` (version name only), `README.md`, `AGENTS.md`

Java/model/UI paths above are relative to `app/src/main/java/com/example/splitease`; resource paths are relative to `app/src/main`, and test paths to `app`.
