package com.mwombeki.peak.property.internal

import com.mwombeki.peak.property.api.OnboardingNextAction
import com.mwombeki.peak.property.api.OperatorBlockerView
import com.mwombeki.peak.property.api.PropertyGoLiveBlockerView
import com.mwombeki.peak.property.api.PropertyManagementNotFoundException
import com.mwombeki.peak.property.api.PropertyBootstrapResponse
import com.mwombeki.peak.property.api.PropertyOnboardingResponse
import com.mwombeki.peak.property.api.PropertyOnboardingStepView
import com.mwombeki.peak.property.api.PropertyReadinessResponse
import java.util.UUID
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Property go-live from evidence, not a checklist a manager can tick.
 *
 * Inventory, a Keycloak Property Administrator, and a frontline path (when POS
 * or front desk is in scope) are required. A hotel guest rail is optional after
 * activate — collecting guest USSD is a later ENABLE on the hotel merchant, not
 * a second Snippe onboarding. Peak SaaS collection lives in
 * `peak.platformbilling`, never [payment_provider_accounts]. This evaluator
 * never writes that table.
 */
@Component
class PropertyGoLiveEvaluator(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val environment: Environment,
) {
    fun evaluateAndPersist(tenantId: UUID, propertyId: UUID): PropertyGoLiveSnapshot {
        ensureWorkflow(tenantId, propertyId)
        val snapshot = evaluate(tenantId, propertyId)
        persist(snapshot)
        return snapshot
    }

    fun ensureWorkflow(tenantId: UUID, propertyId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO property_onboarding_states (tenant_id, property_id)
            VALUES (?, ?)
            ON CONFLICT (tenant_id, property_id) DO NOTHING
            """.trimIndent(),
            tenantId,
            propertyId,
        )
        CANONICAL_STEPS.forEach { step ->
            jdbcTemplate.update(
                """
                INSERT INTO property_onboarding_steps (
                    tenant_id, property_id, step_key, sequence, required
                )
                VALUES (?, ?, ?, ?, true)
                ON CONFLICT ON CONSTRAINT uq_property_onboarding_step DO NOTHING
                """.trimIndent(),
                tenantId,
                propertyId,
                step.key,
                step.sequence,
            )
        }
    }

    private fun evaluate(tenantId: UUID, propertyId: UUID): PropertyGoLiveSnapshot {
        val property = jdbcTemplate.query(
            """
            SELECT id, tenant_id, name, status, is_active
            FROM properties
            WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
            """.trimIndent(),
            { rs, _ ->
                PropertyRow(
                    id = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    name = rs.getString("name").orEmpty(),
                    status = rs.getString("status"),
                    isActive = rs.getBoolean("is_active"),
                )
            },
            propertyId,
            tenantId,
        ).singleOrNull() ?: throw PropertyManagementNotFoundException(
            "Property record not found or access denied",
        )

        val frontlineInScope = frontlineInScope(tenantId, propertyId)
        val collectionEnabled = collectionEnabled(tenantId, propertyId)

        val steps = listOf(
            propertyDistinct(property),
            strongManager(tenantId, propertyId),
            inventoryReady(tenantId, propertyId, property),
            frontlinePath(tenantId, propertyId, frontlineInScope),
            guestRailConfigured(tenantId, propertyId),
            smsRoutable(frontlineInScope),
            goLive(property),
        )

        val blockers = steps
            .filter { it.required && it.status == STATUS_BLOCKED }
            .flatMap { step ->
                if (step.blockers.isNotEmpty()) {
                    step.blockers
                } else {
                    listOf(
                        PropertyGoLiveBlockerView(
                            code = step.blockerCode ?: step.key,
                            stepKey = step.key,
                            detail = step.detail,
                        ),
                    )
                }
            }

        val preActivationReady = steps
            .filter { it.key != STEP_GO_LIVE && it.required }
            .all { it.status == STATUS_SATISFIED }

        val workflowStatus = when {
            property.status == "active" -> STATUS_ACTIVATED
            preActivationReady -> "ready"
            blockers.isNotEmpty() -> "blocked"
            else -> "running"
        }
        val currentStep = steps
            .firstOrNull { it.required && it.status != STATUS_SATISFIED && it.status != STATUS_SKIPPED }
            ?.key
            ?: STEP_GO_LIVE

        val isReady = preActivationReady && property.status !in TERMINAL_STATUSES
        val operatorBlocker = PropertyOnboardingGuide.operatorBlocker(
            steps.firstOrNull { it.key == STEP_SMS },
        )
        val nextAction = PropertyOnboardingGuide.nextAction(
            tenantId = tenantId,
            propertyId = propertyId,
            currentStep = currentStep,
            isReady = isReady,
            workflowStatus = workflowStatus,
            blockers = blockers,
        )

        return PropertyGoLiveSnapshot(
            tenantId = tenantId,
            propertyId = propertyId,
            workflowStatus = workflowStatus,
            currentStep = currentStep,
            isReady = isReady,
            collectionEnabled = collectionEnabled,
            steps = steps,
            blockers = blockers,
            nextAction = nextAction,
            operatorBlocker = operatorBlocker,
        )
    }

    private fun persist(snapshot: PropertyGoLiveSnapshot) {
        jdbcTemplate.update(
            """
            UPDATE property_onboarding_states
            SET workflow_status = ?,
                current_step = ?,
                last_evaluated_at = now(),
                version = version + 1,
                activated_at = CASE
                    WHEN ? = 'activated' THEN COALESCE(activated_at, now())
                    ELSE activated_at
                END
            WHERE tenant_id = ? AND property_id = ?
            """.trimIndent(),
            snapshot.workflowStatus,
            snapshot.currentStep,
            snapshot.workflowStatus,
            snapshot.tenantId,
            snapshot.propertyId,
        )
        snapshot.steps.forEach { step ->
            jdbcTemplate.update(
                """
                UPDATE property_onboarding_steps
                SET status = ?,
                    required = ?,
                    blocker_code = ?,
                    blocker_detail = ?,
                    evidence = ?::jsonb,
                    satisfied_at = CASE
                        WHEN ? = 'satisfied' THEN COALESCE(satisfied_at, now())
                        ELSE NULL
                    END
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND step_key = ?
                """.trimIndent(),
                step.status,
                step.required,
                step.blockerCode,
                step.detail.takeIf { step.status == STATUS_BLOCKED },
                objectMapper.writeValueAsString(step.evidence),
                step.status,
                snapshot.tenantId,
                snapshot.propertyId,
                step.key,
            )
        }
    }

    private fun propertyDistinct(property: PropertyRow): EvaluatedStep {
        val distinct = property.id != property.tenantId
        val tenantExists = exists(
            """
            SELECT EXISTS (
                SELECT 1 FROM tenants
                WHERE id = ? AND deleted_at IS NULL
            )
            """.trimIndent(),
            property.tenantId,
        )
        val ok = distinct && tenantExists
        return EvaluatedStep(
            key = STEP_PROPERTY_DISTINCT,
            sequence = 1,
            required = true,
            status = if (ok) STATUS_SATISFIED else STATUS_BLOCKED,
            blockerCode = if (ok) null else "property_distinct",
            detail = when {
                !distinct -> "Property id must not equal tenant id."
                !tenantExists -> "Property must belong to a living tenant."
                else -> "Property exists and is distinct from the tenant."
            },
            evidence = mapOf(
                "propertyId" to property.id.toString(),
                "tenantId" to property.tenantId.toString(),
                "distinct" to distinct,
            ),
        )
    }

    private fun strongManager(tenantId: UUID, propertyId: UUID): EvaluatedStep {
        val count = count(
            """
            SELECT COUNT(*)
            FROM user_property_roles upr
            JOIN roles r
              ON r.id = upr.role_id
             AND r.tenant_id = upr.tenant_id
            JOIN users u
              ON u.id = upr.user_id
             AND u.tenant_id = upr.tenant_id
            JOIN identity_links il
              ON il.user_id = u.id
             AND il.tenant_id = u.tenant_id
             AND il.revoked_at IS NULL
             AND il.identity_mode = 'tenant'
             AND il.provider = 'oidc'
            WHERE upr.tenant_id = ?
              AND upr.property_id = ?
              AND u.deleted_at IS NULL
              AND u.is_active = true
              AND coalesce(u.status, 'active') = 'active'
              AND r.is_active = true
              AND r.name = 'Property Administrator'
            """.trimIndent(),
            tenantId,
            propertyId,
        )
        val ok = count > 0
        return EvaluatedStep(
            key = STEP_STRONG_MANAGER,
            sequence = 2,
            required = true,
            status = if (ok) STATUS_SATISFIED else STATUS_BLOCKED,
            blockerCode = if (ok) null else "strong_manager",
            detail = if (ok) {
                "A Keycloak-linked Property Administrator is assigned to this hotel."
            } else {
                "At least one STRONG (Keycloak) manager or owner identity must be assigned to this property."
            },
            evidence = mapOf("strongAdministrators" to count),
        )
    }

    private fun inventoryReady(
        tenantId: UUID,
        propertyId: UUID,
        property: PropertyRow,
    ): EvaluatedStep {
        val blockers = mutableListOf<PropertyGoLiveBlockerView>()
        if (property.status in TERMINAL_STATUSES) {
            blockers += blocker("inventory.terminal_status", "Property must not be archived or terminated.")
        }
        if (property.name.isBlank()) {
            blockers += blocker("inventory.profile", "Property profile name is required.")
        }
        if (count(
                """
                SELECT COUNT(*) FROM buildings
                WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                tenantId,
                propertyId,
            ) == 0
        ) {
            blockers += blocker("inventory.building", "Property must have at least one building configured.")
        }
        if (count(
                """
                SELECT COUNT(*)
                FROM floors f
                JOIN buildings b ON b.id = f.building_id AND b.tenant_id = f.tenant_id
                WHERE f.tenant_id = ?
                  AND b.property_id = ?
                  AND f.deleted_at IS NULL
                  AND b.deleted_at IS NULL
                """.trimIndent(),
                tenantId,
                propertyId,
            ) == 0
        ) {
            blockers += blocker("inventory.floor", "Property must have at least one floor configured.")
        }
        if (count(
                """
                SELECT COUNT(*) FROM room_types
                WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL AND is_active = true
                """.trimIndent(),
                tenantId,
                propertyId,
            ) == 0
        ) {
            blockers += blocker(
                "inventory.room_type",
                "Property must have at least one active room type configured.",
            )
        }
        if (count(
                """
                SELECT COUNT(*) FROM rooms
                WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                  AND status IN ('vacant_clean', 'vacant_dirty', 'occupied')
                """.trimIndent(),
                tenantId,
                propertyId,
            ) == 0
        ) {
            blockers += blocker("inventory.room", "Property must have at least one active room configured.")
        }
        if (count(
                """
                SELECT COUNT(*) FROM revenue_centers
                WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL AND is_active = true
                """.trimIndent(),
                tenantId,
                propertyId,
            ) == 0
        ) {
            blockers += blocker(
                "inventory.revenue_center",
                "Property must have at least one active revenue center configured.",
            )
        }
        if (!exists("SELECT EXISTS(SELECT 1 FROM tax_rates WHERE tenant_id = ? AND is_active = true)", tenantId)) {
            blockers += blocker("inventory.tax", "Property lacks active tax configuration records.")
        }
        if (count(
                """
                SELECT COUNT(*) FROM room_types
                WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                  AND is_active = true AND base_price <= 0
                """.trimIndent(),
                tenantId,
                propertyId,
            ) > 0
        ) {
            blockers += blocker(
                "inventory.base_rate",
                "All active room types must have positive base rates configured.",
            )
        }
        if (!tenantModuleEnabled(tenantId, PROPERTY_MODULE) ||
            !propertyModuleEnabled(tenantId, propertyId, PROPERTY_MODULE)
        ) {
            blockers += blocker(
                "inventory.module",
                "Required module 'property' must be enabled for the tenant and property.",
            )
        }
        if (!exists(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM tenant_contacts tc
                    JOIN contact_channels cc
                      ON cc.tenant_id = tc.tenant_id
                     AND cc.contact_id = tc.id
                     AND cc.deleted_at IS NULL
                     AND cc.is_active = true
                     AND cc.verification_status = 'verified'
                    WHERE tc.tenant_id = ?
                      AND tc.deleted_at IS NULL
                      AND tc.status = 'active'
                )
                """.trimIndent(),
                tenantId,
            )
        ) {
            blockers += blocker(
                "inventory.business_contact",
                "At least one active verified business contact channel is required.",
            )
        }

        val ok = blockers.isEmpty()
        return EvaluatedStep(
            key = STEP_INVENTORY,
            sequence = 3,
            required = true,
            status = if (ok) STATUS_SATISFIED else STATUS_BLOCKED,
            blockerCode = if (ok) null else blockers.first().code,
            detail = if (ok) {
                "Hotel inventory, rates, and business contact are in place."
            } else {
                blockers.joinToString(" ") { it.detail }
            },
            evidence = mapOf("missing" to blockers.map { it.code }),
            blockers = blockers,
        )
    }

    private fun frontlinePath(
        tenantId: UUID,
        propertyId: UUID,
        inScope: Boolean,
    ): EvaluatedStep {
        if (!inScope) {
            return EvaluatedStep(
                key = STEP_FRONTLINE,
                sequence = 4,
                required = false,
                status = STATUS_SKIPPED,
                blockerCode = null,
                detail = "POS and front desk are not enabled on this property.",
                evidence = mapOf("inScope" to false),
            )
        }
        val phoneFirst = count(
            """
            SELECT COUNT(*)
            FROM users u
            JOIN user_property_roles upr
              ON upr.user_id = u.id AND upr.tenant_id = u.tenant_id
            WHERE u.tenant_id = ?
              AND upr.property_id = ?
              AND u.deleted_at IS NULL
              AND u.is_active = true
              AND coalesce(u.status, 'active') = 'active'
              AND u.staff_number IS NOT NULL
            """.trimIndent(),
            tenantId,
            propertyId,
        )
        val equivalent = count(
            """
            SELECT COUNT(*)
            FROM users u
            JOIN user_property_roles upr
              ON upr.user_id = u.id AND upr.tenant_id = u.tenant_id
            JOIN roles r
              ON r.id = upr.role_id AND r.tenant_id = upr.tenant_id
            WHERE u.tenant_id = ?
              AND upr.property_id = ?
              AND u.deleted_at IS NULL
              AND u.is_active = true
              AND coalesce(u.status, 'active') = 'active'
              AND r.is_active = true
              AND r.is_system = false
            """.trimIndent(),
            tenantId,
            propertyId,
        )
        val ok = phoneFirst > 0 || equivalent > 0
        return EvaluatedStep(
            key = STEP_FRONTLINE,
            sequence = 4,
            required = true,
            status = if (ok) STATUS_SATISFIED else STATUS_BLOCKED,
            blockerCode = if (ok) null else "frontline_path",
            detail = if (ok) {
                "A frontline staff path exists for POS or front desk."
            } else {
                "POS or front desk is in scope, so this property needs phone-first staff (or an equivalent operational property role)."
            },
            evidence = mapOf(
                "inScope" to true,
                "phoneFirstStaff" to phoneFirst,
                "equivalentOperationalRoles" to equivalent,
            ),
        )
    }

    private fun guestRailConfigured(tenantId: UUID, propertyId: UUID): EvaluatedStep {
        val configured = count(
            """
            SELECT COUNT(*)
            FROM payment_provider_accounts ppa
            JOIN payment_providers pp
              ON pp.id = ppa.provider_id
             AND pp.tenant_id = ppa.tenant_id
            JOIN peak_payment_method_capabilities cap
              ON cap.provider = pp.provider_code
             AND cap.payment_method = 'mobile_money'
             AND cap.is_enabled = true
             AND cap.supports_status_query = true
            WHERE ppa.tenant_id = ?
              AND ppa.property_id = ?
              AND ppa.is_active = true
              AND ppa.lifecycle_status IN ('configured', 'verified', 'certified', 'enabled')
            """.trimIndent(),
            tenantId,
            propertyId,
        )
        val ok = configured > 0
        // Hotel guest collection is optional after activate. Requiring it here
        // made hotels think they were onboarding Peak/Snippe a second time.
        return EvaluatedStep(
            key = STEP_GUEST_RAIL,
            sequence = 5,
            required = false,
            status = if (ok) STATUS_SATISFIED else STATUS_BLOCKED,
            blockerCode = if (ok) null else "guest_rail_configured",
            detail = if (ok) {
                "A recoverable guest rail is configured. Collection still requires ENABLE. This is not Peak SaaS onboarding."
            } else {
                "Guest mobile money is optional after activate. Configure the hotel's own Snippe account later to collect guest payments. Activate is not blocked."
            },
            evidence = mapOf("configuredAccounts" to configured),
        )
    }

    private fun smsRoutable(frontlineInScope: Boolean): EvaluatedStep {
        if (!frontlineInScope) {
            return EvaluatedStep(
                key = STEP_SMS,
                sequence = 6,
                required = false,
                status = STATUS_SKIPPED,
                blockerCode = null,
                detail = "Staff activation SMS is not required until POS or front desk is in scope.",
                evidence = mapOf(
                    "inScope" to false,
                    "smsRoutable" to false,
                    "whatsappRequired" to false,
                ),
            )
        }
        val routable = environment.getProperty("peak.communication.routing.sms")
            .orEmpty()
            .trim()
            .isNotEmpty()
        // PEAK_COMMUNICATION_ROUTING_SMS is Peak deployment config (Beem). A
        // hotel cannot set it. Do not block property activate on it — surface
        // operatorBlocker instead so Peak ops can route SMS without stalling
        // the wizard on an env var.
        return EvaluatedStep(
            key = STEP_SMS,
            sequence = 6,
            required = false,
            status = if (routable) STATUS_SATISFIED else STATUS_BLOCKED,
            blockerCode = if (routable) null else "sms_routable",
            detail = if (routable) {
                "SMS can be delivered for staff activation. WhatsApp is optional."
            } else {
                "Staff SMS is not routable on this Peak deployment. Peak ops must set PEAK_COMMUNICATION_ROUTING_SMS (Beem). The hotel cannot fix this, so activate is not blocked."
            },
            evidence = mapOf(
                "inScope" to true,
                "smsRoutable" to routable,
                "whatsappRequired" to false,
            ),
        )
    }

    private fun goLive(property: PropertyRow): EvaluatedStep {
        val active = property.status == "active"
        return EvaluatedStep(
            key = STEP_GO_LIVE,
            sequence = 7,
            required = true,
            status = if (active) STATUS_SATISFIED else "pending",
            blockerCode = null,
            detail = if (active) {
                "Property is active. Guest collection is a later ENABLE on the hotel merchant, not a second onboarding."
            } else {
                "Activate after the other required steps are satisfied. Guest collection is optional afterwards."
            },
            evidence = mapOf(
                "status" to property.status,
                "isActive" to property.isActive,
            ),
        )
    }

    private fun frontlineInScope(tenantId: UUID, propertyId: UUID): Boolean {
        return FRONTLINE_MODULES.any { moduleId ->
            tenantModuleEnabled(tenantId, moduleId) && propertyModuleEnabled(tenantId, propertyId, moduleId)
        }
    }

    private fun collectionEnabled(tenantId: UUID, propertyId: UUID): Boolean {
        return exists(
            """
            SELECT EXISTS (
                SELECT 1
                FROM payment_provider_accounts
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND is_active = true
                  AND lifecycle_status = 'enabled'
            )
            """.trimIndent(),
            tenantId,
            propertyId,
        )
    }

    private fun tenantModuleEnabled(tenantId: UUID, moduleId: String): Boolean {
        return exists(
            """
            SELECT EXISTS (
                SELECT 1 FROM tenant_modules
                WHERE tenant_id = ? AND module_id = ? AND is_enabled = true
            )
            """.trimIndent(),
            tenantId,
            moduleId,
        )
    }

    private fun propertyModuleEnabled(tenantId: UUID, propertyId: UUID, moduleId: String): Boolean {
        return exists(
            """
            SELECT EXISTS (
                SELECT 1 FROM property_modules
                WHERE tenant_id = ? AND property_id = ? AND module_id = ? AND is_enabled = true
            )
            """.trimIndent(),
            tenantId,
            propertyId,
            moduleId,
        )
    }

    private fun count(sql: String, vararg args: Any?): Int {
        return jdbcTemplate.queryForObject(sql, Int::class.java, *args) ?: 0
    }

    private fun exists(sql: String, vararg args: Any?): Boolean {
        return jdbcTemplate.queryForObject(sql, Boolean::class.java, *args) == true
    }

    private fun blocker(code: String, detail: String) = PropertyGoLiveBlockerView(
        code = code,
        stepKey = STEP_INVENTORY,
        detail = detail,
    )

    private data class PropertyRow(
        val id: UUID,
        val tenantId: UUID,
        val name: String,
        val status: String,
        val isActive: Boolean,
    )

    private data class CanonicalStep(
        val key: String,
        val sequence: Int,
    )

    data class EvaluatedStep(
        val key: String,
        val sequence: Int,
        val required: Boolean,
        val status: String,
        val blockerCode: String?,
        val detail: String,
        val evidence: Map<String, Any?>,
        val blockers: List<PropertyGoLiveBlockerView> = emptyList(),
    )

    data class PropertyGoLiveSnapshot(
        val tenantId: UUID,
        val propertyId: UUID,
        val workflowStatus: String,
        val currentStep: String?,
        val isReady: Boolean,
        val collectionEnabled: Boolean,
        val steps: List<EvaluatedStep>,
        val blockers: List<PropertyGoLiveBlockerView>,
        val nextAction: OnboardingNextAction?,
        val operatorBlocker: OperatorBlockerView?,
    ) {
        fun toReadinessResponse(): PropertyReadinessResponse {
            return PropertyReadinessResponse(
                propertyId = propertyId,
                isReady = isReady,
                missingRequirements = blockers.map { it.detail },
                workflowStatus = workflowStatus,
                currentStep = currentStep,
                steps = stepViews(),
                blockers = blockers,
                collectionEnabled = collectionEnabled,
                nextAction = nextAction,
                operatorBlocker = operatorBlocker,
            )
        }

        fun toOnboardingResponse(): PropertyOnboardingResponse {
            return PropertyOnboardingResponse(
                propertyId = propertyId,
                tenantId = tenantId,
                workflowStatus = workflowStatus,
                currentStep = currentStep,
                isReady = isReady,
                collectionEnabled = collectionEnabled,
                steps = stepViews(),
                blockers = blockers,
                nextAction = nextAction,
                operatorBlocker = operatorBlocker,
            )
        }

        fun toBootstrapResponse(
            status: String,
            changed: Boolean,
            replayed: Boolean,
        ): PropertyBootstrapResponse {
            return PropertyBootstrapResponse(
                propertyId = propertyId,
                tenantId = tenantId,
                status = status,
                changed = changed,
                replayed = replayed,
                nextAction = nextAction,
                workflowStatus = workflowStatus,
                currentStep = currentStep,
                isReady = isReady,
                steps = stepViews(),
                blockers = blockers,
                operatorBlocker = operatorBlocker,
            )
        }

        private fun stepViews(): List<PropertyOnboardingStepView> {
            return steps.map { step ->
                PropertyOnboardingStepView(
                    key = step.key,
                    sequence = step.sequence,
                    status = step.status,
                    required = step.required,
                    detail = step.detail,
                )
            }
        }
    }

    private companion object {
        const val PROPERTY_MODULE = "property"
        const val STEP_PROPERTY_DISTINCT = "property_distinct"
        const val STEP_STRONG_MANAGER = "strong_manager"
        const val STEP_INVENTORY = "inventory_ready"
        const val STEP_FRONTLINE = "frontline_path"
        const val STEP_GUEST_RAIL = "guest_rail_configured"
        const val STEP_SMS = "sms_routable"
        const val STEP_GO_LIVE = "go_live"
        const val STATUS_SATISFIED = "satisfied"
        const val STATUS_BLOCKED = "blocked"
        const val STATUS_SKIPPED = "skipped"
        const val STATUS_ACTIVATED = "activated"
        val TERMINAL_STATUSES = setOf("archived", "terminated")
        val FRONTLINE_MODULES = setOf("pos", "frontdesk", "reservations")
        val CANONICAL_STEPS = listOf(
            CanonicalStep(STEP_PROPERTY_DISTINCT, 1),
            CanonicalStep(STEP_STRONG_MANAGER, 2),
            CanonicalStep(STEP_INVENTORY, 3),
            CanonicalStep(STEP_FRONTLINE, 4),
            CanonicalStep(STEP_GUEST_RAIL, 5),
            CanonicalStep(STEP_SMS, 6),
            CanonicalStep(STEP_GO_LIVE, 7),
        )
    }
}
