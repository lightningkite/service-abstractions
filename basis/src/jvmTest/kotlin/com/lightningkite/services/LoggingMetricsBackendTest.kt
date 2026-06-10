package com.lightningkite.services

import kotlinx.coroutines.test.runTest
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingMetricsBackendTest {

    private fun backend(): Pair<LoggingMetricsBackend, StringWriter> {
        val sw = StringWriter()
        return LoggingMetricsBackend(color = false, out = PrintWriter(sw, true)) to sw
    }

    @Test
    fun spanPrintsTreeWithDurationAndAttributes() = runTest {
        val (backend, sw) = backend()
        val owner = object : Namespaced {
            override val name = "svc"
            override val context = object : SettingContext by TestSettingContext() {
                override val metricsBackend = backend
            }
        }

        owner.metricsTrace("fetch", MetricAttributes { put(MetricKey.OfString("db"), "main") }) { }

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
                override val metricsBackend = backend
            }
        }

        runCatching {
            owner.metricsTrace("boom") { throw RuntimeException("kaboom") }
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
                override val metricsBackend = backend
            }
        }

        owner.metricsTrace("outer") {
            owner.metricsTrace("inner") { }
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
                override val metricsBackend = backend
            }
        }

        val rows = owner.metricsHistogram("svc.rows", MetricUnit.Occurrences, emptySet())
        owner.metricsTrace("query") { rows.record(42.0) }

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
                override val metricsBackend = backend
            }
        }

        val rows = owner.metricsHistogram("svc.rows", MetricUnit.Occurrences, emptySet())
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
        // MetricsBackend.Noop — must not throw
        owner.metricsTrace("op") { }
        owner.metricsHistogram("h", MetricUnit.Bytes, emptySet()).record(1.0)
        owner.metricsCounter("c", MetricUnit.Occurrences, emptySet()).increment()
        owner.metricsInFlight("f", emptySet()).lease().release()
        assertTrue(true)
    }

    @Test
    fun spanLogsAppearInTree() = runTest {
        val (backend, sw) = backend()
        val owner = object : Namespaced {
            override val name = "svc"
            override val context = object : SettingContext by TestSettingContext() {
                override val metricsBackend = backend
            }
        }

        owner.metricsTrace("op") { span ->
            span.log(LogLevel.Info, "hello from span")
        }

        val output = sw.toString()
        assertContains(output, "hello from span")
        assertContains(output, "INFO")
    }
}
