package com.mwombeki.peak.shared.outbox

enum class OutboxStatus {
    PENDING,      // Event is stored and waiting for the background worker to pick it up
    PROCESSING,   // Worker has locked this event and is currently executing it
    COMPLETED,    // Event was processed or sent successfully
    FAILED,       // Event failed an attempt but is allowed to retry
    DEAD_LETTER   // Hard failure: Max retries exceeded, needs human/admin intervention
}