# Housekeeping

Owns property housekeeping settings, room-clean tasks, independent inspections,
and lost-and-found custody. Room state changes use `property::api`; departure
tasks are created idempotently from front-desk outbox events.

Public boundary: `housekeeping::api`.
