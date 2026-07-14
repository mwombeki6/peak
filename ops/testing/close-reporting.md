# Close and Reporting Acceptance

Run `ops/testing/run-close-reporting-acceptance.sh`. This extends the core
hospitality journey with object storage, night audit, reporting, delivery, and
recovery checks using the production topology.

The runner verifies:

- closed-but-unsettled POS behavior and unpaid-checkout protection;
- canonical close business date, exact financial totals, snapshot hashes, and
  document sequencing;
- report generation/retry and outbox replay without duplicate delivery;
- PDF `%PDF` magic, SHA-256 metadata, private storage, signed-link expiry, and
  recipient consent;
- fiscal/payment timeout, duplicate notification, reversal, and retry paths;
- audit and outbox records created atomically with the owning command.

Evidence is written to `build/evidence/close-reporting`. The report contains no
credentials, bearer tokens, provider secrets, recipient addresses, or signed
URLs.
