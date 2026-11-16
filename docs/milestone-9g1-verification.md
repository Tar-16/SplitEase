# Milestone 9G.1 device-local cloud receipts

16 November 2026, Asia/Kolkata. The named Firebase demo graph injects the
device receipt adapter into the expense editor and receipt viewer. Release
and ordinary debug navigation continue to use the SQLite path.

The adapter uses the existing private JPEG file implementation and an atomic
index in a separate no-backup namespace. Each link records the Firebase UID,
group UUID and expense UUID. A linked read requires the confirmed auth session,
the matching SDK UID, current server membership and a server-confirmed active
expense. A draft requires the owner and current group membership. Stored
images are private to the device and account. The editor labels that limitation.

The existing camera, compression, ML Kit OCR, diagnostics and amount confirmation
are shared. Firestore confirms the financial save before the adapter updates the
device index. If that local operation fails, the editor reports the partial
outcome and returns to the expense list. A failed financial save leaves the old
link intact. The local index has no cloud publication state. Firestore expense
documents, Security Rules and the financial security hold are unchanged.

Cleanup removes detached JPEG files and expires drafts after 24 hours. A
confirmed deletion from this device removes matching links. A remote tombstone
is reconciled when an authorized lookup or load next checks the saved expense.
Unverified links are retained after network or membership failures. There is no
background sweep of remote deletes. A crash between the financial save and
index publication can leave an expense saved without the new attachment. The
adapter does not attach a draft based on an ambiguous server result.

Future providers can reuse the encoded-image and logical-metadata contract.
Shared availability would require independently authorized provider objects and
a verified publication step. The device index must never stand in for that
state. There were no changes to production Rules or Firebase configuration.
Verification used Firebase Auth/Firestore emulators. No deployment, billing,
Cloud Storage, Cloud Functions or application data migration was performed.

## Verification

- One bounded Gradle run passed debug, release and Android test APK assembly,
  all **64 unit tests**, and lint with **0 errors and 1 existing unused
  `brand_teal` resource warning**.
- Three generated-image ML Kit instrumentation cases passed on Android API 37,
  including degraded text and orientation coverage.
- Three existing SQLite receipt UI instrumentation cases passed. They covered
  the simulated camera result contract, permission denial, cancellation during
  processing, OCR diagnostics, explicit amount confirmation, manual correction,
  Activity recreation, viewing, replacement, removal and deletion.
- The first cloud association case saved and loaded a link, but reopening raced
  the initial asynchronous AuthService subscription. The adapter now waits for
  its initial session callback before checking the requested actor. The affected
  debug and test APKs and lint passed again. Only the cloud case was selected
  for the follow-up, which passed in **3.902 seconds**. It covered cross-group
  draft rejection, private association and reopen, keep and replacement,
  preservation on stale edit, absence of Firestore receipt metadata, switching
  accounts on the same SDK client, missing-file recovery and remote deletion
  cleanup after a confirmed lookup.

A physical camera/device, cross-device availability and process death during a
cloud save were outside this run. The cloud adapter test reopens the repository.
The full cloud UI journey was not run separately. The SQLite navigation tests exercise Activity recreation and the shared OCR UI
with simulated camera results.
