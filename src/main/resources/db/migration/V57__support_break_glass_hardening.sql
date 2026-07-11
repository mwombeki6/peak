-- ================================================================================
-- Support break-glass lookup hardening
-- ================================================================================

CREATE INDEX IF NOT EXISTS idx_platform_break_glass_active_action
    ON platform_break_glass_access (
        platform_user_id,
        tenant_id,
        action_code,
        starts_at,
        expires_at
    )
    WHERE status = 'active'
      AND approved_by IS NOT NULL
      AND approved_at IS NOT NULL
      AND activated_at IS NOT NULL
      AND revoked_at IS NULL;

COMMENT ON FUNCTION can_platform_admin_access_tenant(uuid, uuid, text) IS
    'Checks platform permission and active approved tenant-specific break-glass access for support operations.';

COMMENT ON TABLE platform_break_glass_access IS
    'Audited tenant-specific support access grants. Support identities must pass can_platform_admin_access_tenant before tenant-targeted platform operations.';
