# Disposable expense durability proof

Never deploy these Rules. No application integration. See
`docs/financial-expense-durability-poc.md` at the repository root for results,
measured budget limits and outstanding validation.

Requires Java compatible with Firestore Emulator 1.22.0, Firebase CLI, Python 3
standard library, and loopback Auth/Firestore emulators. No production project or
`google-services.json` is used. Rules require the explicit demo audience.

From this directory, regenerate the standalone Rules with `python3 generate.py`.
This reads the existing profile Rules without changing them. Start:

```sh
firebase emulators:start --only auth,firestore --project demo-splitease-expense-durability --config firebase.json
```

Run `python3 run.py`, then `python3 boundaries.py`, then `python3 safety.py`,
with external process timeouts of 180, 120 and 60 seconds respectively. They
stop a campaign on its first baseline blocker. Inspect each output's `status`;
process exit status alone is not a test result. Auth test accounts are reused
within the ephemeral session. `boundaries.py` assumes the main campaign has
created the diagnostic padding documents. No emulator export/import is needed.
Stop task-owned emulators afterward. Do not run against existing app emulators.

The harness reuses only the standard-library REST transport/codec in the earlier
`tools/financial-ledger-poc/run.py`, overriding the project to this demo ID.
Requests have 12-second HTTP timeouts and fixed loopback hosts. Financial actions
use real synthetic Auth emulator tokens. The emulator-owner bypass is restricted
to diagnostic fixture setup and read-only enumeration, not authorization claims.

Generated result files/debug logs are ignored. They contain only synthetic
fixtures, but no logs or account/token exports should be committed. The padding
fields and diagnostic documents are test instrumentation, not product schema.

The 10 November cost correction adds `cost_review.py` (120-second external
bound) and `authorization_cost.py` (90-second bound). Run them after the original
cohorts, on the same disposable emulator session. The latter restores fixture
names from their registered profiles, temporarily uploads a stronger membership
predicate **only to the fixed loopback demo emulator**, measures real group and
own-profile/public-identity access, then restores `expense.rules` in `finally`.
Its generated `authorization-measurement.rules` is not selected by firebase.json
and must never be deployed. Inspect `cost-results.json`/`authorization-results.json`
for individual outcomes and budget-vs-semantic classifications; a 403 alone is
not proof of the intended financial invariant. The current conclusion is
INCONCLUSIVE; see the report's correction section.
