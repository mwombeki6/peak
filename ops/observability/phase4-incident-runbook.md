# Phase 4 Department Operations Runbook

## Negative-stock or valuation incident

1. Stop retries for the originating receipt, transfer, or kitchen operation.
2. Query `stock_levels`, the append-only `stock_movements`, and the owning
   `inventory_movement_batches` command. Never edit a movement.
3. Use an audited positive or negative adjustment to correct a verified count.
4. Confirm quantity never became negative and average cost remains at six-decimal
   precision.

## KDS delivery incident

1. Verify API and worker readiness and the realtime journal health indicator.
2. Confirm the kitchen ticket and recipe snapshots committed. A missing WebSocket
   update must not be repaired by sending the order again with another operation ID.
3. Replay from the property realtime journal or reconnect the authenticated KDS.
4. Dead-letter only malformed events; retain ticket and stock evidence.

## Room readiness incident

1. Inspect the active room block and housekeeping task.
2. Do not directly set a released maintenance room to clean. Release must produce
   `vacant_dirty`, followed by cleaning and, when configured, independent inspection.
3. If checkout delivery is delayed, process the housekeeping outbox destination.
   The unique departure-stay key prevents duplicate clean tasks.
