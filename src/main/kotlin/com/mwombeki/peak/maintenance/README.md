# Maintenance

Owns corrective requests, assignable work orders, verification, and explicit
out-of-service/out-of-order blocks. It never updates the room table directly;
all room transitions use `property::api`.

Public boundary: `maintenance::api`.
