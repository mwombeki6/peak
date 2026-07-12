# Inventory

Owns inventory items, property locations, recipes, stock snapshots, append-only
movements, transfers, and weighted-average valuation. All outgoing operations
lock stock and reject negative balances.

Public boundary: `inventory::api`. POS consumes recipes and Procurement receives
stock only through that API.

## Access control

Inventory location detail reads require `inventory.view`; location mutations
require `inventory.manage`.
