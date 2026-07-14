# Recovery And Provider Degradation

## Staging gate

1. Validate the environment and rendered Compose topology.
2. Run migration mode against a backup clone, then run the API smoke checks.
3. Run `run-backup-restore-drill.sh`; retain its JSON evidence and both backup hashes.
4. Run `core-hospitality-journey` and `close-reporting` acceptance with signed
   simulators before protected provider credentials are used.
5. Record an explicit production approval. Staging success never implies
   authorization to deploy production.

## Migration failure

Do not edit or undo an applied migration. Stop API/worker rollout, preserve the
failed database and Flyway history, then choose one of these paths:

- before application traffic: restore the verified pre-deploy PostgreSQL and
  Keycloak backups, validate their hashes, and run the previous image;
- after traffic: retain the database and ship an additive forward fix using the
  next migration number.

Always re-run tenant counts, financial totals, report metadata, runtime-role
tests, and smoke checks. A failed migration is never repaired with `flyway clean`.

## Secret rotation

Introduce the new envelope/identity key as current and retain the prior key in
the documented previous-key variables. Restart one worker and one API instance,
verify decrypt/hash compatibility and provider health, then roll the fleet.
Remove the prior key only after every retained record has been rewrapped or its
retention window has elapsed. Rotate provider credentials independently and
revoke the previous credential only after signed health checks pass.

## Provider degradation

- Payment timeout: leave the transaction pending, let the bounded status-check
  event retry, and accept duplicate signed webhooks idempotently. Never post a
  manual success without provider evidence.
- Fiscal timeout: preserve the issued invoice, retry the fiscal submission, and
  require the documented audited override for checkout when permitted.
- Communication failure: retain the delivery request, bounded attempts and
  consent evidence; retry through the worker without logging recipient data.
- Object storage failure: fail report generation without publishing a signed
  link; retry after private-bucket health is restored.

Escalate on dead-letter growth, outbox age, night-audit lag, report-delivery
failures, or repeated provider circuit openings. The domain alert file contains
the authoritative alert names.
