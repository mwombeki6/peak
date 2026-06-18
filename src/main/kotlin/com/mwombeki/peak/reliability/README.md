# Reliability Module

Reliability owns idempotency, transactional outbox persistence, and worker dispatch. Business modules use the published ports and do not implement their own duplicate-command or async-delivery tables.

## Public Ports

- `IdempotencyPort`
- `OutboxPort`
- `OutboxEventHandler`

## Runtime Model

- API runtime records idempotency keys and enqueues outbox events inside the business transaction.
- Worker runtime claims, dispatches, retries, reclaims stale locks, and dead-letters outbox events.
- `OutboxWorkerLifecycle` runs only when `peak.runtime.mode=worker`.

## Concurrency Rules

- Claiming is database-coordinated; workers must not process the same event concurrently.
- Handlers must be idempotent because retries and recovery are expected.
- Retry delay, batch size, worker id, and stale-lock recovery must stay observable and configurable.

## Production Rules

1. Require `Idempotency-Key` on unsafe externally triggered commands.
2. Keep idempotency reservation, business mutation, audit, and outbox enqueue in one transaction.
3. Dispatch side effects from the worker, not the request thread, unless the provider contract requires synchronous behavior.
4. Dead-letter events only after recording enough error context to diagnose the failure.
