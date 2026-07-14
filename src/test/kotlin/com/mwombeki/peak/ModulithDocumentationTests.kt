package com.mwombeki.peak

import kotlin.test.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

class ModulithDocumentationTests {

    @Test
    fun `generates canonical module diagrams and canvases`() {
        val modules = ApplicationModules.of(PeakApplication::class.java)
        Documenter(modules)
            .writeModulesAsPlantUml()
            .writeModuleCanvases()
    }
}
