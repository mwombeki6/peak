package com.mwombeki.peak.realtime.internal

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RealtimeJournalHealthIndicator(
    private val journal: RealtimeEventJournal,
) : HealthIndicator {
    override fun health(): Health {
        return try {
            Health.up()
                .withDetail("latestSequence", journal.latestSequence())
                .build()
        } catch (ex: Exception) {
            Health.down()
                .withDetail("reason", "journal_unavailable")
                .build()
        }
    }
}
