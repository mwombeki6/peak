# Inventory

Owns inventory items, property locations, recipes, stock snapshots, append-only
movements, transfers, and weighted-average valuation. All outgoing operations
lock stock and reject negative balances.

Public boundary: `inventory::api`. POS consumes recipes and Procurement receives
stock only through that API.

## Access control

Inventory item, location, stock, movement, and recipe reads require
`inventory.view`. Location and recipe mutations require `inventory.manage`;
stock-affecting adjustments require `inventory.adjust`.
