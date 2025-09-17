package com.lightningkite.services.otel

import com.lightningkite.services.TestSettingContext
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.Assert.*
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class OpenTelemetrySettingsTest {
    @Test
    fun test() {
        val telemetry = OpenTelemetrySettings("log", reportFrequency = null).invoke("telemetry", TestSettingContext())
        val context = telemetry["asdf"]
        context.spanBuilder("asdf").useBlocking {
            println("OK, we're doing work")
            KotlinLogging.logger("sigh").info { "This is a test" }
        }
        Thread.sleep(1.seconds.inWholeMilliseconds)
    }
}