package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * REST backfill for the committed-event stream.
 *
 * A client that connects after events were committed must not see a silent gap: it fetches
 * everything after its last known cursor from the journal, then subscribes to the live
 * stream and drops live envelopes at or below the cursor. Both paths carry the canonical
 * envelope, and both are authorized against the same entitlement the subscription
 * authorizer checks — replay is not a second door around stream access control.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RealtimeEventReplayService(
    private val journal: RealtimeEventJournal,
    private val authorizer: RealtimeSubscriptionAuthorizer,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionTemplate: TransactionTemplate,
) {
    fun replay(propertyId: java.util.UUID, after: Long?, limit: Int): RealtimeReplayPage {
        val identity = requestContextHolder.current().identity
        if (identity !is RequestIdentity.Tenant) {
            throw AccessDeniedException("Realtime replay requires an authenticated tenant identity")
        }
        if (!authorizer.canSubscribeDestination(
                identity,
                RealtimeSubscriptionTarget.PropertyOperations(propertyId),
            )
        ) {
            throw AccessDeniedException("Realtime stream access is not granted for this property")
        }
        val events = transactionTemplate.execute {
            databaseSessionContext.bind(identity)
            journal.replayPage(identity.tenantId, propertyId, after ?: 0L, limit)
        }
        return RealtimeReplayPage(
            events = events.map { RealtimeStreamService.envelope(it) },
            nextCursor = events.lastOrNull()?.sequenceId,
        )
    }
}

data class RealtimeReplayPage(
    val events: List<Map<String, Any?>>,
    val nextCursor: Long?,
)