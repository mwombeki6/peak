package com.mwombeki.peak.housekeeping.api

import java.time.LocalDate
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface HousekeepingPort {
    fun getSettings(propertyId: UUID): HousekeepingSettingsResponse
    fun updateSettings(
        propertyId: UUID,
        request: UpdateHousekeepingSettingsRequest,
    ): HousekeepingSettingsResponse
    fun board(propertyId: UUID, date: LocalDate?): HousekeepingBoardResponse
    fun createTask(propertyId: UUID, request: CreateHousekeepingTaskRequest): HousekeepingTaskResponse
    fun assignTask(
        propertyId: UUID,
        taskId: UUID,
        request: AssignHousekeepingTaskRequest,
    ): HousekeepingTaskResponse
    fun startTask(propertyId: UUID, taskId: UUID): HousekeepingTaskResponse
    fun completeTask(
        propertyId: UUID,
        taskId: UUID,
        request: CompleteHousekeepingTaskRequest,
    ): HousekeepingTaskResponse
    fun inspectTask(
        propertyId: UUID,
        taskId: UUID,
        request: InspectHousekeepingTaskRequest,
    ): HousekeepingTaskResponse
    fun skipTask(
        propertyId: UUID,
        taskId: UUID,
        request: HousekeepingReasonRequest,
    ): HousekeepingTaskResponse
    fun cancelTask(
        propertyId: UUID,
        taskId: UUID,
        request: HousekeepingReasonRequest,
    ): HousekeepingTaskResponse
    fun listLostAndFound(propertyId: UUID): List<LostAndFoundResponse>
    fun createLostAndFound(
        propertyId: UUID,
        request: CreateLostAndFoundRequest,
    ): LostAndFoundResponse
    fun transitionLostAndFound(
        propertyId: UUID,
        itemId: UUID,
        target: LostAndFoundStatus,
        request: LostAndFoundTransitionRequest,
    ): LostAndFoundResponse
}
