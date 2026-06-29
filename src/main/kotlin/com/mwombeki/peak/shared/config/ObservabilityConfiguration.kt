package com.mwombeki.peak.shared.config

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ObservabilityConfiguration {
    @Bean
    fun modulithMetricTagConsistencyFilter(): MeterFilter {
        return object : MeterFilter {
            override fun map(id: Meter.Id): Meter.Id {
                if (!id.name.startsWith(MODULITH_REQUEST_METRIC_PREFIX) ||
                    id.getTag(INVOCATION_TYPE_TAG) != null
                ) {
                    return id
                }
                return id.withTag(Tag.of(INVOCATION_TYPE_TAG, "service"))
            }
        }
    }

    private companion object {
        const val MODULITH_REQUEST_METRIC_PREFIX = "module.requests"
        const val INVOCATION_TYPE_TAG = "module.invocation-type"
    }
}
