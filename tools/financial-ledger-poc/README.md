# Disposable financial ledger Rules proof

**Blocked proof, not application infrastructure or deployable Rules.** See
`../../docs/financial-ledger-poc-results.md`. The first valid eight-way expense
hits the emulator's 1,000-expression limit. Dependent tests stop there. Do not
integrate or deploy this directory.

Uses Python's standard library, existing Firebase CLI, Java and actual Auth/
Firestore emulators. No Android device, new npm dependency or live config is
needed. All clients target fixed loopback URLs. Rules require the reserved
`demo-splitease-ledger-poc` token audience. Auth: 9199; Firestore: 8180;
hub: 4410; logging: 4510. Do not run alongside another emulator using websocket
port 9150. Check for existing processes before starting/stopping anything.

From this directory, regenerate with `python3 generate.py`, then start the
existing Firebase CLI with:

```
firebase emulators:start --only auth,firestore --project demo-splitease-ledger-poc --config firebase.json
```

From the workspace root run `python3 tools/financial-ledger-poc/run.py` with an
external 180-second subprocess timeout. It exits nonzero at a valid-operation
failure, marks dependent cases NOT_RUN and only finishes independent diagnostic
limit probes. Results and coverage go to `/tmp/ledger-poc-*`. Auth users are
synthetic and reused when available; no fixture is exported. Restarting the
disposable server clears its in-memory Firestore data. Never supply live hosts,
tokens, project IDs, service accounts or deployment commands.

`generate.py` produces fixed, unrolled eight-slot `ledger.rules`; no application
Rules are read or overwritten. `run.py` uses normal Auth Emulator ID tokens for
assertions. Emulator-owner bypass is limited to probe-document setup, a legacy
departure fixture (not reached in this run) and diagnostic reads/account-list
setup. It is never used to make an asserted financial write succeed.

The POC combines expense/settlement heads in a `records` collection and uses a
minimal owner-written UID registration mirror. It does not prove production
profile/username integration, membership mutation/leave, app lifecycle, privacy
of actual receipt data, or live Firestore behavior. Instrumentation probe
collections intentionally do not model product permissions; they are solely
engine-limit diagnostics. Keep the application's current financial hold.
