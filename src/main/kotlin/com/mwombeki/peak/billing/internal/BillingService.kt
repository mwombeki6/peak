package com.mwombeki.peak.billing.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.billing.api.BillingConflictException
import com.mwombeki.peak.billing.api.BillingInProgressException
import com.mwombeki.peak.billing.api.BillingMutationReceipt
import com.mwombeki.peak.billing.api.BillingNotFoundException
import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.billing.api.CheckoutFinancialState
import com.mwombeki.peak.billing.api.ConfirmedPaymentRequest
import com.mwombeki.peak.billing.api.ConfirmedPaymentReversalRequest
import com.mwombeki.peak.billing.api.FolioChargeResponse
import com.mwombeki.peak.billing.api.FolioPaymentResponse
import com.mwombeki.peak.billing.api.FolioResponse
import com.mwombeki.peak.billing.api.InvoiceResponse
import com.mwombeki.peak.billing.api.IssueInvoiceRequest
import com.mwombeki.peak.billing.api.PostChargeRequest
import com.mwombeki.peak.billing.api.ReverseChargeRequest
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.TenantActor
import com.mwombeki.peak.shared.context.TenantRequestContext
import io.micrometer.core.instrument.MeterRegistry
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Service
class BillingService(
    private val jdbcTemplate: JdbcTemplate,
    private val tenantRequestContext: TenantRequestContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : BillingPort {

    override fun openReservationFolio(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
        idempotencyKeyId: UUID,
    ): UUID {
        requireActiveContext(tenantId, propertyId)
        val existing = jdbcTemplate.query(
            """
            SELECT id
            FROM folios
            WHERE tenant_id = ?
              AND property_id = ?
              AND reservation_id = ?
              AND status <> 'voided'
              AND deleted_at IS NULL
            ORDER BY opened_at
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            tenantId,
            propertyId,
            reservationId,
        ).singleOrNull()
        if (existing != null) {
            return existing
        }

        val folioId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO folios (id, tenant_id, property_id, reservation_id, folio_type, status)
            VALUES (?, ?, ?, ?, 'guest', 'open')
            """.trimIndent(),
            folioId,
            tenantId,
            propertyId,
            reservationId,
        )
        recordSideEffects(
            tenantId = tenantId,
            propertyId = propertyId,
            action = "billing.folio.opened",
            eventType = "billing.folio.opened",
            aggregateType = FOLIOS,
            aggregateId = folioId,
            payload = mapOf("propertyId" to propertyId, "reservationId" to reservationId, "folioId" to folioId),
            idempotencyKeyId = idempotencyKeyId,
            destination = OutboxDestination.PLATFORM,
        )
        return folioId
    }

    override fun postRoomChargeForReservation(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
        idempotencyKeyId: UUID,
    ): UUID? {
        requireActiveContext(tenantId, propertyId)
        val room = reservationRoomForCharge(tenantId, propertyId, reservationId)
            ?: throw BillingNotFoundException("Reservation room was not found for room-charge posting")

        val existingChargeId = jdbcTemplate.query(
            """
            SELECT id
            FROM folio_charges
            WHERE tenant_id = ?
              AND property_id = ?
              AND folio_id = ?
              AND source_type = 'reservation_room'
              AND source_id = ?
              AND status = 'POSTED'
              AND deleted_at IS NULL
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            tenantId,
            propertyId,
            room.folioId,
            room.reservationRoomId,
        ).singleOrNull()
        if (existingChargeId != null) {
            return null
        }

        val nights = nightsBetween(room.checkInDate, room.checkOutDate)
        val request = PostChargeRequest(
            chargeType = "ROOM",
            description = "Room ${room.roomNumber ?: room.roomTypeName} x $nights night(s)",
            quantity = BigDecimal(nights),
            unitPrice = room.ratePerNight,
            sourceType = "reservation_room",
            sourceId = room.reservationRoomId,
        )
        return postChargeInternal(
            actor = TenantActor(tenantId, room.createdBy ?: tenantUserFromContext()),
            propertyId = propertyId,
            folioId = room.folioId,
            request = request,
            idempotencyKeyId = idempotencyKeyId,
        )
    }

    override fun postConfirmedPayment(
        tenantId: UUID,
        propertyId: UUID,
        request: ConfirmedPaymentRequest,
        idempotencyKeyId: UUID?,
    ): UUID {
        requireActiveContext(tenantId, propertyId)
        val method = request.paymentMethod.normalizedPaymentMethod()
        val amount = request.amount.requirePositiveMoney("amount")
        val folio = requireFolio(tenantId, propertyId, request.folioId, lock = true)
        val paymentId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO folio_payments (
                id, tenant_id, property_id, folio_id, payment_method, amount,
                payment_transaction_id, cash_session_id, reference_number,
                idempotency_key, status, paid_at, processed_by, created_by, notes
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', now(), ?, ?, ?)
            """.trimIndent(),
            paymentId,
            tenantId,
            propertyId,
            folio.id,
            method,
            amount,
            request.paymentTransactionId,
            request.cashSessionId,
            request.referenceNumber?.trimmedOrNull(),
            request.idempotencyKey?.trimmedOrNull(),
            request.processedBy,
            request.processedBy,
            request.notes?.trimmedOrNull(),
        )
        recalculateFolio(folio.id)
        recordSideEffects(
            tenantId = tenantId,
            propertyId = propertyId,
            action = "billing.payment.posted",
            eventType = "billing.payment.posted",
            aggregateType = "folio_payments",
            aggregateId = paymentId,
            payload = mapOf(
                "propertyId" to propertyId,
                "folioId" to folio.id,
                "paymentId" to paymentId,
                "paymentMethod" to method,
                "amount" to amount,
            ),
            idempotencyKeyId = idempotencyKeyId,
            destination = OutboxDestination.PLATFORM,
        )
        return paymentId
    }

    override fun reverseConfirmedPayment(
        tenantId: UUID,
        propertyId: UUID,
        request: ConfirmedPaymentReversalRequest,
        idempotencyKeyId: UUID,
    ): UUID {
        requireActiveContext(tenantId, propertyId)
        val original = jdbcTemplate.query(
            """
            SELECT fp.id,
                   fp.folio_id,
                   fp.payment_method,
                   fp.amount,
                   fp.cash_session_id,
                   fp.status,
                   fp.is_reversed
            FROM folio_payments fp
            JOIN folios f
              ON f.tenant_id = fp.tenant_id
             AND f.id = fp.folio_id
             AND f.property_id = ?
             AND f.status = 'open'
             AND f.deleted_at IS NULL
            WHERE fp.tenant_id = ?
              AND fp.property_id = ?
              AND fp.payment_transaction_id = ?
              AND fp.deleted_at IS NULL
            FOR UPDATE OF fp, f
            """.trimIndent(),
            { rs, _ ->
                PaymentReversalSource(
                    paymentId = rs.getObject("id", UUID::class.java),
                    folioId = rs.getObject("folio_id", UUID::class.java),
                    paymentMethod = rs.getString("payment_method"),
                    amount = rs.getBigDecimal("amount").money(),
                    cashSessionId = rs.getObject("cash_session_id", UUID::class.java),
                    status = rs.getString("status"),
                    reversed = rs.getBoolean("is_reversed"),
                )
            },
            propertyId,
            tenantId,
            propertyId,
            request.originalPaymentTransactionId,
        ).singleOrNull() ?: throw BillingNotFoundException(
            "Posted payment was not found on an open folio",
        )
        require(original.status == "POSTED" && !original.reversed) {
            "Payment has already been reversed or is not posted"
        }
        val reversalPaymentId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO folio_payments (
                id, tenant_id, property_id, folio_id, payment_method, amount,
                payment_transaction_id, cash_session_id, reference_number,
                idempotency_key, status, reversal_of, paid_at,
                processed_by, created_by, notes
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', ?, now(), ?, ?, ?)
            """.trimIndent(),
            reversalPaymentId,
            tenantId,
            propertyId,
            original.folioId,
            original.paymentMethod,
            original.amount,
            request.reversalPaymentTransactionId,
            request.cashSessionId,
            request.referenceNumber?.trimmedOrNull(),
            idempotencyKeyId.toString(),
            original.paymentId,
            request.processedBy,
            request.processedBy,
            request.reason.trim(),
        )
        jdbcTemplate.update(
            """
            UPDATE folio_payments
            SET is_reversed = true,
                updated_at = now()
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            tenantId,
            original.paymentId,
        )
        recalculateFolio(original.folioId)
        recordSideEffects(
            tenantId = tenantId,
            propertyId = propertyId,
            action = "billing.payment.reversed",
            eventType = "billing.payment.reversed",
            aggregateType = "folio_payments",
            aggregateId = reversalPaymentId,
            payload = mapOf(
                "propertyId" to propertyId,
                "folioId" to original.folioId,
                "originalPaymentId" to original.paymentId,
                "reversalPaymentId" to reversalPaymentId,
                "amount" to original.amount,
                "reason" to request.reason.trim(),
            ),
            idempotencyKeyId = idempotencyKeyId,
            destination = OutboxDestination.PLATFORM,
        )
        return reversalPaymentId
    }

    override fun checkoutFinancialState(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
    ): CheckoutFinancialState {
        requireActiveContext(tenantId, propertyId)
        val folio = jdbcTemplate.query(
            """
            SELECT id, total_amount, total_paid
            FROM folios
            WHERE tenant_id = ?
              AND property_id = ?
              AND reservation_id = ?
              AND status = 'open'
              AND deleted_at IS NULL
            ORDER BY opened_at
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                FolioTotals(
                    id = rs.getObject("id", UUID::class.java),
                    totalAmount = rs.getBigDecimal("total_amount").money(),
                    totalPaid = rs.getBigDecimal("total_paid").money(),
                )
            },
            tenantId,
            propertyId,
            reservationId,
        ).singleOrNull() ?: throw BillingNotFoundException("Open reservation folio was not found")

        val hasIssuedInvoice = exists(
            """
            SELECT EXISTS (
                SELECT 1
                FROM invoices
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND folio_id = ?
                  AND status IN ('issued', 'sent', 'paid')
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            tenantId,
            propertyId,
            folio.id,
        )
        val hasAcceptedFiscalReceipt = exists(
            """
            SELECT EXISTS (
                SELECT 1
                FROM invoices i
                JOIN fiscal_receipts fr ON fr.tenant_id = i.tenant_id AND fr.invoice_id = i.id
                WHERE i.tenant_id = ?
                  AND i.property_id = ?
                  AND i.folio_id = ?
                  AND i.status IN ('issued', 'sent', 'paid')
                  AND fr.status = 'accepted'
                  AND i.deleted_at IS NULL
            )
            """.trimIndent(),
            tenantId,
            propertyId,
            folio.id,
        )
        return CheckoutFinancialState(
            folioId = folio.id,
            totalAmount = folio.totalAmount,
            totalPaid = folio.totalPaid,
            balanceDue = folio.totalAmount.subtract(folio.totalPaid).money(),
            hasIssuedInvoice = hasIssuedInvoice,
            hasAcceptedFiscalReceipt = hasAcceptedFiscalReceipt,
        )
    }

    override fun closeFolio(
        tenantId: UUID,
        propertyId: UUID,
        folioId: UUID,
    ) {
        requireActiveContext(tenantId, propertyId)
        requireFolio(tenantId, propertyId, folioId, lock = true)
        jdbcTemplate.queryForList("SELECT assert_folio_can_close(?)", folioId)
        jdbcTemplate.update(
            """
            UPDATE folios
            SET status = 'closed', closed_at = now(), updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND status = 'open'
            """.trimIndent(),
            tenantId,
            propertyId,
            folioId,
        )
    }

    override fun listFolios(propertyId: UUID): List<FolioResponse> {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                SELECT id, tenant_id, property_id, reservation_id, status, currency_code,
                       subtotal, tax_amount, service_charge, tourism_levy, total_amount, total_paid
                FROM folios
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND deleted_at IS NULL
                ORDER BY opened_at DESC
                """.trimIndent(),
                ::mapFolioSummary,
                actor.tenantId,
                propertyId,
            )
        }
    }

    override fun getFolio(propertyId: UUID, folioId: UUID): FolioResponse? {
        return read(propertyId) { actor ->
            val folio = jdbcTemplate.query(
                """
                SELECT id, tenant_id, property_id, reservation_id, status, currency_code,
                       subtotal, tax_amount, service_charge, tourism_levy, total_amount, total_paid
                FROM folios
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                  AND deleted_at IS NULL
                """.trimIndent(),
                ::mapFolioSummary,
                actor.tenantId,
                propertyId,
                folioId,
            ).singleOrNull() ?: return@read null
            folio.copy(
                charges = charges(actor.tenantId, propertyId, folio.id),
                payments = payments(actor.tenantId, propertyId, folio.id),
                invoices = invoices(actor.tenantId, propertyId, folio.id),
            )
        }
    }

    override fun postCharge(
        propertyId: UUID,
        folioId: UUID,
        request: PostChargeRequest,
    ): BillingMutationReceipt {
        return mutate(
            propertyId = propertyId,
            operationType = "billing.charge.post",
            requestPayload = mapOf("folioId" to folioId, "request" to request),
            resourceType = FOLIO_CHARGES,
            replayType = BillingMutationReceipt::class.java,
        ) { actor, idempotencyKeyId ->
            val chargeId = postChargeInternal(actor, propertyId, folioId, request, idempotencyKeyId)
            BillingMutationReceipt(propertyId, folioId, FOLIO_CHARGES, chargeId, changed = true, replayed = false)
        }
    }

    override fun postPosCharge(
        tenantId: UUID,
        propertyId: UUID,
        folioId: UUID,
        request: PostChargeRequest,
        idempotencyKeyId: UUID,
    ): UUID {
        val actor = tenantRequestContext.bind()
        require(actor.tenantId == tenantId) {
            "POS charge tenant must match the active tenant context"
        }
        tenantRequestContext.requirePropertyUsable(tenantId, propertyId, lock = false)
        return postChargeInternal(
            actor = actor,
            propertyId = propertyId,
            folioId = folioId,
            request = request,
            idempotencyKeyId = idempotencyKeyId,
        )
    }

    override fun reverseCharge(
        propertyId: UUID,
        folioId: UUID,
        chargeId: UUID,
        request: ReverseChargeRequest,
    ): BillingMutationReceipt {
        return mutate(
            propertyId = propertyId,
            operationType = "billing.charge.reverse",
            requestPayload = mapOf("folioId" to folioId, "chargeId" to chargeId, "request" to request),
            resourceType = FOLIO_CHARGES,
            replayType = BillingMutationReceipt::class.java,
        ) { actor, idempotencyKeyId ->
            requireFolio(actor.tenantId, propertyId, folioId, lock = true)
            val reason = request.reason.normalizedRequired("reason")
            val original = jdbcTemplate.query(
                """
                SELECT revenue_center_id, charge_type, description, source_type,
                       source_id, quantity, unit_price, subtotal, tax_rate,
                       tax_amount, amount
                FROM folio_charges fc
                WHERE fc.tenant_id = ?
                  AND fc.property_id = ?
                  AND fc.folio_id = ?
                  AND fc.id = ?
                  AND fc.status = 'POSTED'
                  AND fc.is_reversed = false
                  AND fc.reversal_of IS NULL
                  AND fc.deleted_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1
                      FROM folio_charges reversal
                      WHERE reversal.tenant_id = fc.tenant_id
                        AND reversal.reversal_of = fc.id
                  )
                FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    ReversibleCharge(
                        revenueCenterId = rs.getObject("revenue_center_id", UUID::class.java),
                        chargeType = rs.getString("charge_type"),
                        description = rs.getString("description"),
                        sourceType = rs.getString("source_type"),
                        sourceId = rs.getObject("source_id", UUID::class.java),
                        quantity = rs.getBigDecimal("quantity"),
                        unitPrice = rs.getBigDecimal("unit_price"),
                        subtotal = rs.getBigDecimal("subtotal"),
                        taxRate = rs.getBigDecimal("tax_rate"),
                        taxAmount = rs.getBigDecimal("tax_amount"),
                        amount = rs.getBigDecimal("amount"),
                    )
                },
                actor.tenantId,
                propertyId,
                folioId,
                chargeId,
            ).singleOrNull() ?: throw BillingNotFoundException(
                "Unreversed posted folio charge was not found",
            )
            val reversalId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO folio_charges (
                    id, tenant_id, property_id, folio_id, revenue_center_id,
                    charge_type, description, source_type, source_id, quantity,
                    unit_price, subtotal, tax_rate, tax_amount, amount, posted_by,
                    status, is_reversed, reversal_of, void_reason, voided_by,
                    voided_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'POSTED', true, ?, ?, ?, now())
                """.trimIndent(),
                reversalId,
                actor.tenantId,
                propertyId,
                folioId,
                original.revenueCenterId,
                original.chargeType,
                "Reversal: ${original.description}",
                original.sourceType,
                original.sourceId,
                original.quantity.negate(),
                original.unitPrice,
                original.subtotal.negate(),
                original.taxRate,
                original.taxAmount.negate(),
                original.amount.negate(),
                actor.tenantUserId,
                chargeId,
                reason,
                actor.tenantUserId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO folio_charge_taxes (
                    id, tenant_id, folio_charge_id, tax_rate_id,
                    tax_type, rate, taxable_amount, tax_amount
                )
                SELECT gen_random_uuid(), tenant_id, ?, tax_rate_id,
                       tax_type, rate, -taxable_amount, -tax_amount
                FROM folio_charge_taxes
                WHERE tenant_id = ? AND folio_charge_id = ?
                """.trimIndent(),
                reversalId,
                actor.tenantId,
                chargeId,
            )
            jdbcTemplate.update(
                """
                UPDATE folio_charges
                SET is_reversed = true,
                    void_reason = ?,
                    voided_by = ?,
                    voided_at = now(),
                    updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                reason,
                actor.tenantUserId,
                actor.tenantId,
                chargeId,
            )
            recalculateFolio(folioId)
            recordSideEffects(
                tenantId = actor.tenantId,
                propertyId = propertyId,
                action = "billing.charge.reversed",
                eventType = "billing.charge.reversed",
                aggregateType = FOLIO_CHARGES,
                aggregateId = reversalId,
                payload = mapOf(
                    "propertyId" to propertyId,
                    "folioId" to folioId,
                    "chargeId" to chargeId,
                    "reversalChargeId" to reversalId,
                    "reason" to reason,
                ),
                idempotencyKeyId = idempotencyKeyId,
                destination = OutboxDestination.PLATFORM,
            )
            BillingMutationReceipt(propertyId, folioId, FOLIO_CHARGES, reversalId, changed = true, replayed = false)
        }
    }

    override fun issueInvoice(
        propertyId: UUID,
        folioId: UUID,
        request: IssueInvoiceRequest,
    ): InvoiceResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "billing.invoice.issue",
            requestPayload = mapOf("folioId" to folioId, "request" to request),
            resourceType = INVOICES,
            replayType = InvoiceResponse::class.java,
        ) { actor, idempotencyKeyId ->
            issueInvoiceInternal(actor, propertyId, folioId, request, idempotencyKeyId)
        }
    }

    override fun listInvoices(propertyId: UUID): List<InvoiceResponse> {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                SELECT id, folio_id, property_id, invoice_number_formatted, subtotal, vat_total,
                       service_charge, tourism_levy, total, status, issued_at
                FROM invoices
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND deleted_at IS NULL
                ORDER BY issued_at DESC NULLS LAST, created_at DESC
                """.trimIndent(),
                ::mapInvoice,
                actor.tenantId,
                propertyId,
            )
        }
    }

    override fun getInvoice(propertyId: UUID, invoiceId: UUID): InvoiceResponse? {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                SELECT id, folio_id, property_id, invoice_number_formatted, subtotal, vat_total,
                       service_charge, tourism_levy, total, status, issued_at
                FROM invoices
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                  AND deleted_at IS NULL
                """.trimIndent(),
                ::mapInvoice,
                actor.tenantId,
                propertyId,
                invoiceId,
            ).singleOrNull()
        }
    }

    private fun postChargeInternal(
        actor: TenantActor,
        propertyId: UUID,
        folioId: UUID,
        request: PostChargeRequest,
        idempotencyKeyId: UUID,
    ): UUID {
        requireFolio(actor.tenantId, propertyId, folioId, lock = true)
        val chargeType = request.chargeType.normalizedChargeType()
        val description = request.description.normalizedRequired("description")
        val quantity = request.quantity.requirePositiveMoney("quantity")
        val unitPrice = request.unitPrice.requireNonNegativeMoney("unitPrice")
        val subtotal = quantity.multiply(unitPrice).money()
        val taxRate = request.taxRate?.requireRate("taxRate") ?: defaultVatRate(actor.tenantId)
        val taxAmount = subtotal.multiply(taxRate).money()
        val amount = subtotal.add(taxAmount).money()
        val taxRatePercent = taxRate.multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
        val chargeId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO folio_charges (
                id, tenant_id, property_id, folio_id, revenue_center_id, charge_type, description,
                source_type, source_id, quantity, unit_price, subtotal, tax_rate, tax_amount,
                amount, posted_by, status
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED')
            """.trimIndent(),
            chargeId,
            actor.tenantId,
            propertyId,
            folioId,
            request.revenueCenterId,
            chargeType,
            description,
            request.sourceType?.trimmedOrNull(),
            request.sourceId,
            quantity,
            unitPrice,
            subtotal,
            taxRatePercent,
            taxAmount,
            amount,
            actor.tenantUserId,
        )
        if (taxAmount > BigDecimal.ZERO) {
            val taxRateId = defaultVatRateId(actor.tenantId)
            jdbcTemplate.update(
                """
                INSERT INTO folio_charge_taxes (
                    tenant_id, folio_charge_id, tax_rate_id, tax_type,
                    taxable_amount, rate, tax_amount, is_inclusive
                )
                VALUES (?, ?, ?, 'vat', ?, ?, ?, false)
                """.trimIndent(),
                actor.tenantId,
                chargeId,
                taxRateId,
                subtotal,
                taxRate,
                taxAmount,
            )
        }
        recalculateFolio(folioId)
        recordSideEffects(
            tenantId = actor.tenantId,
            propertyId = propertyId,
            action = "billing.charge.posted",
            eventType = "billing.charge.posted",
            aggregateType = FOLIO_CHARGES,
            aggregateId = chargeId,
            payload = mapOf(
                "propertyId" to propertyId,
                "folioId" to folioId,
                "chargeId" to chargeId,
                "chargeType" to chargeType,
                "amount" to amount,
            ),
            idempotencyKeyId = idempotencyKeyId,
            destination = OutboxDestination.PLATFORM,
        )
        return chargeId
    }

    private fun issueInvoiceInternal(
        actor: TenantActor,
        propertyId: UUID,
        folioId: UUID,
        request: IssueInvoiceRequest,
        idempotencyKeyId: UUID,
    ): InvoiceResponse {
        require(request.dueDateDays >= 0) {
            "dueDateDays must not be negative"
        }
        val folio = requireFolio(actor.tenantId, propertyId, folioId, lock = true)
        require(folio.totalPaid.money() == folio.totalAmount.money()) {
            "Invoice can be issued only after the folio is fully settled"
        }
        val existing = jdbcTemplate.query(
            """
            SELECT id, folio_id, property_id, invoice_number_formatted, subtotal, vat_total,
                   service_charge, tourism_levy, total, status, issued_at
            FROM invoices
            WHERE tenant_id = ?
              AND property_id = ?
              AND folio_id = ?
              AND status IN ('issued', 'sent', 'paid')
              AND deleted_at IS NULL
            ORDER BY issued_at DESC
            LIMIT 1
            """.trimIndent(),
            ::mapInvoice,
            actor.tenantId,
            propertyId,
            folioId,
        ).singleOrNull()
        if (existing != null) {
            return existing
        }
        val chargeCount = count(
            """
            SELECT COUNT(*)
            FROM folio_charges
            WHERE tenant_id = ?
              AND property_id = ?
              AND folio_id = ?
              AND status = 'POSTED'
              AND deleted_at IS NULL
            """.trimIndent(),
            actor.tenantId,
            propertyId,
            folioId,
        )
        if (chargeCount == 0) {
            throw BillingConflictException("Invoice cannot be issued for a folio with no posted charges")
        }

        val invoiceId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO invoices (id, tenant_id, property_id, folio_id, status, due_date, created_by)
            VALUES (?, ?, ?, ?, 'draft', ?, ?)
            """.trimIndent(),
            invoiceId,
            actor.tenantId,
            propertyId,
            folioId,
            LocalDate.now().plusDays(request.dueDateDays.toLong()),
            actor.tenantUserId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO invoice_items (
                id, tenant_id, property_id, invoice_id, revenue_center_id,
                description, amount, vat_amount, status
            )
            SELECT gen_random_uuid(), tenant_id, property_id, ?, revenue_center_id,
                   description, subtotal, tax_amount, 'POSTED'
            FROM folio_charges
            WHERE tenant_id = ?
              AND property_id = ?
              AND folio_id = ?
              AND status = 'POSTED'
              AND deleted_at IS NULL
            """.trimIndent(),
            invoiceId,
            actor.tenantId,
            propertyId,
            folioId,
        )
        upsertInvoiceSequence(actor.tenantId)
        recalculateInvoice(invoiceId)
        jdbcTemplate.queryForList("SELECT assert_invoice_totals(?)", invoiceId)
        val formattedNumber = allocateInvoiceNumber(actor.tenantId)
        jdbcTemplate.update(
            """
            UPDATE invoices
            SET invoice_number_formatted = ?,
                status = 'issued',
                issued_at = now(),
                updated_at = now()
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
              AND status = 'draft'
            """.trimIndent(),
            formattedNumber,
            actor.tenantId,
            propertyId,
            invoiceId,
        )
        val invoice = requireNotNull(getInvoiceInContext(actor.tenantId, propertyId, invoiceId)) {
            "Issued invoice was not readable"
        }
        recordSideEffects(
            tenantId = actor.tenantId,
            propertyId = propertyId,
            action = "billing.invoice.issued",
            eventType = "billing.invoice.issued",
            aggregateType = INVOICES,
            aggregateId = invoice.id,
            payload = mapOf(
                "propertyId" to propertyId,
                "folioId" to folio.id,
                "invoiceId" to invoice.id,
                "invoiceNumber" to invoice.invoiceNumber,
                "total" to invoice.total,
            ),
            idempotencyKeyId = idempotencyKeyId,
            destination = OutboxDestination.FISCAL,
        )
        return invoice
    }

    private fun charges(tenantId: UUID, propertyId: UUID, folioId: UUID): List<FolioChargeResponse> {
        return jdbcTemplate.query(
            """
            SELECT id, folio_id, property_id, revenue_center_id, charge_type, description,
                   quantity, unit_price, subtotal, tax_rate, tax_amount, amount, status, posted_at
            FROM folio_charges
            WHERE tenant_id = ?
              AND property_id = ?
              AND folio_id = ?
              AND deleted_at IS NULL
            ORDER BY posted_at, created_at
            """.trimIndent(),
            ::mapCharge,
            tenantId,
            propertyId,
            folioId,
        )
    }

    private fun payments(tenantId: UUID, propertyId: UUID, folioId: UUID): List<FolioPaymentResponse> {
        return jdbcTemplate.query(
            """
            SELECT id, folio_id, property_id, payment_method, amount, reference_number, status, paid_at
            FROM folio_payments
            WHERE tenant_id = ?
              AND property_id = ?
              AND folio_id = ?
              AND deleted_at IS NULL
            ORDER BY paid_at NULLS LAST, created_at
            """.trimIndent(),
            ::mapPayment,
            tenantId,
            propertyId,
            folioId,
        )
    }

    private fun invoices(tenantId: UUID, propertyId: UUID, folioId: UUID): List<InvoiceResponse> {
        return jdbcTemplate.query(
            """
            SELECT id, folio_id, property_id, invoice_number_formatted, subtotal, vat_total,
                   service_charge, tourism_levy, total, status, issued_at
            FROM invoices
            WHERE tenant_id = ?
              AND property_id = ?
              AND folio_id = ?
              AND deleted_at IS NULL
            ORDER BY issued_at DESC NULLS LAST, created_at DESC
            """.trimIndent(),
            ::mapInvoice,
            tenantId,
            propertyId,
            folioId,
        )
    }

    private fun getInvoiceInContext(
        tenantId: UUID,
        propertyId: UUID,
        invoiceId: UUID,
    ): InvoiceResponse? {
        return jdbcTemplate.query(
            """
            SELECT id, folio_id, property_id, invoice_number_formatted, subtotal, vat_total,
                   service_charge, tourism_levy, total, status, issued_at
            FROM invoices
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
              AND deleted_at IS NULL
            """.trimIndent(),
            ::mapInvoice,
            tenantId,
            propertyId,
            invoiceId,
        ).singleOrNull()
    }

    private fun requireFolio(
        tenantId: UUID,
        propertyId: UUID,
        folioId: UUID,
        lock: Boolean,
    ): FolioRecord {
        val rows = jdbcTemplate.query(
            """
            SELECT id, status, total_amount, total_paid
            FROM folios
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
              AND deleted_at IS NULL
            ${if (lock) "FOR UPDATE" else ""}
            """.trimIndent(),
            { rs, _ ->
                FolioRecord(
                    id = rs.getObject("id", UUID::class.java),
                    status = rs.getString("status"),
                    totalAmount = rs.getBigDecimal("total_amount").money(),
                    totalPaid = rs.getBigDecimal("total_paid").money(),
                )
            },
            tenantId,
            propertyId,
            folioId,
        )
        val folio = rows.singleOrNull() ?: throw BillingNotFoundException("Folio was not found")
        if (folio.status != "open") {
            throw BillingConflictException("Folio is ${folio.status} and cannot be changed")
        }
        return folio
    }

    private fun reservationRoomForCharge(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
    ): ReservationRoomChargeRecord? {
        return jdbcTemplate.query(
            """
            SELECT rr.id AS reservation_room_id,
                   rr.folio_id,
                   rr.check_in_date,
                   rr.check_out_date,
                   rr.rate_per_night,
                   rt.name AS room_type_name,
                   rm.room_number,
                   r.created_by
            FROM reservation_rooms rr
            JOIN reservations r ON r.tenant_id = rr.tenant_id AND r.id = rr.reservation_id
            JOIN room_types rt ON rt.tenant_id = rr.tenant_id AND rt.id = rr.room_type_id
            LEFT JOIN rooms rm ON rm.tenant_id = rr.tenant_id AND rm.id = rr.room_id
            WHERE rr.tenant_id = ?
              AND r.property_id = ?
              AND rr.reservation_id = ?
              AND rr.status = 'checked_in'
              AND rr.folio_id IS NOT NULL
            ORDER BY rr.created_at
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                ReservationRoomChargeRecord(
                    reservationRoomId = rs.getObject("reservation_room_id", UUID::class.java),
                    folioId = rs.getObject("folio_id", UUID::class.java),
                    checkInDate = rs.getObject("check_in_date", LocalDate::class.java),
                    checkOutDate = rs.getObject("check_out_date", LocalDate::class.java),
                    ratePerNight = rs.getBigDecimal("rate_per_night").money(),
                    roomTypeName = rs.getString("room_type_name"),
                    roomNumber = rs.getString("room_number"),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                )
            },
            tenantId,
            propertyId,
            reservationId,
        ).singleOrNull()
    }

    private fun defaultVatRate(tenantId: UUID): BigDecimal {
        return jdbcTemplate.query(
            """
            SELECT rate
            FROM tax_rates
            WHERE tenant_id = ?
              AND tax_type = 'vat'
              AND is_active = true
              AND effective_from <= CURRENT_DATE
              AND (effective_to IS NULL OR effective_to > CURRENT_DATE)
            ORDER BY effective_from DESC, created_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getBigDecimal("rate") },
            tenantId,
        ).singleOrNull()?.setScale(6, RoundingMode.HALF_UP) ?: BigDecimal.ZERO.setScale(6)
    }

    private fun defaultVatRateId(tenantId: UUID): UUID? {
        return jdbcTemplate.query(
            """
            SELECT id
            FROM tax_rates
            WHERE tenant_id = ?
              AND tax_type = 'vat'
              AND is_active = true
              AND effective_from <= CURRENT_DATE
              AND (effective_to IS NULL OR effective_to > CURRENT_DATE)
            ORDER BY effective_from DESC, created_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            tenantId,
        ).singleOrNull()
    }

    private fun upsertInvoiceSequence(tenantId: UUID) {
        val year = LocalDate.now().year
        jdbcTemplate.update(
            """
            INSERT INTO document_sequences (tenant_id, document_type, prefix, year, next_value, padding)
            VALUES (?, 'invoice', 'INV', ?, 1, 5)
            ON CONFLICT (tenant_id, document_type, year) DO NOTHING
            """.trimIndent(),
            tenantId,
            year,
        )
    }

    private fun allocateInvoiceNumber(tenantId: UUID): String {
        val year = LocalDate.now().year.toShort()
        return requireNotNull(
            jdbcTemplate.query(
                "SELECT formatted_document_number FROM allocate_document_number(?, 'invoice', ?)",
                { rs, _ -> rs.getString("formatted_document_number") },
                tenantId,
                year,
            ).singleOrNull(),
        ) {
            "Invoice document sequence did not return a number"
        }
    }

    private fun recalculateFolio(folioId: UUID) {
        jdbcTemplate.queryForList("SELECT recalculate_folio_totals(?)", folioId)
    }

    private fun recalculateInvoice(invoiceId: UUID) {
        jdbcTemplate.queryForList("SELECT recalculate_invoice_totals(?)", invoiceId)
    }

    private fun <T : Any> mutate(
        propertyId: UUID,
        operationType: String,
        requestPayload: Any,
        resourceType: String,
        replayType: Class<T>,
        block: (TenantActor, UUID) -> T,
    ): T {
        return requireNotNull(
            transactionTemplate.execute {
                val actor = bindActor(propertyId, lockProperty = false)
                val reservation = idempotencyPort.reserve(
                    IdempotencyCommand(
                        operationType = operationType,
                        requestPayload = requestPayload,
                        resourceType = resourceType,
                    ),
                )
                when (reservation) {
                    is IdempotencyReservation.Started -> {
                        try {
                            val response = block(actor, reservation.recordId)
                            idempotencyPort.markSucceeded(
                                recordId = reservation.recordId,
                                responseCode = 200,
                                responseBody = response,
                                resourceId = resourceId(response),
                            )
                            meterRegistry.counter("peak.billing.command", "operation", operationType, "result", "succeeded").increment()
                            response
                        } catch (ex: DataIntegrityViolationException) {
                            throw BillingConflictException(ex.publicDatabaseMessage())
                        }
                    }

                    is IdempotencyReservation.Replay -> {
                        if (reservation.responseBody.isNullOrBlank()) {
                            throw BillingConflictException("Billing command replay does not contain a stored response body")
                        }
                        objectMapper.readValue(reservation.responseBody, replayType).withReplayFlag()
                    }

                    is IdempotencyReservation.InProgress -> {
                        meterRegistry.counter("peak.billing.command", "operation", operationType, "result", "in_progress").increment()
                        throw BillingInProgressException("Billing command is already being processed for this idempotency key")
                    }

                    is IdempotencyReservation.Conflict -> {
                        meterRegistry.counter("peak.billing.command", "operation", operationType, "result", "conflict").increment()
                        throw BillingConflictException("Idempotency key was already used for a different billing request")
                    }
                }
            },
        )
    }

    private fun <T> read(propertyId: UUID, block: (TenantActor) -> T): T {
        return requireNotNull(
            transactionTemplate.execute {
                block(bindActor(propertyId, lockProperty = false))
            },
        )
    }

    private fun bindActor(propertyId: UUID, lockProperty: Boolean): TenantActor {
        val actor = tenantRequestContext.bind()
        tenantRequestContext.requirePropertyUsable(actor.tenantId, propertyId, lockProperty)
        return actor
    }

    private fun requireActiveContext(tenantId: UUID, propertyId: UUID) {
        tenantRequestContext.requireTenantUsable(tenantId)
        tenantRequestContext.requirePropertyUsable(tenantId, propertyId, lock = false)
    }

    private fun tenantUserFromContext(): UUID {
        return tenantRequestContext.bind().tenantUserId
    }

    private fun recordSideEffects(
        tenantId: UUID,
        propertyId: UUID,
        action: String,
        eventType: String,
        aggregateType: String,
        aggregateId: UUID,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID?,
        destination: OutboxDestination,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = action,
                resource = AuditResource(aggregateType, aggregateId),
                after = payload,
            ),
        )
        if (idempotencyKeyId != null) {
            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = aggregateType,
                    aggregateId = aggregateId,
                    tenantId = tenantId,
                    propertyId = propertyId,
                    eventType = eventType,
                    destination = destination,
                    payload = payload,
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 4,
                ),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> T.withReplayFlag(): T {
        return when (this) {
            is BillingMutationReceipt -> copy(replayed = true) as T
            else -> this
        }
    }

    private fun resourceId(response: Any): UUID? {
        return when (response) {
            is BillingMutationReceipt -> response.resourceId
            is InvoiceResponse -> response.id
            else -> null
        }
    }

    private fun mapFolioSummary(rs: ResultSet, rowNumber: Int): FolioResponse {
        val totalAmount = rs.getBigDecimal("total_amount").money()
        val totalPaid = rs.getBigDecimal("total_paid").money()
        return FolioResponse(
            id = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            reservationId = rs.getObject("reservation_id", UUID::class.java),
            status = rs.getString("status"),
            currencyCode = rs.getString("currency_code").trim(),
            subtotal = rs.getBigDecimal("subtotal").money(),
            taxAmount = rs.getBigDecimal("tax_amount").money(),
            serviceCharge = rs.getBigDecimal("service_charge").money(),
            tourismLevy = rs.getBigDecimal("tourism_levy").money(),
            totalAmount = totalAmount,
            totalPaid = totalPaid,
            balanceDue = totalAmount.subtract(totalPaid).money(),
        )
    }

    private fun mapCharge(rs: ResultSet, rowNumber: Int): FolioChargeResponse {
        return FolioChargeResponse(
            id = rs.getObject("id", UUID::class.java),
            folioId = rs.getObject("folio_id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            revenueCenterId = rs.getObject("revenue_center_id", UUID::class.java),
            chargeType = rs.getString("charge_type"),
            description = rs.getString("description"),
            quantity = rs.getBigDecimal("quantity").setScale(3, RoundingMode.HALF_UP),
            unitPrice = rs.getBigDecimal("unit_price").money(),
            subtotal = rs.getBigDecimal("subtotal").money(),
            taxRate = rs.getBigDecimal("tax_rate").setScale(2, RoundingMode.HALF_UP),
            taxAmount = rs.getBigDecimal("tax_amount").money(),
            amount = rs.getBigDecimal("amount").money(),
            status = rs.getString("status"),
            postedAt = rs.getTimestamp("posted_at").toInstant(),
        )
    }

    private fun mapPayment(rs: ResultSet, rowNumber: Int): FolioPaymentResponse {
        return FolioPaymentResponse(
            id = rs.getObject("id", UUID::class.java),
            folioId = rs.getObject("folio_id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            paymentMethod = rs.getString("payment_method"),
            amount = rs.getBigDecimal("amount").money(),
            referenceNumber = rs.getString("reference_number"),
            status = rs.getString("status"),
            paidAt = rs.getTimestamp("paid_at")?.toInstant(),
        )
    }

    private fun mapInvoice(rs: ResultSet, rowNumber: Int): InvoiceResponse {
        return InvoiceResponse(
            id = rs.getObject("id", UUID::class.java),
            folioId = rs.getObject("folio_id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            invoiceNumber = rs.getString("invoice_number_formatted"),
            subtotal = rs.getBigDecimal("subtotal").money(),
            vatTotal = rs.getBigDecimal("vat_total").money(),
            serviceCharge = rs.getBigDecimal("service_charge").money(),
            tourismLevy = rs.getBigDecimal("tourism_levy").money(),
            total = rs.getBigDecimal("total").money(),
            status = rs.getString("status"),
            issuedAt = rs.getTimestamp("issued_at")?.toInstant(),
        )
    }

    private fun exists(sql: String, vararg args: Any?): Boolean {
        return jdbcTemplate.queryForObject(sql, Boolean::class.java, *args) == true
    }

    private fun count(sql: String, vararg args: Any?): Int {
        return jdbcTemplate.queryForObject(sql, Int::class.java, *args) ?: 0
    }

    private fun nightsBetween(start: LocalDate, end: LocalDate): Int {
        val nights = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt()
        require(nights > 0) {
            "Reservation must include at least one night"
        }
        return nights
    }

    private data class FolioRecord(
        val id: UUID,
        val status: String,
        val totalAmount: BigDecimal,
        val totalPaid: BigDecimal,
    )

    private data class FolioTotals(
        val id: UUID,
        val totalAmount: BigDecimal,
        val totalPaid: BigDecimal,
    )

    private data class ReservationRoomChargeRecord(
        val reservationRoomId: UUID,
        val folioId: UUID,
        val checkInDate: LocalDate,
        val checkOutDate: LocalDate,
        val ratePerNight: BigDecimal,
        val roomTypeName: String,
        val roomNumber: String?,
        val createdBy: UUID?,
    )

    private data class ReversibleCharge(
        val revenueCenterId: UUID?,
        val chargeType: String,
        val description: String,
        val sourceType: String?,
        val sourceId: UUID?,
        val quantity: BigDecimal,
        val unitPrice: BigDecimal,
        val subtotal: BigDecimal,
        val taxRate: BigDecimal,
        val taxAmount: BigDecimal,
        val amount: BigDecimal,
    )

    private data class PaymentReversalSource(
        val paymentId: UUID,
        val folioId: UUID,
        val paymentMethod: String,
        val amount: BigDecimal,
        val cashSessionId: UUID?,
        val status: String,
        val reversed: Boolean,
    )

    companion object {
        const val FOLIOS = "folios"
        const val FOLIO_CHARGES = "folio_charges"
        const val INVOICES = "invoices"
        val VALID_CHARGE_TYPES = setOf("ROOM", "F&B", "LAUNDRY", "MINIBAR", "SPA", "PARKING", "TELEPHONE", "TRANSFER", "TAX", "FEE", "MISC")
        val VALID_PAYMENT_METHODS = setOf("cash", "mobile_money")
    }
}

private fun String.normalizedRequired(field: String): String {
    return trim().takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("$field is required")
}

private fun String.trimmedOrNull(): String? {
    return trim().takeIf { it.isNotEmpty() }
}

private fun String.normalizedChargeType(): String {
    val normalized = trim().uppercase()
    require(normalized in BillingService.VALID_CHARGE_TYPES) {
        "Invalid folio charge type: $this"
    }
    return normalized
}

private fun String.normalizedPaymentMethod(): String {
    val normalized = trim().lowercase()
    require(normalized in BillingService.VALID_PAYMENT_METHODS) {
        "Only cash and mobile_money payments are allowed in Phase 3"
    }
    return normalized
}

private fun BigDecimal.money(): BigDecimal {
    return setScale(2, RoundingMode.HALF_UP)
}

private fun BigDecimal.requirePositiveMoney(field: String): BigDecimal {
    val normalized = money()
    require(normalized > BigDecimal.ZERO) {
        "$field must be positive"
    }
    return normalized
}

private fun BigDecimal.requireNonNegativeMoney(field: String): BigDecimal {
    val normalized = money()
    require(normalized >= BigDecimal.ZERO) {
        "$field must not be negative"
    }
    return normalized
}

private fun BigDecimal.requireRate(field: String): BigDecimal {
    require(this >= BigDecimal.ZERO && this <= BigDecimal.ONE) {
        "$field must be a decimal rate between 0 and 1"
    }
    return setScale(6, RoundingMode.HALF_UP)
}

private fun DataIntegrityViolationException.publicDatabaseMessage(): String {
    return "Billing request conflicts with existing financial data"
}
