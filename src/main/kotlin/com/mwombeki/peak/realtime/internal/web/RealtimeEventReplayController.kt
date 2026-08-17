package com.mwombeki.peak.realtime.internal.web

import com.mwombeki.peak.realtime.internal.RealtimeEventReplayService
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Gated exactly like [RealtimeEventReplayService], which it injects.
 *
 * `@RestController` is a `@Component`, so without this the controller was still
 * registered in the migration, bootstrap and worker runtimes — where the service
 * is deliberately absent — and every one of them died on an unsatisfied
 * dependency before reaching its actual job. `peak-migration` exited 1 without
 * running Flyway, which took the whole acceptance stack with it.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/properties/{propertyId}/realtime/events")
class RealtimeEventReplayController(
    private val replayService: RealtimeEventReplayService,
) {
    @GetMapping
    fun replay(
        @PathVariable propertyId: UUID,
        @RequestParam(name = "after", required = false) after: Long?,
        @RequestParam(name = "limit", defaultValue = "200") limit: Int,
    ) = replayService.replay(propertyId, after, limit)
}