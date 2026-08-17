package com.mwombeki.peak.shared.outbound

import java.time.Duration
import org.springframework.modulith.NamedInterface

/**
 * Creation and lifecycle of a browser login identity, for the minority of people who need one.
 *
 * Peak has two ways of knowing who someone is, and this is the smaller one. Owners, managers
 * and platform staff sign in through the identity provider because they use a browser, consent
 * to things and cross tenant boundaries. Waiters, cashiers, housekeepers and porters do not:
 * they are a `users` row with a staff number, an operational property role and a one-time
 * activation secret, and they reach a till with a PIN. Most people Peak serves never appear
 * here at all, and routing them through this port would mint credentials nobody asked for.
 *
 * Because of that, and because onboarding begins with a phone rather than an inbox, an email
 * address is optional throughout. [ProvisionIdentity.username] is the stable identifier the
 * provider knows someone by — a phone number for a Tanzanian hotelier who has never used
 * email, an address for someone who has. Inventing a placeholder address to satisfy a schema
 * would be worse than having none, because the placeholder becomes a real way to log in.
 *
 * The seam also keeps provider vocabulary out of hospitality code. Realms, required actions and
 * credential representations stop at the adapter; a caller asks for an identity and receives a
 * subject.
 *
 * Peak never handles a password here, and there is deliberately no method to set one.
 */
@NamedInterface("outbound")
interface IdentityProvisionerPort {
    fun isHealthy(): Boolean

    /**
     * Returns the subject for [command], creating it when it does not yet exist.
     *
     * Idempotent by contract rather than by convention, because the dangerous failure is a
     * timeout *after* the provider committed: a retry that blindly created would leave two
     * identities for one person, and the second would silently shadow the first at login.
     * Implementations must therefore resolve an existing identity rather than duplicate it,
     * and must report which happened through [ProvisionedIdentity.alreadyExisted].
     */
    fun provision(command: ProvisionIdentity): ProvisionedIdentity

    /**
     * Asks the provider to email a one-time link for establishing credentials.
     *
     * Only available to an identity that has an email address. Everyone else activates through
     * Peak's own path — a one-time secret delivered by SMS, or handed over in person — which
     * never enters the provider at all, so callers must choose the route rather than assume
     * this one. Implementations reject a subject with no address instead of silently doing
     * nothing, since a manager who never receives a link and a manager who was never sent one
     * are indistinguishable from the outside.
     *
     * Separate from [provision] because the two fail independently: a failed send must not roll
     * back a good identity, as the link can be reissued and the identity cannot.
     */
    fun sendActivationLink(command: SendActivationLink)

    /** Revokes the ability to log in while preserving the subject, for suspension and offboarding. */
    fun disable(subjectId: String)

    /**
     * Removes a subject outright.
     *
     * Only for unwinding a provision whose Peak-side transaction then failed. Offboarding a
     * real person is [disable]: deleting them would strip the audit trail of who did what.
     */
    fun delete(subjectId: String)
}

data class ProvisionIdentity(
    /**
     * What the provider knows this person by, and the key an idempotent retry resolves on.
     *
     * A phone number in E.164 for someone who has no email, an address for someone who has.
     * Stable for the life of the identity: changing it later would strand every session and
     * every audit record that names it.
     */
    val username: String,
    val email: String? = null,
    val phoneNumber: String? = null,
    val firstName: String,
    val lastName: String,
    /** Carried to the provider so support can trace a login back to a tenant without a join. */
    val tenantId: String,
    val peakUserId: String,
)

data class ProvisionedIdentity(
    val subjectId: String,
    val alreadyExisted: Boolean,
)

data class SendActivationLink(
    val subjectId: String,
    val redirectUri: String,
    val lifetime: Duration,
)

/**
 * Raised when the provider cannot be made to reflect what Peak asked for.
 *
 * Distinct from an ordinary failure because the caller's obligation differs: Peak-side state
 * that assumes a working identity must not be committed, so this is the signal to unwind
 * rather than to retry in place.
 */
class IdentityProvisioningException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
