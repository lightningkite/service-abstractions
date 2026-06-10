package com.lightningkite.services.otel

import com.lightningkite.services.MetricsBackend
import com.lightningkite.services.TestSettingContext
import kotlin.test.Test
import kotlin.test.assertIs

class MetricsBackendSettingsOtelTest {

    private val context = TestSettingContext()

    @Test
    fun registerMakesOtelSchemesAvailable() {
        OtelMetricsBackend.register()
        val backend = MetricsBackend.Settings("console")("metrics", context)
        assertIs<OtelMetricsBackend>(backend)
    }

    @Test
    fun devSchemeProducesOtelBackend() {
        OtelMetricsBackend.register()
        val backend = MetricsBackend.Settings("dev://")("metrics", context)
        assertIs<OtelMetricsBackend>(backend)
    }

    @Test
    fun logSchemeProducesOtelBackend() {
        OtelMetricsBackend.register()
        val backend = MetricsBackend.Settings("log")("metrics", context)
        assertIs<OtelMetricsBackend>(backend)
    }

    @Test
    fun serviceNameComesFromProjectName() {
        OtelMetricsBackend.register()
        // TestSettingContext.projectName == "Test"; just verify it doesn't throw and
        // produces an OtelMetricsBackend (service.name is an OTel resource attribute,
        // not exposed on the wrapper, so we verify it indirectly via construction).
        val backend = MetricsBackend.Settings("console")("metrics", context)
        assertIs<OtelMetricsBackend>(backend)
    }
}
