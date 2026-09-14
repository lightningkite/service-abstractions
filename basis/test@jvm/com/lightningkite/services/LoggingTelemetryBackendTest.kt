package com.lightningkite.services

import com.lightningkite.services.telemetry.LogLevel
import com.lightningkite.services.telemetry.MetricUnit
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.telemetryCounter
import com.lightningkite.services.telemetry.telemetryHistogram
import com.lightningkite.services.telemetry.telemetryInFlight
import com.lightningkite.services.telemetry.telemetryTrace
import kotlinx.coroutines.test.runTest
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class LoggingTelemetryBackendTest {

    private fun backend(): Pair<LoggingTelemetryBackend, StringWriter> {
        val sw = StringWriter()
        return LoggingTelemetryBackend(color = false, out = PrintWriter(sw, true)) to sw
    }

    @Test
    fun spanPrintsTreeWithDurationAndAttributes() = runTest {
        val (backend, sw) = backend()
        val owner = object : Namespaced {
            override val name = "svc"
            override val context = object : SettingContext by TestSettingContext() {
                override val telemetryBackend = backend
            }
        }

        owner.telemetryTrace("fetch", TelemetryAttributes { put(TelemetryKey.OfString("db"), "main") }) { }

        val output = sw.toString()
        assertContains(output, "svc.fetch")
        assertContains(output, "db=main")
        assertTrue(output.contains("ms") || output.contains("µs") || output.contains("ns"),
            "output should contain a duration: $output")
        assertContains(output, "✓")
    }

    @Test
    fun errorSpanShowsX() = runTest {
        val (backend, sw) = backend()
        val owner = object : Namespaced {
            override val name = "svc"
            override val context = object : SettingContext by TestSettingContext() {
                override val telemetryBackend = backend
            }
        }

        runCatching {
            owner.telemetryTrace("boom") { throw RuntimeException("kaboom") }
        }

        assertContains(sw.toString(), "✗")
        assertContains(sw.toString(), "svc.boom")
    }

    @Test
    fun nestedSpansPrintTree() = runTest {
        val (backend, sw) = backend()
        val owner = object : Namespaced {
            override val name = "svc"
            override val context = object : SettingContext by TestSettingContext() {
                override val telemetryBackend = backend
            }
        }

        owner.telemetryTrace("outer") {
            owner.telemetryTrace("inner") { }
        }

        val output = sw.toString()
        assertContains(output, "svc.outer")
        assertContains(output, "svc.inner")
        // inner should appear before outer's closing (tree indented inside outer)
        assertTrue(output.indexOf("svc.inner") < output.indexOf("svc.outer") + "svc.outer".length + 50)
    }

    @Test
    fun histogramInsideSpanFoldsIntoTree() = runTest {
        val (backend, sw) = backend()
        val owner = object : Namespaced {
            override val name = "svc"
            override val context = object : SettingContext by TestSettingContext() {
                override val telemetryBackend = backend
            }
        }

        val rows = owner.telemetryHistogram("svc.rows", MetricUnit.Occurrences, emptySet())
        owner.telemetryTrace("query") { rows.record(42.0) }

        val output = sw.toString()
        // Record is folded into the span tree, printed once
        assertContains(output, "svc.rows")
        assertContains(output, "42")
    }

    @Test
    fun histogramOutsideSpanPrintsImmediately() = runTest {
        val (backend, sw) = backend()
        val owner = object : Namespaced {
            override val name = "svc"
            override val context = object : SettingContext by TestSettingContext() {
                override val telemetryBackend = backend
            }
        }

        val rows = owner.telemetryHistogram("svc.rows", MetricUnit.Occurrences, emptySet())
        rows.record(7.0)

        assertContains(sw.toString(), "svc.rows")
        assertContains(sw.toString(), "7")
    }

    @Test
    fun noBackendDoesNotCrash() = runTest {
        val owner = object : Namespaced {
            override val name = "svc"
            override val context: SettingContext = TestSettingContext()
        }
        // TelemetryBackend.Noop — must not throw
        owner.telemetryTrace("op") { }
        owner.telemetryHistogram("h", MetricUnit.Bytes, emptySet()).record(1.0)
        owner.telemetryCounter("c", MetricUnit.Occurrences, emptySet()).increment()
        owner.telemetryInFlight("f", emptySet()).lease().release()
        assertTrue(true)
    }

    @Test
    fun spanLogsAppearInTree() = runTest {
        val (backend, sw) = backend()
        val owner = object : Namespaced {
            override val name = "svc"
            override val context = object : SettingContext by TestSettingContext() {
                override val telemetryBackend = backend
            }
        }

        owner.telemetryTrace("op") { span ->
            span.log(LogLevel.Info, "hello from span")
        }

        val output = sw.toString()
        assertContains(output, "hello from span")
        assertContains(output, "INFO")
    }
}
