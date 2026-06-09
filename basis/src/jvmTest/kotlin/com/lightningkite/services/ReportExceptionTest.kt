package com.lightningkite.services

import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Tests for [SettingContext.reportException].
 */
class ReportExceptionTest {

    @Test
    fun reportExceptionLogsWithoutTelemetryBackend() = runTest {
        // TestSettingContext has metricsBackend == null, so reportError is skipped and only the
        // ERROR log happens. This must complete without throwing. The OTel-backed reportError path
        // is exercised by OtelMetricsBackendReportErrorTest in otel-jvm.
        val context = TestSettingContext()
        context.reportException(
            RuntimeException("something failed"),
            mapOf("operation" to "createIndex", "table" to "users"),
        )
    }
}
