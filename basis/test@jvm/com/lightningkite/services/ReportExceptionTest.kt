package com.lightningkite.services

import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Tests for [SettingContext.reportException].
 */
class ReportExceptionTest {

    @Test
    fun reportExceptionLogsWithoutTelemetryBackend() = runTest {
        // TestSettingContext uses TelemetryBackend.Noop, so reportError is a no-op and only the
        // ERROR log happens. This must complete without throwing. The OTel-backed reportError path
        // is exercised by OtelTelemetryBackendReportErrorTest in otel-jvm.
        val context = TestSettingContext()
        context.reportException(
            RuntimeException("something failed"),
        )
    }
}
