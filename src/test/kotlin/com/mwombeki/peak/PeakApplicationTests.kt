package com.mwombeki.peak

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class PeakApplicationTests {

    @Test
    fun contextLoads() {
    }

}
