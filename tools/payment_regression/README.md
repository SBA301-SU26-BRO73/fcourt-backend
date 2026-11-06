# SCRUM-109 — booking/payment regression evidence

Run from the repo root with Java 21, Maven wrapper network access and Docker:

```sh
python3 -m unittest discover -s tools/payment_regression -v
python3 tools/payment_regression/run.py          # NOT_RUN, no suites executed
python3 tools/payment_regression/run.py --execute
```

The runner executes `clean verify` for hold, booking and payment. Every module must
produce nonempty Surefire XML with no failed, errored or skipped cases. Critical
named cases must be present: competing clients for the last slot, atomic selection,
release ownership, TTL expiry, cancellation rollback/idempotency, oversized/spoofed
receipts, in-flight expiry, competing uploads, duplicate/parallel decisions,
customer/branch isolation and refund uniqueness. Missing/renamed coverage fails
rather than quietly counting unrelated tests as evidence. Modules continue after a
failure to expose the full regression picture. A module timeout is 15 minutes.

Read `target/payment-regression/report.json`; logs stay in the same ignored local
directory. Reports include tested Git revision, scope, elapsed time, executed test
counts and mapped critical cases. They never include tokens, receipt bytes or bank
details. Treat raw Maven logs as private: keep access limited before sharing.
No live service, external provider or real financial record is touched by this
runner; containers and mocked upstream/provider integrations scope the result.

`overall=PASS` means **local service regression only**. `productionAcceptance=false`
and external gates remain NOT_RUN even when all suites pass. The task cannot be
accepted as production regression without SCRUM-100 deployment, SCRUM-107 smoke,
authorized production fixtures and the integrated full-court workflow.

## Production acceptance still required

Record the exact deployed revision/images, smoke report, time, device/app release,
network and authorized fixture IDs in an access-controlled evidence location.
Run normal booking→receipt→approval→ticket, rejection/expiry/rebooking and refund
with the product owner. Check database/event/ledger reconciliation through service
APIs or authorized operations tooling; do not query another service's database
from application code. Never treat a fabricated receipt as a real bank transfer.

On two clients, check the final free slot and verify only one hold succeeds; under
an interrupted upload near expiry, verify receipt/payment/outbox state consistently
reconciles. Replay confirmation and verify stable amount/code with one financial
transition. Check >5MiB at the real gateway and servlet (local service validation
alone does not prove Nginx behavior). Confirm customer/branch access boundaries,
full-court conflict handling after SCRUM-114, real CDN image behavior and modal
readability. Measure latency on the specified Android/4G setup; no local suite
establishes the ticket's <1s image target or 100% reliability claim.

Do not change Cloudinary source URLs with an assumed transformation: signed URLs,
provider configuration and full-resolution evidence retention need an agreed
contract. Keep originals for payment review; image optimization needs measured
provider/device evidence before changing acceptance behavior.
