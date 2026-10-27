# Milestone 9A — Firebase foundation

Verified 27 October 2026. Scope stops at SDK/configuration foundation; local authentication and SQLite remain active.

## Configuration and implementation

- The supplied `app/google-services.json` exists, is unchanged, and contains a matching `com.example.splitease` Android client, app ID, API key and project information for `splitease-9acc8`. No fake config was generated. The file is now ignored by Git; supply the real file locally/through CI. Generated resources and runtime FirebaseOptions were compared without printing the API key.
- Pinned Google Services plugin **4.5.0**, Firebase BoM **35.1.0**, Authentication **25.0.0** and Cloud Firestore **27.1.0**. These stable versions resolved/built with existing AGP 9.2.0 / Gradle 9.4.1 / JDK 25 daemon, Java 17 source, compile/target SDK 37 and minSdk 24.
- Default FirebaseApp initializes through the SDK's merged FirebaseInitProvider before Application.onCreate. No parallel initializer or backend adapter was added. SplitEaseApplication, LocalAuthService, sessions, SQLite repositories/schema 5 and receipt storage remain unchanged. Internet permission is explicit.
- Debug Profile adds a manual configuration/reachability card. Local getters verify default app/options and Auth/Firestore client association. One unauthenticated HTTPS HEAD to the fixed `https://firestore.googleapis.com/` root checks reachability; no API key, project identity, local account, credentials or document path is sent. Timeouts and a worker keep it off the UI thread; LiveData/view-lifecycle ownership and worker shutdown prevent stale view updates. No automatic polling.
- Release uses a source-set no-op hook. APK ZIP inspection confirmed the diagnostic layout is absent; javap confirmed the release `attach` method contains only `return`, with no probe or diagnostic SDK getters.

## Checks and outcomes

| Check | Result / evidence |
|---|---|
| Package/config processing | PASS — Google Services processing, generated `google_app_id`/`project_id`, and runtime FirebaseOptions agree |
| SDK initialization | PASS — default FirebaseApp existed before diagnostic getters; Auth and Firestore clients refer to that same app |
| Endpoint connectivity | PASS — emulator received **HTTP 404** over HTTPS. This is host reachability, not a successful Firestore document/API request |
| Existing local backend | PASS — AuthService is LocalAuthService; UserRepository is SqliteRepositories; FirebaseAuth.currentUser remained null |
| Local authentication/navigation | PASS — signup/login/logout, profile/UPI persistence, all three bottom tabs, Back/auth guards, account switching, missing/invalid sessions and same-account Activity recreation |
| Focused instrumentation | **7/7 passed**, 21.859s: FirebaseFoundationTest (2), existing NavigationTest (2), existing LocalAuthenticationTest (3); Medium_Phone_API_37.0 / Android 17 emulator |
| Milestone builds/unit/lint | **BUILD SUCCESSFUL**, 45s; debug + unsigned release APKs, **32/32 unit tests**, lint **0 errors, 12 existing warnings** |
| Dependency audit | Auth/Firestore present; no Analytics, Cloud Storage or Cloud Functions SDK in debugRuntimeClasspath. BoM constraints do not activate unused products |
| Cloud accounts/documents/rules | No Firebase sign-in/account creation, collection/query/document operations, database provisioning or rules deployments performed |
| Billing/Spark status | **NOT VERIFIED from console** — JSON and SDK initialization do not encode billing plan; no authenticated console/management access was available. No billing, payment method or plan settings were changed |

Final milestone command:

```bash
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug :app:dependencies --configuration debugRuntimeClasspath --no-daemon
```

Focused emulator execution used direct ADB installation with `-r` (no clear-data/uninstall) and AndroidJUnitRunner for `com.example.splitease.firebase.FirebaseFoundationTest,com.example.splitease.ui.NavigationTest,com.example.splitease.auth.LocalAuthenticationTest`. Only temporary local test accounts were registered; none were cloud accounts. Lint warnings remain 9 GradleDependency, 1 AndroidGradlePluginVersion, 1 NewerVersionAvailable and 1 UnusedResources. No unrelated dependency upgrades were made.

The debug Profile test was additionally run with `-e responsiveFirebase true`: **1/1 passed**, 7.076s, using an 864 × 1920 emulator display at density 420 (approximately 329dp portrait width), 1.3× font scale, landscape and dark theme. Screenshots were reviewed: [light result](screenshots/milestone-9a/profile-debug-light-large.png), [landscape/dark panel](screenshots/milestone-9a/profile-debug-landscape-dark-large.png). The Profile remains vertically scrollable; long debug text may require scrolling in landscape. Original display/font settings and app theme were restored. This extra targeted layout run does not change the seven unique focused tests above.

## Cost and access boundaries

Spark/no-billing restrictions remain mandatory. This milestone does not enable services, attach billing, upgrade to Blaze, create cloud users/data, add Analytics/Storage/Functions, or deploy rules. SDK construction and the project-agnostic HEAD probe do not issue billable document operations. A JSON `storage_bucket` identifier does not add/provision Cloud Storage.

The project owner must independently confirm the existing project is on **Spark**, without adding billing/payment methods, before later cloud feature activation. SDK initialization proves only local configuration. Endpoint reachability proves neither project availability nor enabled providers/database, credentials, security rules or authenticated Firestore access. Those checks are deliberately outside this milestone's no-cloud-data scope. No claim of authenticated access or console-verified Spark status is made.

## Future integration and limitations

Future AuthService/repository adapters must be selected in the composition root only in an authorized later milestone. Preserve asynchronous callbacks, serial executors/cancellation/observations, checked integer-paise domain logic and current membership authorization. Firebase UID-to-canonical-domain-UUID mapping must be designed explicitly; SDK UIDs must not be blindly passed into existing UUID-validated contracts. SDK types/queries stay out of UI/domain/contracts. Fresh cloud accounts/data only; no migration of local accounts, SQLite records or receipt files. ReceiptStorageRepository remains local/unchanged; the historical M8 Cloud Storage design is not authorized under current Spark restrictions.

No Firebase auth replacement, Firestore repository, payment, recurring, expense or OCR changes. API 24 and physical-device coverage remain outstanding. Offline/blocked networks may show unreachable while the SDK/local application remain usable. API-key backend restrictions, project provider/database enablement and Firestore authorization have not been exercised.

Official version/setup references: [Firebase Android setup](https://firebase.google.com/docs/android/setup), [Android release notes](https://firebase.google.com/support/release-notes/android), [Google Services configuration](https://firebase.google.com/docs/android/google-services-plugin-and-file), [Spark pricing](https://firebase.google.com/pricing).
