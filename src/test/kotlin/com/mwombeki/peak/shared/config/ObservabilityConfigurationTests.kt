package com.mwombeki.peak.shared.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class ObservabilityConfigurationTests {
    @Test
    fun `adds stable invocation type tag to modulith request meters`() {
        val registry = SimpleMeterRegistry()
        registry.config().meterFilter(
            ObservabilityConfiguration().modulithMetricTagConsistencyFilter(),
        )

        Counter.builder("module.requests")
            .tag("module.key", "property")
            .register(registry)
            .increment()
        Counter.builder("module.requests")
            .tag("module.key", "realtime")
            .tag("module.invocation-type", "event-listener")
            .register(registry)
            .increment()

        val invocationTypes = registry.meters
            .filter { it.id.name == "module.requests" }
            .mapNotNull { it.id.getTag("module.invocation-type") }
            .toSet()
        assertEquals(setOf("service", "event-listener"), invocationTypes)
    }
}
