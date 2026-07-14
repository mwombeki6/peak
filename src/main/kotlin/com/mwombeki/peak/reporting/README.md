# Reporting

Owns report catalog access, settings, subscriptions, deterministic generation,
private object metadata, signed download links, delivery state, retry, and
retention cleanup. Reporting reads immutable Night Audit close snapshots through
`NightAuditCloseSnapshotPort` and sends links through
`ReportLinkDeliveryPort`; it does not mutate either owner module's tables.

Externally visible reporting states remain uppercase for V1 compatibility.
Artifacts remain private, content-addressed, bounded by retention policy, and
are deleted only after a claimed cleanup records completion provenance.
