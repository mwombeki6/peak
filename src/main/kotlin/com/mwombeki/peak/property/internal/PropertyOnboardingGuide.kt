package com.mwombeki.peak.property.internal

import com.mwombeki.peak.property.api.OnboardingNextAction
import com.mwombeki.peak.property.api.OperatorBlockerView
import com.mwombeki.peak.property.api.PropertyGoLiveBlockerView
import java.util.UUID

/**
 * Turns go-live evidence into the one hotel-fixable HTTP call a wizard should
 * make now. Peak deployment facts (SMS routing) are [operatorBlocker], never
 * a hotel [OnboardingNextAction].
 */
internal object PropertyOnboardingGuide {
    fun nextAction(
        tenantId: UUID,
        propertyId: UUID,
        currentStep: String?,
        isReady: Boolean,
        workflowStatus: String,
        blockers: List<PropertyGoLiveBlockerView>,
    ): OnboardingNextAction? {
        if (workflowStatus == "activated") {
            return null
        }
        if (isReady) {
            return activate(propertyId)
        }
        return when (currentStep) {
            STEP_STRONG_MANAGER -> strongManager(tenantId, propertyId)
            STEP_INVENTORY -> inventory(propertyId, blockers)
            STEP_FRONTLINE -> frontline(tenantId, propertyId)
            STEP_GO_LIVE -> activate(propertyId)
            STEP_PROPERTY_DISTINCT -> null
            STEP_SMS -> null
            else -> blockers.firstOrNull()?.let { inventory(propertyId, blockers) }
        }
    }

    fun operatorBlocker(
        smsStep: PropertyGoLiveEvaluator.EvaluatedStep?,
    ): OperatorBlockerView? {
        if (smsStep == null) {
            return null
        }
        val inScope = smsStep.evidence["inScope"] == true
        val routable = smsStep.evidence["smsRoutable"] == true
        if (!inScope || routable) {
            return null
        }
        return OperatorBlockerView(
            code = STEP_SMS,
            title = "Peak must route staff SMS (Beem)",
            why = "Staff activation SMS is Peak deployment config " +
                "(PEAK_COMMUNICATION_ROUTING_SMS). The hotel cannot set this. " +
                "Peak ops must route Beem before POS cashiers can receive " +
                "activation messages. This does not block property activate.",
        )
    }

    private fun strongManager(tenantId: UUID, propertyId: UUID) = OnboardingNextAction(
        step = STEP_STRONG_MANAGER,
        title = "Assign a Keycloak Property Administrator",
        why = "A Keycloak-linked Property Administrator must be assigned. " +
            "POS cashier PIN login is for tills, not property activate.",
        method = "POST",
        path = "/api/v1/tenants/$tenantId/properties/$propertyId/administrators/{userId}/assign",
        bodyHint = emptyMap(),
    )

    private fun inventory(
        propertyId: UUID,
        blockers: List<PropertyGoLiveBlockerView>,
    ): OnboardingNextAction {
        val code = blockers.firstOrNull { it.stepKey == STEP_INVENTORY }?.code
            ?: blockers.firstOrNull()?.code
        return when (code) {
            "inventory.building" -> OnboardingNextAction(
                step = STEP_INVENTORY,
                title = "Add a building",
                why = "A hotel needs at least one building before rooms can exist.",
                method = "POST",
                path = "/api/v1/properties/$propertyId/buildings",
                bodyHint = mapOf("name" to "Main building"),
            )
            "inventory.floor" -> OnboardingNextAction(
                step = STEP_INVENTORY,
                title = "Add a floor",
                why = "Rooms sit on a floor of an existing building.",
                method = "POST",
                path = "/api/v1/properties/$propertyId/floors",
                bodyHint = mapOf(
                    "buildingId" to BUILDING_ID_HINT,
                    "floorNumber" to 1,
                    "name" to "Ground floor",
                ),
            )
            "inventory.room_type" -> OnboardingNextAction(
                step = STEP_INVENTORY,
                title = "Add a room type with a positive rate",
                why = "Guests book a room type. basePrice must be greater than zero.",
                method = "POST",
                path = "/api/v1/properties/$propertyId/room-types",
                bodyHint = mapOf(
                    "name" to "Standard",
                    "code" to "STD",
                    "basePrice" to 80_000,
                ),
            )
            "inventory.room" -> OnboardingNextAction(
                step = STEP_INVENTORY,
                title = "Add a sellable room",
                why = "At least one vacant room must exist before the hotel can open.",
                method = "POST",
                path = "/api/v1/properties/$propertyId/rooms",
                bodyHint = mapOf(
                    "buildingId" to BUILDING_ID_HINT,
                    "roomNumber" to "101",
                    "roomTypeId" to ROOM_TYPE_ID_HINT,
                    "floorNumber" to 1,
                ),
            )
            "inventory.revenue_center" -> OnboardingNextAction(
                step = STEP_INVENTORY,
                title = "Add a revenue center",
                why = "Folios post to a revenue center. Rooms revenue is enough to start.",
                method = "POST",
                path = "/api/v1/properties/$propertyId/revenue-centers",
                bodyHint = mapOf(
                    "name" to "Rooms",
                    "code" to "RMS",
                    "centerType" to "rooms",
                    "isRoomsRevenue" to true,
                ),
            )
            "inventory.tax" -> OnboardingNextAction(
                step = STEP_INVENTORY,
                title = "Add a tax rate",
                why = "An active tax rate is required before the hotel can sell.",
                method = "POST",
                path = "/api/v1/properties/taxes",
                bodyHint = mapOf(
                    "name" to "VAT",
                    "code" to "VAT",
                    "rate" to 0.18,
                    "taxType" to "vat",
                ),
            )
            "inventory.base_rate" -> OnboardingNextAction(
                step = STEP_INVENTORY,
                title = "Set a positive base rate",
                why = "Every active room type needs a positive base rate.",
                method = "POST",
                path = "/api/v1/properties/$propertyId/rates",
                bodyHint = mapOf(
                    "roomTypeId" to ROOM_TYPE_ID_HINT,
                    "amount" to 80_000,
                    "currency" to "TZS",
                ),
            )
            "inventory.business_contact" -> OnboardingNextAction(
                step = STEP_INVENTORY,
                title = "Add and verify a business contact",
                why = "The hotel needs at least one verified phone or email channel.",
                method = "POST",
                path = "/api/v1/communication/contacts",
                bodyHint = mapOf(
                    "fullName" to "General Manager",
                    "jobTitle" to "General Manager",
                    "email" to "gm@hotel.example",
                    "phone" to "+255700000001",
                ),
            )
            "inventory.module" -> OnboardingNextAction(
                step = STEP_INVENTORY,
                title = "Enable the property module",
                why = "The property module must be enabled on the tenant and this hotel.",
                method = "POST",
                path = "/api/v1/properties/$propertyId/modules",
                bodyHint = mapOf("moduleId" to "property"),
            )
            "inventory.profile" -> OnboardingNextAction(
                step = STEP_INVENTORY,
                title = "Name the property",
                why = "A property profile name is required before go-live.",
                method = "PUT",
                path = "/api/v1/properties/$propertyId",
                bodyHint = mapOf("name" to "Hotel name"),
            )
            else -> OnboardingNextAction(
                step = STEP_INVENTORY,
                title = "Finish hotel inventory",
                why = blockers.firstOrNull { it.stepKey == STEP_INVENTORY }?.detail
                    ?: "Building, floor, room type, room, rate, tax, and a verified contact are required.",
                method = "GET",
                path = "/api/v1/properties/$propertyId/onboarding",
                bodyHint = null,
            )
        }
    }

    private fun frontline(tenantId: UUID, propertyId: UUID) = OnboardingNextAction(
        step = STEP_FRONTLINE,
        title = "Hire phone-first frontline staff",
        why = "POS or front desk is in scope, so this hotel needs phone-first " +
            "staff (email optional) on an operational property role.",
        method = "POST",
        path = "/api/v1/tenants/$tenantId/staff",
        bodyHint = mapOf(
            "fullName" to "Front desk",
            "phoneNumber" to "+2557XXXXXXXX",
            "propertyId" to propertyId.toString(),
            "propertyRoleId" to PROPERTY_ROLE_ID_HINT,
        ),
    )

    private fun activate(propertyId: UUID) = OnboardingNextAction(
        step = STEP_GO_LIVE,
        title = "Activate the property",
        why = "Required evidence is in place. Activate opens the hotel. " +
            "Guest mobile money is a later ENABLE on the hotel merchant.",
        method = "POST",
        path = "/api/v1/properties/$propertyId/activate",
        bodyHint = null,
    )

    private const val STEP_PROPERTY_DISTINCT = "property_distinct"
    private const val STEP_STRONG_MANAGER = "strong_manager"
    private const val STEP_INVENTORY = "inventory_ready"
    private const val STEP_FRONTLINE = "frontline_path"
    private const val STEP_SMS = "sms_routable"
    private const val STEP_GO_LIVE = "go_live"
    private const val BUILDING_ID_HINT = "<buildingId from GET /buildings>"
    private const val ROOM_TYPE_ID_HINT = "<roomTypeId from GET /room-types>"
    private const val PROPERTY_ROLE_ID_HINT = "<operational property role id>"
}
