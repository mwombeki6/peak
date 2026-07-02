UPDATE module_access_matrix
SET is_enabled_by_default = false,
    notes = 'Disabled: public Booking Engine is outside the frozen V1 scope',
    updated_at = now()
WHERE module_id = 'booking_engine'
  AND route_scope = 'public_property';
