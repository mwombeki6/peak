package com.mwombeki.peak

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@ConfigurationPropertiesScan
@SpringBootApplication
@EnableScheduling
class PeakApplication

fun main(args: Array<String>) {
    runApplication<PeakApplication>(*args)
}
