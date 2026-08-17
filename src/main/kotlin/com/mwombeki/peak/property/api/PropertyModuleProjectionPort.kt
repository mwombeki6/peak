package com.mwombeki.peak.property.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

/**
 * The per-property half of projecting a paid entitlement onto module flags.
 *
 * Separate from the tenant-level port because add-ons are sold per property: a group may
 * buy POS for two of its three hotels, and `property_modules` is where that distinction
 * lives. Same asymmetry — activation only where nothing has been decided, deactivation
 * unconditional.
 */
@NamedInterface("api")
interface PropertyModuleProjectionPort {
    fun activateModuleIfNeverActivated(
        tenantId: UUID,
        propertyId: UUID,
        moduleId: String,
    ): Boolean

    /** Turns a module off for every property of a tenant. Used when a grant lapses. */
    fun deactivateModuleEverywhere(
        tenantId: UUID,
        moduleId: String,
    ): Int

    /** Turns a module off for the properties a grant no longer covers. */
    fun deactivateModuleExcept(
        tenantId: UUID,
        moduleId: String,
        keepPropertyIds: Collection<UUID>,
    ): Int

    fun propertiesWithModuleEnabled(tenantId: UUID, moduleId: String): Set<UUID>
}
