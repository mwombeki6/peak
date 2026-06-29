package com.mwombeki.peak

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@ConfigurationPropertiesScan
@SpringBootApplication
class PeakApplication

fun main(args: Array<String>) {
    val context = runApplication<PeakApplication>(*args)
    val runtimeMode = context.environment.getProperty("peak.runtime.mode")
    if (runtimeMode.equals("migration", ignoreCase = true) ||
        runtimeMode.equals("bootstrap", ignoreCase = true)
    ) {
        exitProcess(SpringApplication.exit(context))
    }
}
