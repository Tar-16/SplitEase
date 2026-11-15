# Bounded financial posting POC — ownership-fixed emulator proof

**Disposable emulator-only proof. Do not deploy or integrate.** The earlier global
record race is retained as historical evidence in
`../../docs/financial-posting-poc-results.md`. Current Rules use group-scoped heads,
exclusive pending commands before posting, actual-head checks and strict
publication revisions. Five focused ownership and seven affected cohorts passed
on 2026-11-09. This supports the next isolated security stage, not production.

Uses the existing Firebase CLI, Java and Python standard library. Reuses only the
REST transport/value codec from `../financial-ledger-poc/run.py`. No Android app,
new npm dependencies, live credentials or production configuration is needed.

- Project: `demo-splitease-posting-poc`; Auth 9199, Firestore 8180, hub 4410,
  logging 4510; loopback only. Websocket 9150 must also be free.
- Rules require the reserved demo audience. `diag`/`expr`, `probes` and `access`
  are diagnostic facilities, not product permissions/schema.
- Start from a **fresh disposable emulator session**. Fixture setup creates/reuses
  twelve synthetic accounts with deterministic emulator-only emails/passwords.
  No emulator state is exported; restarting loses the in-memory fixture data.

From this directory, start the existing CLI:

```
firebase emulators:start --only auth,firestore --project demo-splitease-posting-poc --config firebase.json
```

From the workspace root, use an external subprocess timeout for each command:

```
python3 tools/financial-posting-poc/ownership_tests.py
python3 tools/financial-posting-poc/run.py
python3 tools/financial-posting-poc/audit.py
```

Caps used: focused ownership 120 seconds; affected seven-cohort harness 180;
read-only audit 30. Stop at a fundamental failure. `cross_group_race.py` can run
just the scoped collision regression, but is already included in the focused
cohorts and need not be repeated. Both groups may publish the same UUID only at
distinct paths; unchanged first-group history and rejected foreign/global writes
are required. Same-group duplicate/conflicting claims must be rejected.

All financial assertions use regular Auth Emulator tokens. Emulator-owner bypass
is limited to diagnostic probe setup and fixture enumeration/readback; no
successful financial operation is authorized through owner bypass. Accounts
persist after leaving; there is no lifetime member vector or slot recycling.
PREPARE claims can abort without changes; APPLY must finish through verified
steps and can be resumed by another current member.

Artifacts go to `/tmp/posting-owner-*` and `/tmp/posting-poc-*`, including synthetic coverage context. Never
commit real identities/tokens/receipts or change hosts/projects to live values.
Only stop positively identified task-owned emulator processes. The POC omits full
production models/profile privacy, username registry, admin transfer, UI, local
data, SDK restoration and migration. Its staged protocol is multiple atomic steps,
not one atomic user operation; pending account values are provisional.
