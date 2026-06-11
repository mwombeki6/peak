package com.mwombeki.peak.shared.time

import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TimeConfiguration {
    @Bean
    fun systemClock(): Clock = Clock.systemUTC()
}
