package com.mwombeki.peak

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@ConfigurationPropertiesScan
@SpringBootApplication
class PeakApplication

fun main(args: Array<String>) {
    runApplication<PeakApplication>(*args)
}
