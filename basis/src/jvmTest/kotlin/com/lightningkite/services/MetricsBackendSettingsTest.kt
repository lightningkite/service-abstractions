package com.lightningkite.services

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MetricsBackendSettingsTest {

    private val context = TestSettingContext()

    @Test
    fun noopSchemeReturnsNoop() {
        val backend = MetricsBackend.Settings("noop")("metrics", context)
        assertSame(MetricsBackend.Noop, backend)
    }

    @Test
    fun defaultUrlIsNoop() {
        val backend = MetricsBackend.Settings()("metrics", context)
        assertSame(MetricsBackend.Noop, backend)
    }

    @Test
    fun loggingSchemeAfterRegistration() {
        LoggingMetricsBackend.register()
        val backend = MetricsBackend.Settings("logging")("metrics", context)
        assertIs<LoggingMetricsBackend>(backend)
    }

    @Test
    fun loggingNoColorScheme() {
        LoggingMetricsBackend.register()
        val backend = MetricsBackend.Settings("logging-nocolor")("metrics", context) as LoggingMetricsBackend
        assertIs<LoggingMetricsBackend>(backend)
        assert(!backend.color)
    }

    @Test
    fun unknownSchemeThrows() {
        assertFailsWith<IllegalArgumentException> {
            MetricsBackend.Settings("unknown-backend-xyz")("metrics", context)
        }
    }
}
