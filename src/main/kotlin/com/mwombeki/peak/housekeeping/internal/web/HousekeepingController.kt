package com.mwombeki.peak.housekeeping.internal.web

import com.mwombeki.peak.housekeeping.api.AssignHousekeepingTaskRequest
import com.mwombeki.peak.housekeeping.api.CompleteHousekeepingTaskRequest
import com.mwombeki.peak.housekeeping.api.CreateHousekeepingTaskRequest
import com.mwombeki.peak.housekeeping.api.CreateLostAndFoundRequest
import com.mwombeki.peak.housekeeping.api.HousekeepingBoardResponse
import com.mwombeki.peak.housekeeping.api.HousekeepingPort
import com.mwombeki.peak.housekeeping.api.HousekeepingReasonRequest
import com.mwombeki.peak.housekeeping.api.HousekeepingSettingsResponse
import com.mwombeki.peak.housekeeping.api.HousekeepingTaskResponse
import com.mwombeki.peak.housekeeping.api.InspectHousekeepingTaskRequest
import com.mwombeki.peak.housekeeping.api.LostAndFoundResponse
import com.mwombeki.peak.housekeeping.api.LostAndFoundStatus
import com.mwombeki.peak.housekeeping.api.LostAndFoundTransitionRequest
import com.mwombeki.peak.housekeeping.api.UpdateHousekeepingSettingsRequest
import jakarta.validation.Valid
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}")
class HousekeepingController(private val port: HousekeepingPort) {
    @GetMapping("/housekeeping/settings")
    fun settings(@PathVariable propertyId: UUID): HousekeepingSettingsResponse =
        port.getSettings(propertyId)

    @PutMapping("/housekeeping/settings")
    fun updateSettings(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: UpdateHousekeepingSettingsRequest,
    ) = port.updateSettings(propertyId, request)

    @GetMapping("/housekeeping/board")
    fun board(
        @PathVariable propertyId: UUID,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        date: LocalDate?,
    ): HousekeepingBoardResponse = port.board(propertyId, date)

    @PostMapping("/housekeeping/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    fun createTask(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreateHousekeepingTaskRequest,
    ) = port.createTask(propertyId, request)

    @PostMapping("/housekeeping/tasks/{taskId}/assign")
    fun assign(
        @PathVariable propertyId: UUID,
        @PathVariable taskId: UUID,
        @Valid @RequestBody request: AssignHousekeepingTaskRequest,
    ) = port.assignTask(propertyId, taskId, request)

    @PostMapping("/housekeeping/tasks/{taskId}/start")
    fun start(@PathVariable propertyId: UUID, @PathVariable taskId: UUID) =
        port.startTask(propertyId, taskId)

    @PostMapping("/housekeeping/tasks/{taskId}/complete")
    fun complete(
        @PathVariable propertyId: UUID,
        @PathVariable taskId: UUID,
        @Valid @RequestBody request: CompleteHousekeepingTaskRequest,
    ) = port.completeTask(propertyId, taskId, request)

    @PostMapping("/housekeeping/tasks/{taskId}/inspect")
    fun inspect(
        @PathVariable propertyId: UUID,
        @PathVariable taskId: UUID,
        @Valid @RequestBody request: InspectHousekeepingTaskRequest,
    ) = port.inspectTask(propertyId, taskId, request)

    @PostMapping("/housekeeping/tasks/{taskId}/skip")
    fun skip(
        @PathVariable propertyId: UUID,
        @PathVariable taskId: UUID,
        @Valid @RequestBody request: HousekeepingReasonRequest,
    ) = port.skipTask(propertyId, taskId, request)

    @PostMapping("/housekeeping/tasks/{taskId}/cancel")
    fun cancel(
        @PathVariable propertyId: UUID,
        @PathVariable taskId: UUID,
        @Valid @RequestBody request: HousekeepingReasonRequest,
    ) = port.cancelTask(propertyId, taskId, request)

    @GetMapping("/lost-and-found")
    fun lostAndFound(@PathVariable propertyId: UUID): List<LostAndFoundResponse> =
        port.listLostAndFound(propertyId)

    @PostMapping("/lost-and-found")
    @ResponseStatus(HttpStatus.CREATED)
    fun recordLostAndFound(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreateLostAndFoundRequest,
    ) = port.createLostAndFound(propertyId, request)

    @PostMapping("/lost-and-found/{itemId}/claim")
    fun claim(
        @PathVariable propertyId: UUID,
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: LostAndFoundTransitionRequest,
    ) = port.transitionLostAndFound(propertyId, itemId, LostAndFoundStatus.CLAIMED, request)

    @PostMapping("/lost-and-found/{itemId}/return")
    fun returnItem(
        @PathVariable propertyId: UUID,
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: LostAndFoundTransitionRequest,
    ) = port.transitionLostAndFound(propertyId, itemId, LostAndFoundStatus.RETURNED, request)

    @PostMapping("/lost-and-found/{itemId}/dispose")
    fun dispose(
        @PathVariable propertyId: UUID,
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: LostAndFoundTransitionRequest,
    ) = port.transitionLostAndFound(propertyId, itemId, LostAndFoundStatus.DISPOSED, request)

    @PostMapping("/lost-and-found/{itemId}/donate")
    fun donate(
        @PathVariable propertyId: UUID,
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: LostAndFoundTransitionRequest,
    ) = port.transitionLostAndFound(propertyId, itemId, LostAndFoundStatus.DONATED, request)
}
