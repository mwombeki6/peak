-- Production remediation: permit only the editable relationship deletes used by
-- reservation commands. Night-audit history remains append-only.
GRANT DELETE ON TABLE
    reservation_guests,
    reservation_room_nights
TO pms_app;

REVOKE DELETE ON TABLE night_audit_issues FROM pms_app, pms_worker;
