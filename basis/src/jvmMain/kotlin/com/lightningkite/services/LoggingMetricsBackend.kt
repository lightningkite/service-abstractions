package com.lightningkite.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * [MetricsBackend] that prints human-readable, ANSI-colored output to the console (or a [PrintWriter]).
 * Intended for local development; not suitable for production.
 *
 * Spans are buffered until the root span completes, then printed as an indented tree:
 * ```
 * 12:34:56.789
 * ✓ demo.fetch (5.2ms)
 *    cache.system=redis cache.hit=true
 *    ├─ ◆ demo.rows = 7.00 {occurrence}
 *    └─ ✓ demo.db.query (3.1ms)
 *          rows=42
 * ```
 *
 * Histogram and counter records are folded into the span they were recorded within.
 * Outside any span they print as standalone lines. InFlight and gauge produce no output.
 */
public class LoggingMetricsBackend(
    public val color: Boolean = true,
    public val out: PrintWriter = PrintWriter(System.out, true),
) : MetricsBackend {

    public companion object {
        init {
            MetricsBackend.Settings.register("logging") { _, _, _ -> LoggingMetricsBackend() }
            MetricsBackend.Settings.register("logging-nocolor") { _, _, _ -> LoggingMetricsBackend(color = false) }
        }
        /** Call once at startup to register the `logging` and `logging-nocolor` URL schemes. */
        public fun register() {}
    }

    private val ESC     = ""
    private val RESET   = if (color) "$ESC[0m"  else ""
    private val RED     = if (color) "$ESC[31m" else ""
    private val GREEN   = if (color) "$ESC[32m" else ""
    private val CYAN    = if (color) "$ESC[36m" else ""
    private val DIM     = if (color) "$ESC[2m"  else ""
    private val BOLD    = if (color) "$ESC[1m"  else ""
    private val YELLOW  = if (color) "$ESC[33m" else ""
    private val MAGENTA = if (color) "$ESC[35m" else ""

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private fun formatDuration(nanos: Long): String = when {
        nanos >= 60_000_000_000 -> "%.1fm".format(nanos / 60_000_000_000.0)
        nanos >= 1_000_000_000  -> "%.2fs".format(nanos / 1_000_000_000.0)
        nanos >= 1_000_000      -> "%.1fms".format(nanos / 1_000_000.0)
        nanos >= 1_000          -> "%.1fµs".format(nanos / 1_000.0)
        else                    -> "${nanos}ns"
    }

    private fun MetricAttributes.fmtInline(limit: Int = 6): String =
        map.entries.take(limit).joinToString(" ") { (k, v) ->
            val vs = v.toString().let { if (it.length > 40) it.take(37) + "…" else it }
            "$CYAN${k.name}$RESET=$vs"
        }

    // ── Span buffering ────────────────────────────────────────────────────────

    override suspend fun <T> span(
        owner: Namespaced,
        opName: String,
        attributes: MetricAttributes,
        dimensions: Set<MetricKey<*>>,
        action: suspend (MetricSpan) -> T,
    ): T {
        val parent = coroutineContext[LogSpanNode]
        val node = LogSpanNode("${owner.name}.$opName", System.nanoTime(), parent)
        val metricSpan = object : MetricSpan {
            val enriched = LinkedHashMap<MetricKey<*>, Any?>()
            override fun enrich(attributes: MetricAttributes) { enriched.putAll(attributes.map) }
            override fun isLoggable(level: LogLevel): Boolean = true
            override fun log(level: LogLevel, message: String, attributes: MetricAttributes) {
                node.logs.add(level to message)
            }
        }

        var ok = true
        return try {
            withContext(node) { action(metricSpan) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ok = false
            throw e
        } finally {
            val durationNanos = System.nanoTime() - node.startNanos
            val allAttrs = if (metricSpan.enriched.isEmpty()) attributes
                else MetricAttributes(attributes.map + metricSpan.enriched)
            val result = LogSpanResult(node.name, durationNanos, ok, allAttrs,
                node.logs.toList(), node.records.toList(), node.children.toList())
            if (parent != null) {
                parent.children.add(result)
            } else {
                val timestamp = timeFormatter.format(Instant.now())
                synchronized(out) {
                    out.println("$DIM$timestamp$RESET")
                    printResult(result, prefix = "", isLast = true)
                    out.println()
                    out.flush()
                }
            }
        }
    }

    private fun printResult(result: LogSpanResult, prefix: String, isLast: Boolean) {
        val branch = if (prefix.isEmpty()) "" else if (isLast) "└─ " else "├─ "
        val marker = if (result.ok) "$GREEN✓$RESET" else "$RED✗$RESET"
        out.println("$prefix$branch$marker $BOLD${result.name}$RESET $DIM(${formatDuration(result.durationNanos)})$RESET")

        val childPrefix = prefix + when {
            prefix.isEmpty() -> "   "
            isLast           -> "   "
            else             -> "│  "
        }

        if (result.attrs.map.isNotEmpty())
            out.println("$childPrefix$DIM${result.attrs.fmtInline()}$RESET")

        for ((recName, amount, unit) in result.records)
            out.println("$childPrefix$DIM◆ $recName = ${"%.4g".format(amount)} $unit$RESET")

        for ((level, message) in result.logs) {
            val (levelColor, levelTag) = when (level) {
                LogLevel.Error -> RED     to "ERROR"
                LogLevel.Warn  -> YELLOW  to "WARN "
                LogLevel.Info  -> MAGENTA to "INFO "
                LogLevel.Debug -> CYAN    to "DEBUG"
                LogLevel.Trace -> DIM     to "TRACE"
            }
            out.println("$childPrefix$levelColor▸ $levelTag$RESET $message")
        }

        result.children.forEachIndexed { i, child ->
            printResult(child, childPrefix, i == result.children.lastIndex)
        }
    }

    // ── Instruments ───────────────────────────────────────────────────────────

    override fun histogram(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<MetricKey<*>>): Histogram =
        object : Histogram {
            override suspend fun record(amount: Double) {
                val node = coroutineContext[LogSpanNode]
                if (node != null) {
                    node.records.add(Triple(name, amount, unit.ucum))
                } else {
                    synchronized(out) {
                        out.println("$DIM◆ $name = ${"%.4g".format(amount)} ${unit.ucum}$RESET")
                        out.flush()
                    }
                }
            }
        }

    override fun counter(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<MetricKey<*>>): Counter =
        object : Counter {
            override suspend fun increment(amount: Double) {
                val node = coroutineContext[LogSpanNode]
                if (node != null) {
                    node.records.add(Triple(name, amount, unit.ucum))
                } else {
                    synchronized(out) {
                        out.println("$DIM◆ $name += ${"%.4g".format(amount)} ${unit.ucum}$RESET")
                        out.flush()
                    }
                }
            }
        }

    // InFlight and gauge produce no output — per-acquire/release pairs are too noisy,
    // and gauge values are sampled by exporters rather than pushed per-change.

    override fun inFlight(owner: Namespaced, name: String, dimensions: Set<MetricKey<*>>): InFlight =
        object : InFlight {
            override suspend fun lease(): Lease = object : Lease { override fun release() {} }
        }

    override fun gauge(owner: Namespaced, name: String, unit: MetricUnit, attributes: MetricAttributes, sample: () -> Long): AutoCloseable =
        AutoCloseable {}

    // ── Error reporting ───────────────────────────────────────────────────────

    override fun reportError(throwable: Throwable, attributes: MetricAttributes) {
        val timestamp = timeFormatter.format(Instant.now())
        synchronized(out) {
            out.println("$DIM$timestamp$RESET $RED$BOLD${throwable::class.simpleName}$RESET $RED${throwable.message}$RESET")
            if (attributes.map.isNotEmpty())
                out.println("  $DIM${attributes.fmtInline()}$RESET")
            throwable.printStackTrace(out)
            out.flush()
        }
    }
}

// ── File-private coroutine context types ──────────────────────────────────────

private class LogSpanNode(
    val name: String,
    val startNanos: Long,
    val parent: LogSpanNode?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LogSpanNode>
    val logs: MutableList<Pair<LogLevel, String>>            = Collections.synchronizedList(mutableListOf())
    val records: MutableList<Triple<String, Double, String>> = Collections.synchronizedList(mutableListOf())
    val children: MutableList<LogSpanResult>                 = Collections.synchronizedList(mutableListOf())
}

private data class LogSpanResult(
    val name: String,
    val durationNanos: Long,
    val ok: Boolean,
    val attrs: MetricAttributes,
    val logs: List<Pair<LogLevel, String>>,
    val records: List<Triple<String, Double, String>>,
    val children: List<LogSpanResult>,
)
