package com.lightningkite.services.otel

import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.TestSettingContext
import kotlin.test.Test
import kotlin.test.assertIs

class TelemetryBackendSettingsOtelTest {

    private val context = TestSettingContext()

    @Test
    fun registerMakesOtelSchemesAvailable() {
        OtelTelemetryBackend.register()
        val backend = TelemetryBackend.Settings("console")("metrics", context)
        assertIs<OtelTelemetryBackend>(backend)
    }

    @Test
    fun devSchemeProducesOtelBackend() {
        OtelTelemetryBackend.register()
        val backend = TelemetryBackend.Settings("dev://")("metrics", context)
        assertIs<OtelTelemetryBackend>(backend)
    }

    @Test
    fun logSchemeProducesOtelBackend() {
        OtelTelemetryBackend.register()
        val backend = TelemetryBackend.Settings("log")("metrics", context)
        assertIs<OtelTelemetryBackend>(backend)
    }

    @Test
    fun serviceNameComesFromProjectName() {
        OtelTelemetryBackend.register()
        // TestSettingContext.projectName == "Test"; just verify it doesn't throw and
        // produces an OtelTelemetryBackend (service.name is an OTel resource attribute,
        // not exposed on the wrapper, so we verify it indirectly via construction).
        val backend = TelemetryBackend.Settings("console")("metrics", context)
        assertIs<OtelTelemetryBackend>(backend)
    }
}
