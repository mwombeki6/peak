# Procurement

Owns tenant suppliers and property purchase orders, approval history, receipts,
and remaining quantities. Receiving delegates stock valuation and movements to
`inventory::api`.

Public boundary: `procurement::api`.

## Access control

Purchase order detail reads require `procurement.view`; purchase order mutations
require `procurement.manage` or the route-specific approval/receiving
permissions.
