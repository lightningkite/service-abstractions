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
 * @property debounceWindowMs Debounce window in milliseconds (null = no debouncing)
 * @property debounceMinCount Minimum occurrences to show aggregate (default: 1)
 */
internal data class DevExporterConfig(
    val color: Boolean = true,
    val output: File? = null,
    val debounceWindowMs: Long? = null,
    val debounceMinCount: Int = 1
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

    // Shared buffer for correlating logs with spans
    // Map of traceId -> (spanId -> List<LogRecordData>)
    val logBuffer = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableList<LogRecordData>>>()

    // ANSI codes - empty strings if color is disabled
    val RESET = if (color) "\u001B[0m" else ""
    val RED = if (color) "\u001B[31m" else ""
    val GREEN = if (color) "\u001B[32m" else ""
    val BLUE = if (color) "\u001B[34m" else ""
    val CYAN = if (color) "\u001B[36m" else ""
    val DIM = if (color) "\u001B[2m" else ""
    val BOLD = if (color) "\u001B[1m" else ""
    val YELLOW = if (color) "\u001B[33m" else ""
    val MAGENTA = if (color) "\u001B[35m" else ""
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

        // Clean up buffered logs for this trace
        config.logBuffer.remove(rootSpan.traceId)
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

        // Print logs associated with this span
        val logs = config.logBuffer[span.traceId]?.get(span.spanId)?.sortedBy { it.timestampEpochNanos }
        if (!logs.isNullOrEmpty()) {
            for (log in logs) {
                val logOffsetNanos = log.timestampEpochNanos - span.startEpochNanos
                val logOffsetStr = formatDuration(logOffsetNanos)

                val severityNum = log.severity.severityNumber
                val (levelColor, levelStr) = when {
                    severityNum >= 21 -> config.RED to "FATAL"
                    severityNum >= 17 -> config.RED to "ERROR"
                    severityNum >= 13 -> config.YELLOW to "WARN"
                    severityNum >= 9 -> config.MAGENTA to "INFO"
                    severityNum >= 5 -> config.CYAN to "DEBUG"
                    severityNum >= 1 -> config.DIM to "TRACE"
                    else -> "" to "LOG"
                }

                @Suppress("DEPRECATION")
                val body = log.body?.asString() ?: ""
                val displayBody = if (body.length > 100) body.take(97) + "..." else body

                out.println("$childPrefix$levelColor▸ $levelStr${config.RESET} $displayBody ${config.DIM}+$logOffsetStr${config.RESET}")
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
 * Development-mode log exporter that buffers logs for display within span trees.
 *
 * Instead of printing logs immediately, this exporter buffers them by trace/span ID
 * so they can be displayed inline with their associated spans by DevSpanExporter.
 */
internal class DevLogExporter(private val config: DevExporterConfig = DevExporterConfig()) : LogRecordExporter {
    private val isShutdown = AtomicBoolean()

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        if (isShutdown.get()) {
            return CompletableResultCode.ofFailure()
        }

        // Buffer logs by trace and span ID for correlation with spans
        for (log in logs) {
            if (log.spanContext.isValid) {
                val traceId = log.spanContext.traceId
                val spanId = log.spanContext.spanId

                config.logBuffer.computeIfAbsent(traceId) { ConcurrentHashMap() }
                    .computeIfAbsent(spanId) { mutableListOf() }
                    .add(log)
            } else {
                // Log without span context - print immediately
                printOrphanLog(log)
            }
        }

        return CompletableResultCode.ofSuccess()
    }

    private fun printOrphanLog(log: LogRecordData) {
        val out = config.writer
        val timestamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(log.timestampEpochNanos / 1_000_000))

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
        val displayBody = if (body.length > 500) body.take(497) + "..." else body

        out.println("${config.DIM}$timestamp${config.RESET} $levelColor${config.BOLD}$levelStr${config.RESET} $displayBody")
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode {
        isShutdown.set(true)
        return CompletableResultCode.ofSuccess()
    }
}

/**
 * Debounced development-mode span exporter that aggregates high-frequency traces.
 *
 * Behavior:
 * - First occurrence of a span name: Prints immediately in full detail
 * - Subsequent occurrences within debounce window: Aggregated silently
 * - When window expires: Prints aggregate summary of additional occurrences
 *
 * This provides immediate feedback for new operations while preventing spam from
 * high-frequency operations like websocket messages.
 *
 * Features:
 * - Aggregation by root span name
 * - Configurable debounce window (e.g., 30 seconds)
 * - Statistics: count, min/avg/max duration, error rate
 * - Automatic window expiration and flushing
 *
 * Example with 30s window and websocket messages firing every second:
 * - t=0s: First websocket.message prints immediately
 * - t=1-29s: 29 more websocket.message spans aggregate silently
 * - t=30s: Aggregate summary prints: "29 additional occurrences"
 */
internal class DebouncedDevSpanExporter(private val config: DevExporterConfig = DevExporterConfig()) : SpanExporter {
    private val isShutdown = AtomicBoolean()
    private val delegate = DevSpanExporter(config)

    private data class AggregateStats(
        var count: Int = 0,
        var minDurationNanos: Long = Long.MAX_VALUE,
        var maxDurationNanos: Long = Long.MIN_VALUE,
        var totalDurationNanos: Long = 0,
        var errorCount: Int = 0,
        var firstSeenMs: Long = System.currentTimeMillis(),
        var lastSeenMs: Long = System.currentTimeMillis(),
        var sampleTraceIds: MutableList<String> = mutableListOf(),
        var firstTrace: Pair<SpanData, List<SpanData>>? = null
    )

    // Map of root span name -> aggregate stats
    private val aggregates = ConcurrentHashMap<String, AggregateStats>()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    // Background thread for window expiration
    private val expirationThread = Thread({
        while (!isShutdown.get()) {
            try {
                Thread.sleep(100) // Check every 100ms
                checkExpiredWindows()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        // Flush all remaining aggregates on shutdown
        flushAllAggregates()
    }, "debounced-otel-expiration").apply {
        isDaemon = true
        start()
    }

    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode {
        if (isShutdown.get()) {
            return CompletableResultCode.ofFailure()
        }

        val debounceWindow = config.debounceWindowMs
        if (debounceWindow == null) {
            // No debouncing - delegate directly
            return delegate.export(spans)
        }

        for (span in spans) {
            val isRoot = span.parentSpanId == "0000000000000000" || span.parentSpanId.isEmpty()
            if (!isRoot) continue // Only track root spans for aggregation

            val spanName = span.name
            val durationNanos = span.endEpochNanos - span.startEpochNanos
            val isError = span.status.statusCode == StatusCode.ERROR
            val now = System.currentTimeMillis()

            val isFirstOccurrence = aggregates.compute(spanName) { _, existing ->
                if (existing != null && (now - existing.firstSeenMs) >= debounceWindow) {
                    // Window expired - flush old aggregate and start new window
                    flushAggregate(spanName, existing)
                    null // Will create new stats below
                } else {
                    existing
                }
            } == null

            if (isFirstOccurrence) {
                // First occurrence - print immediately and start tracking
                val allSpans = spans.toList()
                delegate.export((mutableListOf(span) + allSpans).toMutableList())

                aggregates[spanName] = AggregateStats().also { newStats ->
                    newStats.count = 1
                    newStats.minDurationNanos = durationNanos
                    newStats.maxDurationNanos = durationNanos
                    newStats.totalDurationNanos = durationNanos
                    newStats.errorCount = if (isError) 1 else 0
                    newStats.firstSeenMs = now
                    newStats.lastSeenMs = now
                    newStats.sampleTraceIds.add(span.traceId)
                    newStats.firstTrace = span to allSpans
                }
            } else {
                // Subsequent occurrence - aggregate silently
                aggregates.computeIfPresent(spanName) { _, stats ->
                    stats.count++
                    stats.minDurationNanos = minOf(stats.minDurationNanos, durationNanos)
                    stats.maxDurationNanos = maxOf(stats.maxDurationNanos, durationNanos)
                    stats.totalDurationNanos += durationNanos
                    if (isError) stats.errorCount++
                    stats.lastSeenMs = now
                    if (stats.sampleTraceIds.size < 5) {
                        stats.sampleTraceIds.add(span.traceId)
                    }
                    stats
                }
            }
        }

        return CompletableResultCode.ofSuccess()
    }

    private fun checkExpiredWindows() {
        val debounceWindow = config.debounceWindowMs ?: return
        val now = System.currentTimeMillis()

        val expired = aggregates.filterValues { stats ->
            (now - stats.lastSeenMs) >= debounceWindow
        }

        expired.forEach { (name, stats) ->
            if (aggregates.remove(name, stats)) {
                flushAggregate(name, stats)
            }
        }
    }

    private fun flushAllAggregates() {
        aggregates.forEach { (name, stats) ->
            flushAggregate(name, stats)
        }
        aggregates.clear()
    }

    private fun flushAggregate(name: String, stats: AggregateStats) {
        // Only print aggregate if there were additional occurrences after the first
        // (First occurrence is always printed immediately)
        if (stats.count <= 1) {
            return
        }

        // Check minimum count threshold (excluding the first occurrence)
        if (stats.count < config.debounceMinCount) {
            return
        }

        val out = config.writer
        val timestamp = timeFormatter.format(Instant.ofEpochMilli(stats.lastSeenMs))
        val avgDurationNanos = stats.totalDurationNanos / stats.count
        val windowDurationMs = stats.lastSeenMs - stats.firstSeenMs
        val windowDurationSec = windowDurationMs / 1000.0

        val marker = if (stats.errorCount > 0) "${config.RED}✗${config.RESET}" else "${config.GREEN}✓${config.RESET}"
        val errorRate = (stats.errorCount.toDouble() / stats.count * 100)
        val additionalCount = stats.count - 1 // Exclude first occurrence already printed

        out.println("${config.DIM}$timestamp${config.RESET} ${config.DIM}trace=${stats.sampleTraceIds.lastOrNull()?.take(8) ?: "unknown"}…${config.RESET}")
        out.println("  $marker ${config.BOLD}$name${config.RESET} ${config.DIM}(${additionalCount} additional)${config.RESET}")
        out.println("    ${config.CYAN}↳ Summary:${config.RESET} ${config.BOLD}${stats.count}${config.RESET} total in ${config.DIM}${String.format("%.1fs", windowDurationSec)}${config.RESET}")
        out.println("      ${config.DIM}•${config.RESET} avg: ${config.BOLD}${formatDuration(avgDurationNanos)}${config.RESET}, min: ${formatDuration(stats.minDurationNanos)}, max: ${formatDuration(stats.maxDurationNanos)}")

        if (stats.errorCount > 0) {
            out.println("      ${config.DIM}•${config.RESET} errors: ${config.RED}${stats.errorCount}${config.RESET} ${config.DIM}(${String.format("%.1f%%", errorRate)})${config.RESET}")
        }

        if (stats.sampleTraceIds.size > 1) {
            val sampleIds = stats.sampleTraceIds.takeLast(3).joinToString(", ") { it.take(8) + "…" }
            out.println("      ${config.DIM}•${config.RESET} recent samples: ${config.DIM}$sampleIds${config.RESET}")
        }

        out.println() // Blank line between aggregates

        // Clean up buffered logs for these traces
        stats.sampleTraceIds.forEach { traceId ->
            config.logBuffer.remove(traceId)
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
        flushAllAggregates()
        return delegate.flush()
    }

    override fun shutdown(): CompletableResultCode {
        if (!isShutdown.compareAndSet(false, true)) {
            return CompletableResultCode.ofSuccess()
        }
        expirationThread.interrupt()
        expirationThread.join(5000)
        flushAllAggregates()
        return delegate.shutdown()
    }
}
