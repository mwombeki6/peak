package com.mwombeki.peak

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PeakApplication

fun main(args: Array<String>) {
    runApplication<PeakApplication>(*args)
}
