package com.mwombeki.peak.shared.ephemeral

import java.time.Duration
import org.springframework.modulith.NamedInterface

/**
 * A fixed-window counter for the flows that must not be allowed to run unbounded.
 *
 * Callers see counters and decisions and nothing else. Which store answers — the Valkey
 * cache or the PostgreSQL table behind it — is a deployment detail, and no caller may
 * branch on it, because the moment one does, an outage starts changing behaviour instead
 * of changing latency.
 *
 * The store counts. It does not decide what a refusal means, does not persist why one
 * happened, and holds nothing another system needs to be correct. Anything a dispute, an
 * audit or a night audit could ask about lives in PostgreSQL.
 */
@NamedInterface("ephemeral")
interface RateLimitStore {

    /**
     * Records one attempt against `subject` and reports whether it is within `limit`.
     *
     * The window starts at the first attempt and does not slide, so a subject that is
     * refused is refused for the remainder of that window and no longer.
     */
    fun consume(
        scope: RateLimitScope,
        subject: String,
        limit: Int,
        window: Duration,
    ): RateLimitDecision

    /** Clears the counter, for the one caller that has proof the subject is legitimate. */
    fun reset(scope: RateLimitScope, subject: String)
}

/**
 * The counters Peak is allowed to keep outside PostgreSQL.
 *
 * Closed on purpose. Every member is a coarse abuse bound whose loss costs a window of
 * throttling and nothing else; a new one is added here deliberately, after asking whether
 * the thing being counted is evidence. Verification attempt budgets, OTP challenge state
 * and anything a decision is later justified by are evidence, and belong in a table.
 */
@NamedInterface("ephemeral")
enum class RateLimitScope(val keyspace: String) {
    /** Unauthenticated request volume from one source address. */
    REQUESTS_PER_IP("ip"),

    /** Request volume aimed at one phone number, across addresses. */
    REQUESTS_PER_PHONE("phone"),

    /** Request volume attributable to one account, across addresses. */
    REQUESTS_PER_ACCOUNT("account"),

    /** How often a one-time code may be sent, not how often one may be checked. */
    OTP_SEND_COOLDOWN("otp-send"),

    /** Wrong staff PINs at a terminal. */
    STAFF_PIN_ATTEMPTS("staff-pin"),

    /** Failed staff sign-ins. */
    STAFF_LOGIN_ATTEMPTS("staff-login"),

    /** Coarse bound on device pairing traffic, above the per-tenant miss ledger. */
    DEVICE_PAIRING_ATTEMPTS("device-pairing"),
}

/**
 * @param used attempts recorded in the current window, including this one.
 * @param retryAfter how long until the window ends; [Duration.ZERO] while allowed.
 */
@NamedInterface("ephemeral")
data class RateLimitDecision(
    val allowed: Boolean,
    val used: Long,
    val limit: Int,
    val retryAfter: Duration,
)

/**
 * The ephemeral store could not answer.
 *
 * Thrown by the Valkey adapters only, and caught only by the fallback pair, which turns it
 * into a PostgreSQL call. It exists so that "Valkey is unreachable" is a distinguishable
 * event rather than a generic data access failure that some future `catch` swallows into
 * an allow.
 */
class EphemeralStoreUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
