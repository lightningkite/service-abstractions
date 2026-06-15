#!/usr/bin/env kotlin

/**
 * Renames the Metrics* → Telemetry* API surface introduced in service-abstractions v2,
 * and fixes imports to the new com.lightningkite.services.telemetry package.
 *
 * Usage:
 *   kotlin scripts/migrate-metrics-to-telemetry.main.kts [path]
 *
 * Defaults to the current working directory when no path is given.
 * Processes all *.kt files recursively, skipping build/ directories.
 *
 * ── Symbol renames ───────────────────────────────────────────────────────────
 *
 *   OtelMetricsBackend       → OtelTelemetryBackend
 *   LoggingMetricsBackend    → LoggingTelemetryBackend
 *   MetricsBackend           → TelemetryBackend
 *   MetricSpan               → TelemetryTrace
 *   MetricAttributesBuilder  → TelemetryAttributesBuilder
 *   MetricAttributes         → TelemetryAttributes
 *   MetricKey                → TelemetryKey   (covers MetricKeys too)
 *   metricsBackend           → telemetryBackend
 *   metricsTrace             → telemetryTrace
 *   metricsAttributes        → telemetryAttributes
 *   metricsHistogram         → telemetryHistogram
 *   metricsCounter           → telemetryCounter
 *   metricsInFlight          → telemetryInFlight
 *   metricsGauge             → telemetryGauge
 *
 * ── Import package fixes ─────────────────────────────────────────────────────
 *
 *   import com.lightningkite.services.{telemetry-symbol}
 *     → import com.lightningkite.services.telemetry.{telemetry-symbol}
 *
 *   Symbols that moved into com.lightningkite.services.telemetry:
 *     TelemetryBackend, TelemetryAttributes, TelemetryAttributesBuilder,
 *     TelemetryKey, TelemetryKeys, TelemetryTrace, TelemetrySanitization,
 *     MetricUnit, LogLevel, Histogram, Counter, InFlight, Lease,
 *     telemetryTrace, telemetryAttributes, telemetryHistogram,
 *     telemetryCounter, telemetryInFlight, telemetryGauge,
 *     currentTelemetryAttributes
 *
 *   import com.lightningkite.services.{OtelX}
 *     → import com.lightningkite.services.otel.{OtelX}
 *
 * ── Not renamed ──────────────────────────────────────────────────────────────
 *   MetricUnit   — still named MetricUnit (just moved to the telemetry package)
 *   MetricSpan   — RENAMED to TelemetryTrace (see above)
 */

import java.io.File

val rootDir = File(args.firstOrNull() ?: ".")
require(rootDir.isDirectory) { "Path '${rootDir.path}' is not a directory" }

// ── Symbol renames (applied in order) ────────────────────────────────────────
// Longer / more-specific names before shorter prefixes to avoid partial matches.
val simpleReplacements = listOf(
    // Concrete subclasses first
    "OtelMetricsBackend"          to "OtelTelemetryBackend",
    "LoggingMetricsBackend"       to "LoggingTelemetryBackend",
    // Base class
    "MetricsBackend"              to "TelemetryBackend",
    // Span handle (was MetricSpan)
    "MetricSpan"                  to "TelemetryTrace",
    // Builder class (longer name before its prefix MetricAttributes)
    "MetricAttributesBuilder"     to "TelemetryAttributesBuilder",
    // Attribute bag
    "MetricAttributes"            to "TelemetryAttributes",
    // Key types (MetricKey covers both MetricKey and MetricKeys)
    "MetricKey"                   to "TelemetryKey",
    // SettingContext property
    "metricsBackend"              to "telemetryBackend",
    // DSL functions
    "metricsTrace"                to "telemetryTrace",
    "metricsAttributes"           to "telemetryAttributes",
    "metricsHistogram"            to "telemetryHistogram",
    "metricsCounter"              to "telemetryCounter",
    "metricsInFlight"             to "telemetryInFlight",
    "metricsGauge"                to "telemetryGauge",
)

// ── Import fixes ──────────────────────────────────────────────────────────────

// Symbols that now live in com.lightningkite.services.telemetry.
// After symbol renaming above runs, all old names are already updated, so this
// list uses only the *new* names.
val telemetryPackageSymbols = setOf(
    "TelemetryBackend",
    "TelemetryAttributes",
    "TelemetryAttributesBuilder",
    "TelemetryKey",
    "TelemetryKeys",
    "TelemetryTrace",
    "TelemetrySanitization",
    "MetricUnit",
    "LogLevel",
    "Histogram",
    "Counter",
    "InFlight",
    "Lease",
    "telemetryTrace",
    "telemetryAttributes",
    "telemetryHistogram",
    "telemetryCounter",
    "telemetryInFlight",
    "telemetryGauge",
    "currentTelemetryAttributes",
)

// Symbols that live in com.lightningkite.services.otel (already there or just renamed).
val otelPackageSymbols = setOf(
    "OtelTelemetryBackend",
)

// Matches a single-symbol import from com.lightningkite.services (not already in a sub-package).
// Group 1 = the symbol name.
val baseImportRegex = Regex("""^(import com\.lightningkite\.services\.)([A-Za-z][A-Za-z0-9]*)$""", RegexOption.MULTILINE)

fun fixImports(content: String): String = baseImportRegex.replace(content) { match ->
    val prefix = match.groupValues[1]   // "import com.lightningkite.services."
    val symbol = match.groupValues[2]
    when (symbol) {
        in telemetryPackageSymbols -> "${prefix}telemetry.$symbol"
        in otelPackageSymbols      -> "${prefix}otel.$symbol"
        else                       -> match.value   // leave unchanged
    }
}

// ── Pipeline ──────────────────────────────────────────────────────────────────

fun process(content: String): String {
    var result = content
    // 1. Rename symbols everywhere (code, comments, strings, imports)
    for ((old, new) in simpleReplacements) result = result.replace(old, new)
    // 2. Fix import package paths
    result = fixImports(result)
    return result
}

// ── Walk and update files ─────────────────────────────────────────────────────

var filesChanged = 0

rootDir.walkTopDown()
    .filter { it.isFile && it.extension == "kt" }
    .filter { file -> "build" !in file.invariantSeparatorsPath.split("/") }
    .forEach { file ->
        val original = file.readText()
        val updated = process(original)
        if (updated != original) {
            file.writeText(updated)
            filesChanged++
            println("  updated: ${file.relativeTo(rootDir).path}")
        }
    }

println()
println("Done — $filesChanged file(s) updated.")
