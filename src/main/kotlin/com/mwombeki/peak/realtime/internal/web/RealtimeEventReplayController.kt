package com.mwombeki.peak.realtime.internal.web

import com.mwombeki.peak.realtime.internal.RealtimeEventReplayService
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
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