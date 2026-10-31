# SCRUM-107: Backend production smoke preparation

## Plan and boundary

Inventory the twelve deployed services and their gateway health routes. Probe
each with verified TLS and bounded GET requests, record nearest-rank p50/p95/max,
check an optional protected route rejects missing credentials, and check an
optional browser origin on a GET response. Produce JSON/Markdown and preserve
every external acceptance gate as NOT_RUN until evidence is supplied.

This runner does not deploy, write business data, send notifications, authenticate
with Google, upload receipts, initiate payments or access other services' DBs.
It belongs to the Backend part of the ticket; Android release/camera/network
tests belong to the Flutter repository and devices.

## Configure

Python 3.12+, standard library only. The sample routes `/health/<service>` are
**proposed gateway mappings**, not proof that Nginx exposes them. Obtain the
actual AWS HTTPS origin, route map and deployed commit/image inventory from the
deployment owner first. Do not expose unrestricted actuator details publicly
merely to satisfy this test. A minimal authorized `{ "status": "UP" }` surface
is enough; protect it and set `tokenEnv` to a process environment variable name
when required. Tokens are sent only to the configured origin; redirects fail.

Copy `config.example.json` to an ignored `production.local.json`. Fill all twelve
health routes, a latency budget agreed for that environment and optionally:

- `protectedPath`: a side-effect-free GET route that returns 401 without JWT.
- `corsOrigin`: the exact permitted web client origin. This checks GET CORS only;
  browser preflight and credentialed CORS still require separate validation.
- `tokenEnv`: environment variable name containing an authorized health token.

Production requires HTTPS with the system certificate trust store. There is no
insecure TLS option. HTTP is permitted only for explicit loopback fixture scope.
No credentials, URL userinfo or query strings are allowed in the base origin.

```powershell
python -m unittest discover -s tools/production_smoke -v
python tools/production_smoke/smoke.py --config tools/production_smoke/config.example.json
# After supplying the actual authorized production URL/config:
python tools/production_smoke/smoke.py --config tools/production_smoke/production.local.json --execute
```

Default is NOT_RUN, with no network calls. Exit 2 means NOT_RUN/BLOCKED/FAIL.
Exit 0 means **PARTIAL**: the configured read-only probes passed, with outstanding
acceptance gates. Always inspect `overall`, `scope`, `checks`, and `externalGates`
in the report; exit 0 never means production-ready. `productionReady` remains
false. Missing optional checks stay NOT_RUN. A fixture run cannot count as a
production execution. Reports omit bodies, tokens, full URLs and private errors.

Each service gets 1..10 samples (default 3) with a <=10-second per-request
timeout, no retries and <=256KiB responses. p95 of three samples is the maximum;
this smoke observation is not a load test or a production latency SLA.
Failure of one service does not hide results for other services. DOWN, malformed
JSON, non-200 status, redirect, oversized body, TLS/network errors and excess
latency fail their probe. Protected-route GETs deliberately omit the token.

## Release evidence still required

Record service versions, full business flow/settlement results (SCRUM-91), real
VietQR acceptance, Google OAuth, FCM on a device, Android/camera release tests,
4G/5G device-network results and authorized production-log review. Associate
evidence with the exact deployed release in the team's release checklist.
The runner cannot verify these from workstation health calls and does not
accept a file claiming they passed. Full release sign-off remains external.

Commit groups: config/preflight; read-only probes/metrics; report generation;
HTTP fixture tests; runbook/CI. One PR for this Backend ticket, based on the
implementation stack, with missing production evidence explicitly stated.
