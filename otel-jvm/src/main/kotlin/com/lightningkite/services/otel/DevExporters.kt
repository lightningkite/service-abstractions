package com.lightningkite.services.otel

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.io.File
import java.io.PrintWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Configuration for dev-mode exporters.
 *
 * @property color Whether to use ANSI color codes in output (default: true)
 * @property output Where to write output - null for stdout, or a file path
 */
internal data class DevExporterConfig(
    val color: Boolean = true,
    val output: File? = null
) {
    private var _writer: PrintWriter? = null

    val writer: PrintWriter
        get() = _writer ?: synchronized(this) {
            _writer ?: createWriter().also { _writer = it }
        }

    private fun createWriter(): PrintWriter = output?.let {
        it.parentFile?.mkdirs()
        PrintWriter(it.bufferedWriter(), true)
    } ?: PrintWriter(System.out, true)

    fun close() {
        output?.let { _writer?.close() }
    }

    // ANSI codes - empty strings if color is disabled
    val RESET = if (color) "\u001B[0m" else ""
    val RED = if (color) "\u001B[31m" else ""
    val GREEN = if (color) "\u001B[32m" else ""
    val BLUE = if (color) "\u001B[34m" else ""
    val CYAN = if (color) "\u001B[36m" else ""
    val DIM = if (color) "\u001B[2m" else ""
    val BOLD = if (color) "\u001B[1m" else ""
    val YELLOW = if (color) "\u001B[33m" else ""
}

/**
 * Development-mode span exporter that provides immediate, human-readable hierarchical output.
 *
 * Features:
 * - No batching delay - traces are printed as soon as the root span completes
 * - Hierarchical tree view showing parent-child relationships
 * - Duration formatting (ms, s, or m depending on magnitude)
 * - Error highlighting
 * - Key attributes displayed inline
 * - Events shown as sub-items
 * - Optional ANSI color codes
 * - Optional file output
 * - Background thread for I/O to avoid affecting application timing
 */
internal class DevSpanExporter(private val config: DevExporterConfig = DevExporterConfig()) : SpanExporter {
    private val isShutdown = AtomicBoolean()

    // Buffer spans by trace ID until the root span arrives
    private val pendingSpans = ConcurrentHashMap<String, MutableList<SpanData>>()

    // Background thread for I/O to avoid blocking application threads
    private val printQueue = java.util.concurrent.LinkedBlockingQueue<Pair<SpanData, List<SpanData>>>()
    private val printThread = Thread({
        while (!isShutdown.get() || printQueue.isNotEmpty()) {
            try {
                val item = printQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (item != null) {
                    printTraceTree(item.first, item.second)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        // Drain remaining items on shutdown
        while (true) {
            val item = printQueue.poll() ?: break
            printTraceTree(item.first, item.second)
        }
    }, "dev-otel-printer").apply {
        isDaemon = true
        start()
    }

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode {
        if (isShutdown.get()) {
            return CompletableResultCode.ofFailure()
        }

        for (span in spans) {
            val traceId = span.traceId
            val isRoot = span.parentSpanId == "0000000000000000" || span.parentSpanId.isEmpty()

            if (isRoot) {
                // Root span arrived - queue the entire trace tree for printing
                val children = pendingSpans.remove(traceId) ?: mutableListOf()
                val allSpans = children + span
                printQueue.offer(span to allSpans)
            } else {
                // Child span - buffer it until the root arrives
                pendingSpans.computeIfAbsent(traceId) { mutableListOf() }.add(span)
            }
        }

        return CompletableResultCode.ofSuccess()
    }

    private fun printTraceTree(rootSpan: SpanData, allSpans: List<SpanData>) {
        val spansByParent = allSpans.groupBy { it.parentSpanId }
        val timestamp = timeFormatter.format(Instant.ofEpochMilli(rootSpan.startEpochNanos / 1_000_000))
        val out = config.writer

        out.println("${config.DIM}$timestamp${config.RESET} ${config.DIM}trace=${rootSpan.traceId.take(8)}…${config.RESET}")
        printSpanNode(rootSpan, spansByParent, prefix = "", isLast = true)
        out.println() // Blank line between traces
    }

    private fun printSpanNode(
        span: SpanData,
        spansByParent: Map<String, List<SpanData>>,
        prefix: String,
        isLast: Boolean
    ) {
        val out = config.writer
        val durationNanos = span.endEpochNanos - span.startEpochNanos
        val durationStr = formatDuration(durationNanos)

        val isError = span.status.statusCode == StatusCode.ERROR
        val marker = if (isError) "${config.RED}✗${config.RESET}" else "${config.GREEN}✓${config.RESET}"

        // Tree branch characters
        val branch = if (prefix.isEmpty()) "" else if (isLast) "└─ " else "├─ "

        out.println("$prefix$branch$marker ${config.BOLD}${span.name}${config.RESET} ${config.DIM}($durationStr)${config.RESET}")

        // Child prefix for nested items
        val childPrefix = prefix + if (prefix.isEmpty()) "  " else if (isLast) "   " else "│  "

        // Print key attributes (skip internal ones)
        val importantAttrs = span.attributes.asMap().entries
            .filter { !it.key.key.startsWith("otel.") && !it.key.key.startsWith("thread.") }
            .take(5)

        if (importantAttrs.isNotEmpty()) {
            val attrsStr = importantAttrs.joinToString(" ") { (k, v) ->
                val valueStr = when {
                    v.toString().length > 50 -> v.toString().take(47) + "..."
                    else -> v.toString()
                }
                "${config.CYAN}${k.key}${config.RESET}=$valueStr"
            }
            out.println("$childPrefix${config.DIM}$attrsStr${config.RESET}")
        }

        // Print error message if present
        if (isError && span.status.description.isNotEmpty()) {
            out.println("$childPrefix${config.RED}error: ${span.status.description}${config.RESET}")
        }

        // Print events
        for (event in span.events) {
            val eventOffsetNanos = event.epochNanos - span.startEpochNanos
            val eventOffsetStr = formatDuration(eventOffsetNanos)
            val isException = event.name == "exception"
            val eventColor = if (isException) config.RED else config.BLUE

            out.println("$childPrefix${eventColor}↳ ${event.name}${config.RESET} ${config.DIM}+$eventOffsetStr${config.RESET}")

            if (isException) {
                val exType = event.attributes.get(AttributeKey.stringKey("exception.type"))
                val exMsg = event.attributes.get(AttributeKey.stringKey("exception.message"))
                if (exType != null || exMsg != null) {
                    out.println("$childPrefix  ${config.RED}$exType: $exMsg${config.RESET}")
                }
            }
        }

        // Print child spans
        val children = spansByParent[span.spanId]?.sortedBy { it.startEpochNanos } ?: emptyList()
        children.forEachIndexed { index, child ->
            printSpanNode(child, spansByParent, childPrefix, isLast = index == children.lastIndex)
        }
    }

    private fun formatDuration(nanos: Long): String = when {
        nanos >= 60_000_000_000 -> String.format("%.1fm", nanos / 60_000_000_000.0)
        nanos >= 1_000_000_000 -> String.format("%.2fs", nanos / 1_000_000_000.0)
        nanos >= 1_000_000 -> String.format("%.1fms", nanos / 1_000_000.0)
        nanos >= 1_000 -> String.format("%.1fµs", nanos / 1_000.0)
        else -> "${nanos}ns"
    }

    override fun flush(): CompletableResultCode {
        // Wait for print queue to drain
        while (printQueue.isNotEmpty()) {
            Thread.sleep(10)
        }

        val out = config.writer
        // Flush any orphaned spans (where root never arrived)
        pendingSpans.forEach { (traceId, spans) ->
            if (spans.isNotEmpty()) {
                val timestamp = timeFormatter.format(Instant.ofEpochMilli(spans.first().startEpochNanos / 1_000_000))
                out.println("${config.DIM}$timestamp${config.RESET} ${config.DIM}trace=$traceId (incomplete)${config.RESET}")
                spans.sortedBy { it.startEpochNanos }.forEach { span ->
                    val duration = formatDuration(span.endEpochNanos - span.startEpochNanos)
                    val marker = if (span.status.statusCode == StatusCode.ERROR) "${config.RED}✗${config.RESET}" else "${config.GREEN}✓${config.RESET}"
                    out.println("  $marker ${config.BOLD}${span.name}${config.RESET} ${config.DIM}($duration)${config.RESET}")
                }
                out.println()
            }
        }
        pendingSpans.clear()
        return CompletableResultCode.ofSuccess()
    }

    override fun shutdown(): CompletableResultCode {
        if (!isShutdown.compareAndSet(false, true)) {
            return CompletableResultCode.ofSuccess()
        }
        // Wait for print thread to finish
        printThread.interrupt()
        printThread.join(5000)
        val result = flush()
        config.close()
        return result
    }
}

/**
 * Development-mode metric exporter with readable console output.
 */
internal class DevMetricExporter(private val config: DevExporterConfig = DevExporterConfig()) : MetricExporter {
    private val isShutdown = AtomicBoolean()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality {
        return AggregationTemporality.CUMULATIVE
    }

    override fun export(metrics: MutableCollection<MetricData>): CompletableResultCode {
        if (isShutdown.get()) {
            return CompletableResultCode.ofFailure()
        }

        if (metrics.isEmpty()) return CompletableResultCode.ofSuccess()

        val out = config.writer
        val timestamp = timeFormatter.format(Instant.now())
        out.println("${config.DIM}$timestamp${config.RESET} ${config.BOLD}METRICS${config.RESET} ${config.DIM}(${metrics.size} metrics)${config.RESET}")

        for (metric in metrics) {
            val dataPoints = when {
                metric.longSumData.points.isNotEmpty() -> metric.longSumData.points.map { "${it.value}" }
                metric.doubleSumData.points.isNotEmpty() -> metric.doubleSumData.points.map { String.format("%.2f", it.value) }
                metric.longGaugeData.points.isNotEmpty() -> metric.longGaugeData.points.map { "${it.value}" }
                metric.doubleGaugeData.points.isNotEmpty() -> metric.doubleGaugeData.points.map { String.format("%.2f", it.value) }
                metric.histogramData.points.isNotEmpty() -> metric.histogramData.points.map {
                    "count=${it.count} sum=${String.format("%.2f", it.sum)}"
                }
                else -> listOf("(no data)")
            }

            val valueStr = dataPoints.joinToString(", ")
            out.println("  ${config.CYAN}${metric.name}${config.RESET} = $valueStr ${config.DIM}${metric.unit}${config.RESET}")
        }

        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode {
        isShutdown.set(true)
        return CompletableResultCode.ofSuccess()
    }
}

/**
 * Development-mode log exporter with readable, colorized console output.
 */
internal class DevLogExporter(private val config: DevExporterConfig = DevExporterConfig()) : LogRecordExporter {
    private val isShutdown = AtomicBoolean()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        if (isShutdown.get()) {
            return CompletableResultCode.ofFailure()
        }

        val out = config.writer
        for (log in logs) {
            val timestamp = timeFormatter.format(Instant.ofEpochMilli(log.timestampEpochNanos / 1_000_000))

            // Severity numbers: TRACE=1-4, DEBUG=5-8, INFO=9-12, WARN=13-16, ERROR=17-20, FATAL=21-24
            val severityNum = log.severity.severityNumber
            val (levelColor, levelStr) = when {
                severityNum >= 21 -> config.RED to "FATAL"
                severityNum >= 17 -> config.RED to "ERROR"
                severityNum >= 13 -> config.YELLOW to "WARN "
                severityNum >= 9 -> config.BLUE to "INFO "
                severityNum >= 5 -> config.CYAN to "DEBUG"
                severityNum >= 1 -> config.DIM to "TRACE"
                else -> "" to "LOG  "
            }

            @Suppress("DEPRECATION")
            val body = log.body?.asString() ?: ""

            // Truncate very long messages
            val displayBody = if (body.length > 500) body.take(497) + "..." else body

            // Show trace context if present
            val traceInfo = if (log.spanContext.isValid) {
                " ${config.DIM}[${log.spanContext.traceId.take(8)}…]${config.RESET}"
            } else ""

            out.println("${config.DIM}$timestamp${config.RESET} $levelColor${config.BOLD}$levelStr${config.RESET}$traceInfo $displayBody")
        }

        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode {
        isShutdown.set(true)
        return CompletableResultCode.ofSuccess()
    }
}
