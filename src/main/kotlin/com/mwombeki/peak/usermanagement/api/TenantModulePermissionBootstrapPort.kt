package com.mwombeki.peak.usermanagement.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

/**
 * Gives a newly activated module's permissions to the tenant's administrator role.
 *
 * Without this, buying a module is invisible. `can_access_module` requires both that the
 * module is enabled *and* that the user holds a permission within it, so flipping
 * `tenant_modules.is_enabled` on its own changes nothing anyone can see: the customer pays,
 * the flag moves, and the screens stay exactly as absent as before.
 *
 * The permission set is derived from `module_access_matrix` rather than listed here, so the
 * same table the route guard consults decides what a module grants. A hardcoded list would
 * drift the first time someone added a route.
 */
@NamedInterface("api")
interface TenantModulePermissionBootstrapPort {
    /**
     * Grants every permission the module's routes require to the tenant-admin role.
     *
     * Additive and idempotent: it never removes a permission and never overwrites one, so
     * running it repeatedly is a no-op and it cannot undo a deliberate operator decision.
     * Returns how many role grants were newly created.
     */
    fun bootstrapModulePermissions(tenantId: UUID, moduleId: String): Int
}
