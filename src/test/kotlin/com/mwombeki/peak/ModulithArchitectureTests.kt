package com.mwombeki.peak

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModulithArchitectureTests {

    @Test
    fun verifiesApplicationModuleBoundaries() {
        ApplicationModules.of(PeakApplication::class.java).verify()
    }
}
