package com.lightningkite.services

import com.lightningkite.services.telemetry.TelemetryBackend
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

class TelemetryBackendSettingsTest {

    private val context = TestSettingContext()

    @Test
    fun noopSchemeReturnsNoop() {
        val backend = TelemetryBackend.Settings("noop")("metrics", context)
        assertSame(TelemetryBackend.Noop, backend)
    }

    @Test
    fun defaultUrlIsNoop() {
        val backend = TelemetryBackend.Settings()("metrics", context)
        assertSame(TelemetryBackend.Noop, backend)
    }

    @Test
    fun loggingSchemeAfterRegistration() {
        LoggingTelemetryBackend.register()
        val backend = TelemetryBackend.Settings("logging")("metrics", context)
        assertIs<LoggingTelemetryBackend>(backend)
    }

    @Test
    fun loggingNoColorScheme() {
        LoggingTelemetryBackend.register()
        val backend = TelemetryBackend.Settings("logging-nocolor")("metrics", context) as LoggingTelemetryBackend
        assertIs<LoggingTelemetryBackend>(backend)
        assert(!backend.color)
    }

    @Test
    fun unknownSchemeThrows() {
        assertFailsWith<IllegalArgumentException> {
            TelemetryBackend.Settings("unknown-backend-xyz")("metrics", context)
        }
    }
}
