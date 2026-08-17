package com.mwombeki.peak.platformbilling

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The rule that keeps an operator from manufacturing financial truth.
 *
 * > No platform-admin reconciliation action may directly mutate grants, subscriptions,
 * > entitlements, module activation or receipts. Its only output is a normalized payment
 * > observation that enters the ordinary settlement machinery.
 *
 * ```
 *   AdminController -> OperatorReconciliationService -> observation
 *                                                          |
 *                                     PaymentConfirmationService (the only settler)
 * ```
 *
 * never
 *
 * ```
 *   AdminController -> UPDATE peak_purchases SET status = 'paid' -> enable modules
 * ```
 *
 * The second shape would look like a small convenience and would route around every
 * guarantee the settlement path provides: idempotency, exactly-once grants, receipts,
 * convergence, and the audit trail. A support engineer under pressure would reach for it.
 */
class OperatorReconciliationInvariantTests {

    private val operatorPath = Path.of(
        "src/main/kotlin/com/mwombeki/peak/platformbilling/internal/OperatorReconciliationService.kt",
    )
    private val adminControllerPath = Path.of(
        "src/main/kotlin/com/mwombeki/peak/platformbilling/internal/web/PlatformBillingAdminController.kt",
    )

    @Test
    fun theOperatorPathNeverWritesTheThingsSettlementOwns() {
        val source = Files.readString(operatorPath)
        val forbidden = FORBIDDEN_TABLES.filter { table ->
            Regex(
                """(INSERT\s+INTO|UPDATE|DELETE\s+FROM)\s+$table\b""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(source)
        }

        assertTrue(
            forbidden.isEmpty(),
            "the operator path writes $forbidden directly. An operator records an " +
                "observation; the settlement engine decides what that means. Writing these " +
                "here would bypass idempotency, exactly-once grants, receipts and " +
                "convergence all at once.",
        )
    }

    @Test
    fun theOperatorPathSettlesOnlyThroughTheSharedConfirmationService() {
        val source = Files.readString(operatorPath)

        assertTrue(
            source.contains("confirmationService.confirm(") ||
                source.contains("confirmationService.reject("),
            "the operator path must reach settlement through PaymentConfirmationService",
        )
        assertTrue(
            source.contains("ConfirmationSource.OPERATOR"),
            "an operator-sourced settlement must be attributable as one, so it can be told " +
                "apart from a provider's own confirmation later",
        )
    }

    @Test
    fun theAdminControllerHoldsNoBusinessLogicOfItsOwn() {
        val source = Files.readString(adminControllerPath)
        val leaked = FORBIDDEN_TABLES.filter { source.contains(it) }

        assertTrue(
            leaked.isEmpty(),
            "the admin controller mentions $leaked. It should delegate and nothing else.",
        )
    }

    /**
     * Confirming a payment and choosing not to collect a debt are different decisions.
     *
     * A waiver, a credit or a write-off routed through CONFIRMED_PAID would record revenue
     * that never arrived and make the books fiction. Keeping the words out of this path is a
     * cheap guard against someone reaching for the nearest button.
     */
    @Test
    fun debtForgivenessIsNotReconciliation() {
        val source = Files.readString(operatorPath).lowercase()
        val commercialWords = listOf("write_off", "writeoff", "waiver", "goodwill", "comp_")

        val leaked = commercialWords.filter { word ->
            // Only outside comments: the class documentation explains why these are excluded.
            source.lines()
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .any { it.contains(word) }
        }

        assertTrue(
            leaked.isEmpty(),
            "deciding not to collect a debt is a commercial decision needing its own audited " +
                "workflow, not a reconciliation outcome. Found $leaked",
        )
    }

    private companion object {
        /** What only the settlement engine may write. */
        val FORBIDDEN_TABLES = listOf(
            "peak_purchases",
            "peak_product_grants",
            "peak_receipts",
            "tenant_subscriptions",
            "tenant_modules",
            "property_modules",
            "tenant_control_states",
        )
    }
}
