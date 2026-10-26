# Milestone 8 — Receipt scanning and attachments

Implemented and verified on 24 October 2026. Scope stops at M8; no Firebase uploads or local-data migration.

## Implementation

- Add/Edit Expense supports camera capture, private preview, retake, explicit detected-total review, manual correction, zoom/pan viewing, replacement and confirmed removal. Scanning never saves an expense or silently changes its amount.
- Bundled ML Kit Latin recognition runs on-device. Long receipts use overlapping vertical sections; row reconstruction joins amount/label columns. The pure-Java parser prioritizes GRAND TOTAL, NET AMOUNT, TOTAL PAYABLE and TOTAL, rejects conflicting candidates, and excludes subtotal, tendered cash, change and tax/item totals. Indian/international grouping converts to exact checked integer paise.
- Capture, EXIF normalization, compression, OCR and image decoding run off the UI thread. All eight orientations are normalized; re-encoding strips EXIF/GPS. JPEGs preserve aspect ratio and target 12 MP, 1,600 px short side, 16,000 px long side and 4 MiB. Extremely long captures that would become unreadable are rejected for retake.
- ReceiptStorageRepository exchanges logical UUID metadata and encoded bytes through asynchronous callbacks. ReceiptChange joins attachment publication/replacement/removal to the existing transactional expense/split save. The UI never sees persistent file paths or performs file/SQL operations.
- Schema **5** adds only receipt metadata, one optional expense link per object, ownership and group foreign keys. No image BLOBs or destructive migrations. Existing Expense models, exact accounting, auth, settlements and recurring logic remain in place.
- Immutable files live in app-private no-backup storage. A complete fsynced file precedes metadata publication; old objects are deleted only after commit. Rollback preserves the previous attachment and retryable draft. Cleanup retries orphan deletion on later writes/startup; abandoned drafts and camera captures expire after 24 hours. Linked objects never expire. Raw captures are deleted after processing/cancellation.
- Every receipt operation checks current membership. Unpublished drafts are creator-private; published historical receipts remain accessible to current members. Session changes clear presentation state and late/cancelled callbacks cannot publish drafts into a different editor/session.

## Automated evidence

Final command (project Gradle user home):

```bash
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug testDebugUnitTest connectedDebugAndroidTest lintDebug --no-daemon
```

**BUILD SUCCESSFUL** in 2m 40s. Unit XML: **28 tests, 0 failures/errors**. Connected XML: **48 tests, 46 passed, 2 skipped, 0 failures/errors**. The two skips are explicit cross-process fixture tests, not failed checks; M8's separate restart proof passed. The console's “Finished 50 tests” includes duplicated skip events; the authoritative JUnit XML contains 48 tests.

Lint: **0 errors, 12 pre-existing warnings**, no new M8 warnings: 9 GradleDependency, 1 AndroidGradlePluginVersion and 1 NewerVersionAvailable update advisory; 1 UnusedResources for existing brand_teal. Pinned dependency upgrades/unrelated resource changes were not made. Reports: app/build/reports/lint-results-debug.html, app/build/reports/androidTests/connected/debug and app/build/reports/tests/testDebugUnitTest.

Focused development checks passed before the aggregate run:

| Coverage | Evidence |
|---|---|
| OCR amount parsing | Five ReceiptTotalDetectorTest cases: payable labels, realistic subtotal/cash/change text, Indian grouping, wrapped amounts, ambiguity, malformed/negative/zero values and long overflow |
| Image quality/orientation | Three ReceiptImagingTest cases: eight EXIF orientations, metadata removal, invalid/extreme captures; a compressed 1,000 × 8,000 synthetic receipt retained its dimensions and real ML Kit found the bottom ₹1,234.56 total |
| Storage/integrity/authorization | Four focused SQLite tests: draft privacy, member/historical access, unauthorized access, atomic replacement/removal/deletion, injected transaction rollback, missing files, storage failure/retry, orphan cleanup, startup expiry and v4→v5 preservation |
| Expense UI | Three ReceiptNavigationTest cases: real Activity Result contract with intercepted camera output, real ML Kit, explicit suggestion use/manual correction, recreation, save/view/replace/remove/delete; cancellation; late OCR callback cancellation race |
| Process persistence | Explicit ReceiptColdRestartTest passed separately after actual `am force-stop` and relaunch. Process changed from PID 31167 to 31237. Restored session, UUID metadata, exact byte count and SHA-256 matched; viewer rendered the persisted image |

Camera interception supplies synthetic JPEGs through the actual FileProvider URI; it does not prove external camera hardware quality. The automated denied-state case forwards denial to the presentation layer; the actual Android permission dialog was additionally exercised below. Two opt-in cross-process fixture tests normally skip in the aggregate suite and are run separately with prepared fixtures (the M8 one passed as above).

To reproduce the separate cold-start proof after building/installing both APKs (ADB on PATH):

```bash
adb shell am instrument -w -e class com.example.splitease.ui.ReceiptNavigationTest -e keepReceiptFixture true com.example.splitease.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop com.example.splitease
adb shell am start -n com.example.splitease/.ui.MainActivity
adb shell am instrument -w -e class com.example.splitease.ui.ReceiptColdRestartTest -e verifyReceiptRestart true com.example.splitease.test/androidx.test.runner.AndroidJUnitRunner
```

This opt-in preparation leaves temporary accounts/group/receipt data in the debug app. Default tests do not retain an authenticated fixture. Gradle's connected runner removes its installation afterward; use direct ADB instrumentation for this two-process check.

## Observed emulator behavior

Device: Medium_Phone_API_37.0, Android 17 / API 37, arm64 emulator.

| Scenario | Result and evidence |
|---|---|
| Actual camera permission denial | PASS. Android's “Allow SplitEase to take pictures…” dialog was denied. The editor displayed permission guidance and retained manual expense entry and the previous attachment |
| Actual installed camera capture | PASS. Android Camera opened, shutter/review/Done returned a photo to the editor. The virtual scene had no payable total; a manual-entry message and receipt preview appeared without saving |
| Preview and viewer | PASS. New capture could be viewed; Back returned to the editor. Unsaved replacement was discarded |
| Light/dark | PASS. Viewer image, Back and zoom controls remained visible in both themes |
| Landscape/large fonts | PASS for receipt viewer and scrollable expense form at 1.3× font scale. Zoom and Back remained reachable. Device rotation/font/night settings were restored |
| Long-receipt legibility | PASS for the generated printed-text fixture, observed in the full-image viewer and recognized by ML Kit. Physical paper, glare/blur and camera hardware quality remain unverified |

Screenshots are in [screenshots/milestone-8](screenshots/milestone-8), including the system permission dialog/denial, actual camera preview, OCR suggestion, synthetic receipt viewer and responsive/theme checks. All account names and receipt content are temporary test fixtures.

## Future Firebase adapter

The stable contract is logical UUID + group/expense ownership + immutable JPEG bytes and metadata, with executor-delivered success/error callbacks. A future adapter stages an object before publishing its reference in a member-checked Firestore transaction; failed commits retain a retryable draft or compensate with deletion. Replacement/removal requires idempotent deferred object cleanup, version checks and abandoned-upload expiry. Firestore and Cloud Storage do not share a transaction.

Both Firestore and Storage rules must enforce current group membership, creator-private drafts, allowed file type/size and immutable group/expense identities. No public download URLs or client-only authorization. Firebase starts with fresh accounts/data; local credentials, groups, metadata and receipt files are not migrated. See README's adapter contract. No Firebase Auth/Firestore/Storage SDK or upload was added.

## Remaining limitations

- Bundled Latin OCR is advisory; handwriting, unsupported scripts, ambiguous layouts, poor lighting and blurred photos need manual correction. No confidence percentage or financial correctness guarantee is claimed.
- API 24 and physical-device camera checks, exhaustive accessibility/TalkBack coverage and manufacturer-specific camera behavior remain manual work. No gallery/import was added.
- Extreme receipt dimensions/size are rejected rather than silently reducing text to an illegible thumbnail. Full-image viewing is bounded, not a streaming tiled viewer.
- A file removed externally is reported unavailable with retry/retake/remove options. Private originals and drafts can survive interrupted work until the next startup cleanup; failed deletion can persist longer until retry succeeds.
- Storage tests use real SQLite and fault injection; they are not power-loss or device-disk failure simulations. Firebase adapter/security rules are design documentation only.

## Changed-file map

- `data`: additive v5 schema, existing SqliteRepositories integration and ReceiptFiles adapter.
- `model` / `repository` / `domain`: ReceiptMetadata, ReceiptImage, ReceiptChange, ReceiptStorageRepository, ExpenseRepository overload and ReceiptTotalDetector.
- `platform/receipts`: ReceiptImaging and ReceiptCaptureService.
- `ui`: existing ExpenseFragment/ViewModel integration, new receipt viewer/ViewModel/canvas, MainActivity guard and navigation/layout/string resources.
- App configuration: ML Kit/Exif dependencies, version 0.8.0, optional camera feature/permission, private cache-only FileProvider and application-owned services.
- Tests: parser, real image/OCR, SQLite integrity/migration, receipt navigation and opt-in cold restart. README/AGENTS and this report document the contract and evidence; screenshots contain synthetic test fixtures only.

## Receipt result presentation refinement — 26 October 2026

Presentation only: the Add/Edit Expense receipt result now uses the shared rounded card, a small “Detected amount” label, a prominent 36sp amount in the theme's accessible primary teal, and smaller caution text. The existing preview/buttons and all non-detection status messages remain. No changes to OCR, parsing, ViewModel state, storage or business rules.

Focused verification only: `assembleDebug assembleDebugAndroidTest` passed; the existing `ReceiptNavigationTest#scanReviewCorrectSaveViewReplaceRemoveAndDelete` passed on the API 37 emulator (1/1, 13.337s), including manual amount review/correction, recreation, editing, preview/viewing, replacement/removal and deletion. Its screenshot capture now brings the result into view in both themes. Light/dark screenshots were visually reviewed: [light](screenshots/milestone-8/detected-amount-refinement-light.png), [dark](screenshots/milestone-8/detected-amount-refinement-dark.png). No full suite or lint rerun for this small UI refinement; earlier aggregate results above are historical M8 evidence.

## Receipt amount confirmation — 26 October 2026

The separate amount-use button is replaced by a 56dp check control immediately beside the large amount inside the same rounded card. Confirmation fills the check with primary teal, changes its accessible description to “Amount applied”, and displays that label below the amount. The detected amount remains visible. Repeated confirmation is a no-op; any subsequent manual amount change clears confirmation, including when the user later types the detected value again. Confirmation survives same-ViewModel Activity recreation; new scans/session changes/removal reset it. With no valid detected result the check is hidden. No auto-save or OCR/parsing/storage/repository/accounting change.

Focused verification: app/test APK builds passed. Existing receipt UI scenarios `scanReviewCorrectSaveViewReplaceRemoveAndDelete` and `cameraDeniedOrCancelledKeepsManualEntryAndCreatesNoExpense` passed **2/2** on API 37 in 15.976s. Assertions cover initial/confirmed accessible descriptions, filling the exact amount, retained detection, repeated taps, no automatic expense creation, confirmation after recreation, manual-edit reset, explicit re-confirmation and hidden controls without a detection. Existing edit/replacement/removal/view/save flows passed in the same scenario. Emulator-only display override: 864 × 1920 at density 420 (approximately 329dp wide), font scale 1.3; original display/font settings were restored. Light/dark screenshots were visually reviewed. No full suite or lint rerun for this presentation-only refinement.

Screenshots: [unconfirmed light](screenshots/milestone-8/confirmation-unconfirmed-light.png), [unconfirmed dark](screenshots/milestone-8/confirmation-unconfirmed-dark.png), [applied light](screenshots/milestone-8/confirmation-applied-light.png), [applied dark](screenshots/milestone-8/confirmation-applied-dark.png).

## OCR accuracy investigation and improvement — 26 October 2026

### Baseline and supplied photograph

The unchanged implementation was run first on `IMG_1730.jpg` using real bundled ML Kit on the API 37 emulator. The correct visual payable amount is **₹701.40**: ₹668.00 plus ₹16.70 CGST and ₹16.70 SGST. The UPI payment matches ₹701.40; Due ₹0.00 means payment is complete.

| Supplied image | Original result | Improved result | Image evidence |
|---|---|---|---|
| IMG_1730.jpg | **₹668.00, incorrect suggestion** | **₹701.40, HIGH reliability, TOTAL** | Actual ML Kit image processing, not a text-only parser test; exact tax-component and independent payment agreement |

Baseline capture/normalization produced a 1,512 × 2,016 JPEG from the 3,024 × 4,032 source. Recognition read the final label as `Tota1` and amount as `7O1.4O`, but read the payment amount correctly. The parser's rigid label matching missed the final total and ranked pre-tax NET AMOUNT above generic TOTAL. It neither compared intervening taxes nor used payment evidence.

Initial full-resolution experiments also exposed duplicate/partial words at tile overlaps, creating extra numbers in reconstructed rows. Generated-image testing found decimal words split into `942.` + `82` and a grouping comma recognized as a dot. These were association/numeric interpretation failures, not reasons to pick the largest number or add receipt-specific exceptions.

Only one user photograph was supplied. Its image and raw transcript are **not repository fixtures or screenshots**. The opt-in ReceiptRealImageTest reads `cache/private-ocr-fixture.jpg`, provisioned through debug `run-as`; diagnostics are written to app-private cache for the test, never production logs. Reproduction requires privately supplying the photograph and `-e receiptImagePhase after`; it normally skips without that argument. The original baseline result is historical evidence captured before replacing the pipeline.

### Recognition and extraction

- ReceiptOcr reads the upright source independently of stored JPEG quality, with bounds checks and up to 16 MP. The attachment resize/compress/EXIF policy and repository contracts are unchanged. Originals still disappear after processing/cancellation.
- Recognition keeps source-scale glyphs in overlapping 2,000px tiles, stepping 1,648px on both axes, with one 45-second total deadline. No blanket downscale to 1,600px OCR width. Incomplete edge words are omitted; spatial containment removes partial duplicate words from overlap, and repeated equivalent observations are deduplicated.
- Pure-Java Line geometry carries word coordinates and slope; median skew compensation associates aligned columns. Close digit fragments following a decimal point are joined using geometry, rather than concatenating arbitrary quantities/prices. Wrapped standalone amount lines are allowed within a bounded vertical gap.
- Strict INR token parsing supports ₹/Rs/INR, attached prefixes, Indian/international comma grouping, two decimal places and `/-`. Dates, times, phone-like bare integers, IDs, negatives, zero payable amounts, percentages, malformed grouping and overflow are rejected.
- Limited lexical label normalization handles common OCR glyph confusions. Numeric O/I/L and dot-for-grouping repairs are permitted only in constrained currency/decimal tokens and require independently read payment or exact component agreement. Corrected results remain REVIEW. No amount or merchant-specific exceptions are in production code.
- No contrast/sharpening filter or new dependency was added. The source-scale and layout changes addressed measured defects; mild generated fading/skew passed without a filter. Severe blur/warping can still need manual entry.

### Deterministic ranking and reliability

| Evidence | Score/rule |
|---|---|
| Grand/Final Total, Net/Amount/Total Payable | Base 100 |
| Total Amount, Bill Amount, Amount Charged | Base 90 |
| Amount Paid / Paid Amount | Base 85; positive outstanding prevents treating a partial payment as the bill total |
| Net Amount | Base 80; tax/charge rows following it reduce unsupported intermediate amounts by 30 |
| Total | Base 70 |
| Label-associated value / currency / decimal | +8 / +4 / +3 |
| Independent matching payment | +25, once |
| Exact partial payment + outstanding | +30, once; outstanding itself is never the expense suggestion |
| Exact prior subtotal/net + taxes/charges − discounts | +40, once; total-tax summaries do not double-count detailed tax rows |
| Numeric OCR repair / bare generic integer | −20 / −20 |

Suggestions require score ≥78 and a ≥20 margin over a distinct competing amount. Repeated values merge without rewarding repeated observations. Conflicting explicit final totals require arithmetic resolution rather than payment alone. Unresolved strong labels, weak evidence, unsupported repairs or close competing amounts result in manual entry. HIGH requires score ≥100 without numeric repair; other accepted suggestions are REVIEW. These are deterministic rule classifications, **not calibrated probabilities**. The card retains its prominent amount and explicit check interaction; applying never saves, and manual edits still clear confirmation.

Rejected subtotal, tax/VAT, discount/coupon/MRP, cash tendered/change, quantity, previous-balance and loyalty fields are not final payable candidates. Debug-only details expose input coordinates/text, extracted amounts, scores and rejection/result reasons. They are opt-in UI, transient, excluded from saved view state, and never written to production logs. Release BuildConfig.DEBUG=false was checked and the release APK compiled.

### Measured coverage and limits

The synthetic corpus is a development regression set, not a held-out population sample. Counts below must not be interpreted as a general accuracy percentage.

| Evaluation layer | Correct suggestions | Incorrect suggestions | Manual/uncertain | Scope |
|---|---:|---:|---:|---|
| Synthetic OCR text | 21 | 0 | 18 | 39 labeled fixtures across restaurant, grocery, fuel, utility/broadband, pharmacy, hotel, parking, discounts/service charges, excess tender, zero due, conflicts, damaged text and missing totals |
| Generated image OCR | 12 | 0 | 3 | 15 generated image evaluations: 12 receipt-type renders, faded/skewed and obscured variants, and an EXIF rotation case, all through actual ML Kit |
| User photograph, original | 0 | 1 | 0 | Original scanner displayed an incorrect suggestion; it had no explicit reliability class |
| User photograph, improved | 1 | 0 | 0 | Same photo; HIGH result verified against the visible payable total |

There were no incorrect HIGH or REVIEW suggestions in the measured final fixture set. Expected manual cases include absent/conflicting/obscured totals, unsupported numeric repairs, tax-only fields, invalid amounts and incomplete payment information. Generated faded/skewed text and a single real photo do not establish physical-camera, arbitrary thermal-printer or severe-damage accuracy.

Final focused build/check command:

```bash
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug assembleDebugAndroidTest assembleRelease testDebugUnitTest --tests '*Receipt*Test' lintDebug --no-daemon
```

BUILD SUCCESSFUL in 38s; **9/9 parser unit tests** passed (5 existing + 4 corpus/geometry/privacy cases). Lint remains **0 errors, 12 existing warnings**: 9 GradleDependency, 1 AndroidGradlePluginVersion, 1 NewerVersionAvailable and 1 UnusedResources. No dependency upgrades, new libraries, migrations or storage-contract changes.

Direct ADB instrumentation on Medium_Phone_API_37.0 / Android 17: **14/14 focused tests passed**, 57.473s, no skips. This includes ReceiptOcrTest (3), the explicit private ReceiptRealImageTest (1), ReceiptImagingTest (3), ReceiptNavigationTest (3), and the four existing receipt SQLite authorization/atomicity/cleanup/migration tests. The UI tests use the Activity Result/FileProvider camera contract with intercepted JPEG output, actual ML Kit and real local storage; they do not prove physical-camera quality. Permission denial/cancellation, stale-result cancellation, manual entry, amount application/reset/repeated taps, recreation, preview/view/replace/remove/delete and private debug-panel toggling passed. The existing 1,000 × 8,000 long-receipt OCR regression and all eight attachment EXIF orientation cases also passed. These legacy regressions/UI captures are not counted again in the declared evaluation-corpus table above.

Light/dark result screenshots were reviewed, retaining the large amount/check and adding a secondary supporting-label/reliability line: [light](screenshots/milestone-8/ocr-improvement/review-light.png), [dark](screenshots/milestone-8/ocr-improvement/review-dark.png), [applied](screenshots/milestone-8/ocr-improvement/applied-light.png). Only synthetic images/accounts appear in committed screenshots. The private evaluation photo and raw reports were removed from the emulator/test scratch files after verification; the user's original photograph is untouched.

A full unrelated application suite was not rerun. API 24, physical thermal-receipt capture, other manufacturers/scripts and exhaustive accessibility remain unverified in this task. Only one real photograph was available, so this work does not establish an unbiased real-world accuracy rate. No unrelated milestone or Firebase work was performed.
