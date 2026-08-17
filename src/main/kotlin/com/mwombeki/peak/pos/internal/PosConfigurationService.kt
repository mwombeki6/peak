package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.pos.api.CreatePosMenuCategoryRequest
import com.mwombeki.peak.pos.api.CreatePosMenuItemRequest
import com.mwombeki.peak.pos.api.CreatePosOutletRequest
import com.mwombeki.peak.pos.api.PosConfigurationResponse
import com.mwombeki.peak.pos.api.PosMenuCategoryResponse
import com.mwombeki.peak.pos.api.PosMenuItemResponse
import com.mwombeki.peak.pos.api.PosNotFoundException
import java.math.RoundingMode
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class PosConfigurationService(
    private val jdbcTemplate: JdbcTemplate,
    private val commandExecutor: PosCommandExecutor,
) {
    fun createOutlet(
        propertyId: UUID,
        request: CreatePosOutletRequest,
    ): PosConfigurationResponse {
        return configure(
            propertyId,
            "pos.configuration.outlet.create",
            "outlets",
            request,
        ) { tenantId, id ->
            jdbcTemplate.queryForList(
                "SELECT assert_tenant_capacity(?, 'limit.outlets')",
                tenantId,
            )
            requireExists(
                """
                SELECT EXISTS (
                    SELECT 1 FROM revenue_centers
                    WHERE tenant_id = ?
                      AND property_id = ?
                      AND id = ?
                      AND is_active = true
                      AND deleted_at IS NULL
                )
                """.trimIndent(),
                tenantId,
                propertyId,
                request.revenueCenterId,
                message = "Active revenue center was not found",
            )
            val type = request.type.trim().uppercase()
            require(type in OUTLET_TYPES) {
                "Unsupported outlet type"
            }
            jdbcTemplate.update(
                """
                INSERT INTO outlets (
                    id, tenant_id, property_id, revenue_center_id,
                    name, type, is_active
                )
                VALUES (?, ?, ?, ?, ?, ?, true)
                """.trimIndent(),
                id,
                tenantId,
                propertyId,
                request.revenueCenterId,
                request.name.required("name"),
                type,
            )
        }
    }

    fun createCategory(
        propertyId: UUID,
        request: CreatePosMenuCategoryRequest,
    ): PosConfigurationResponse {
        return configure(
            propertyId,
            "pos.configuration.category.create",
            "menu_categories",
            request,
        ) { tenantId, id ->
            requireOutlet(tenantId, propertyId, request.outletId)
            jdbcTemplate.update(
                """
                INSERT INTO menu_categories (id, tenant_id, outlet_id, name)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                id,
                tenantId,
                request.outletId,
                request.name.required("name"),
            )
        }
    }

    fun createMenuItem(
        propertyId: UUID,
        request: CreatePosMenuItemRequest,
    ): PosConfigurationResponse {
        return configure(
            propertyId,
            "pos.configuration.menu_item.create",
            "menu_items",
            request,
        ) { tenantId, id ->
            requireExists(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM menu_categories mc
                    JOIN outlets o
                      ON o.tenant_id = mc.tenant_id
                     AND o.id = mc.outlet_id
                    WHERE mc.tenant_id = ?
                      AND o.property_id = ?
                      AND mc.id = ?
                      AND o.is_active = true
                      AND o.deleted_at IS NULL
                )
                """.trimIndent(),
                tenantId,
                propertyId,
                request.categoryId,
                message = "Active menu category was not found",
            )
            requireExists(
                """
                SELECT EXISTS (
                    SELECT 1 FROM tax_rates
                    WHERE tenant_id = ?
                      AND id = ?
                      AND is_active = true
                      AND effective_from <= current_date
                      AND (effective_to IS NULL OR effective_to > current_date)
                )
                """.trimIndent(),
                tenantId,
                request.taxRateId,
                message = "Active tax rate was not found",
            )
            val price = request.price.setScale(2, RoundingMode.HALF_UP)
            require(price.signum() > 0) {
                "price must be positive"
            }
            jdbcTemplate.update(
                """
                INSERT INTO menu_items (
                    id, tenant_id, category_id, name, price, tax_rate_id,
                    is_available
                )
                VALUES (?, ?, ?, ?, ?, ?, true)
                """.trimIndent(),
                id,
                tenantId,
                request.categoryId,
                request.name.required("name"),
                price,
                request.taxRateId,
            )
        }
    }

    fun listMenuCategories(
        propertyId: UUID,
        outletId: UUID,
    ): List<PosMenuCategoryResponse> {
        return commandExecutor.read(propertyId) { actor ->
            requireOutlet(actor.tenantId, propertyId, outletId)
            jdbcTemplate.query(
                """
                SELECT mc.id, mc.outlet_id, mc.name
                FROM menu_categories mc
                JOIN outlets o
                  ON o.tenant_id = mc.tenant_id
                 AND o.id = mc.outlet_id
                WHERE mc.tenant_id = ?
                  AND o.property_id = ?
                  AND mc.outlet_id = ?
                ORDER BY mc.name, mc.id
                """.trimIndent(),
                { rs, _ ->
                    PosMenuCategoryResponse(
                        id = rs.getObject("id", UUID::class.java),
                        outletId = rs.getObject("outlet_id", UUID::class.java),
                        name = rs.getString("name"),
                    )
                },
                actor.tenantId,
                propertyId,
                outletId,
            )
        }
    }

    fun listMenuItems(
        propertyId: UUID,
        outletId: UUID,
    ): List<PosMenuItemResponse> {
        return commandExecutor.read(propertyId) { actor ->
            requireOutlet(actor.tenantId, propertyId, outletId)
            jdbcTemplate.query(
                """
                SELECT mi.id, mi.category_id, mi.name, mi.price,
                       mi.tax_rate_id, mi.is_available
                FROM menu_items mi
                JOIN menu_categories mc
                  ON mc.tenant_id = mi.tenant_id
                 AND mc.id = mi.category_id
                JOIN outlets o
                  ON o.tenant_id = mc.tenant_id
                 AND o.id = mc.outlet_id
                WHERE mi.tenant_id = ?
                  AND o.property_id = ?
                  AND mc.outlet_id = ?
                  AND mi.deleted_at IS NULL
                ORDER BY mi.name, mi.id
                """.trimIndent(),
                { rs, _ ->
                    PosMenuItemResponse(
                        id = rs.getObject("id", UUID::class.java),
                        categoryId = rs.getObject("category_id", UUID::class.java),
                        name = rs.getString("name"),
                        price = rs.getBigDecimal("price").setScale(2, RoundingMode.HALF_UP),
                        taxRateId = rs.getObject("tax_rate_id", UUID::class.java),
                        isAvailable = rs.getBoolean("is_available"),
                    )
                },
                actor.tenantId,
                propertyId,
                outletId,
            )
        }
    }

    private fun configure(
        propertyId: UUID,
        operation: String,
        resourceType: String,
        request: Any,
        insert: (UUID, UUID) -> Unit,
    ): PosConfigurationResponse {
        return commandExecutor.mutate(
            propertyId = propertyId,
            operationType = operation,
            requestPayload = request,
            resourceType = resourceType,
            replayType = PosConfigurationResponse::class.java,
            resourceId = PosConfigurationResponse::id,
            markReplayed = { it.copy(replayed = true) },
        ) { actor, idempotencyKeyId ->
            val id = UUID.randomUUID()
            insert(actor.tenantId, id)
            commandExecutor.recordSideEffects(
                actor = actor,
                propertyId = propertyId,
                action = "$operation.completed",
                aggregateType = resourceType,
                aggregateId = id,
                payload = mapOf(
                    "propertyId" to propertyId,
                    "resourceId" to id,
                    "resourceType" to resourceType,
                ),
                idempotencyKeyId = idempotencyKeyId,
            )
            PosConfigurationResponse(
                id = id,
                propertyId = propertyId,
                resourceType = resourceType,
            )
        }
    }

    private fun requireOutlet(
        tenantId: UUID,
        propertyId: UUID,
        outletId: UUID,
    ) {
        requireExists(
            """
            SELECT EXISTS (
                SELECT 1 FROM outlets
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                  AND is_active = true
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            tenantId,
            propertyId,
            outletId,
            message = "Active outlet was not found",
        )
    }

    private fun requireExists(
        sql: String,
        vararg arguments: Any,
        message: String,
    ) {
        if (
            jdbcTemplate.queryForObject(
                sql,
                Boolean::class.java,
                *arguments,
            ) != true
        ) {
            throw PosNotFoundException(message)
        }
    }

    private fun String.required(field: String): String {
        return trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("$field is required")
    }

    private companion object {
        val OUTLET_TYPES = setOf(
            "RESTAURANT",
            "BAR",
            "ROOM_SERVICE",
            "POOL_BAR",
            "SPA",
            "BANQUET",
            "CAFE",
            "SHOP",
        )
    }
}
