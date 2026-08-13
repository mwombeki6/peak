package com.mwombeki.peak.tenantmanagement.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

/**
 * Lets billing project a paid entitlement onto the module flags this module owns.
 *
 * It exists because `tenant_modules` belongs to tenantmanagement and
 * `DatabaseOwnershipArchitectureTests` holds every module to writing only its own tables.
 * That is not bureaucracy here: `can_access_module` reads these flags, so this is the
 * single place a commercial fact becomes a technical capability, and it should be reviewed
 * as such rather than scattered across whoever needed it.
 *
 * The methods are deliberately asymmetric. Enabling is conditional on never having been
 * activated before, so the reconciler cannot re-enable something an administrator turned
 * off; disabling is unconditional, because a lapsed subscription must actually revoke.
 */
@NamedInterface("api")
interface TenantEntitlementProjectionPort {
    /**
     * Turns a module on for the first time, and reports whether it did.
     *
     * Returns false when a row already exists, whatever its state — that means the tenant
     * has made a choice about this module and the reconciler must not overrule it.
     */
    fun activateModuleIfNeverActivated(
        tenantId: UUID,
        moduleId: String,
    ): Boolean

    /** Turns a module off. Unconditional: this is what makes an expired grant mean something. */
    fun deactivateModule(
        tenantId: UUID,
        moduleId: String,
        reason: String,
    ): Boolean

    /** Which modules are currently enabled, so the reconciler can diff rather than rewrite. */
    fun enabledModules(tenantId: UUID): Set<String>
}
