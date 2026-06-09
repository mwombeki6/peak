package com.mwombeki.peak

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<PeakApplication>().with(TestcontainersConfiguration::class).run(*args)
}
