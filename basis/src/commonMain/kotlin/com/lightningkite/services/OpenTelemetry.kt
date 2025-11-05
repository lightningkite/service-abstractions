package com.lightningkite.services

/**
 * Platform-specific OpenTelemetry integration marker.
 *
 * This is an expect interface that resolves to platform-specific implementations:
 * - **JVM**: Actual OpenTelemetry SDK instance (`io.opentelemetry.api.OpenTelemetry`)
 * - **Non-JVM**: Empty marker interface (no-op implementation)
 *
 * ## Purpose
 *
 * OpenTelemetry provides distributed tracing, metrics, and logs for observability.
 * Services use this to:
 * - Create spans for operations (database queries, HTTP requests, cache operations)
 * - Record metrics (operation counts, durations, error rates)
 * - Propagate trace context across service boundaries
 *
 * ## Platform Support
 *
 * Full OpenTelemetry support is currently **JVM-only**. On other platforms, this
 * interface acts as a no-op marker to maintain API compatibility.
 *
 * ## Usage
 *
 * Services receive an optional [OpenTelemetry] instance via [SettingContext.openTelemetry].
 * When present, they should instrument operations:
 *
 * ```kotlin
 * class MyService(override val context: SettingContext) : Service {
 *     suspend fun fetchData(id: String): Data {
 *         // On JVM with OpenTelemetry configured, this creates a span
 *         // On other platforms or without telemetry, this is a no-op
 *         return context.openTelemetry?.let { otel ->
 *             val tracer = otel.getTracer("my-service")
 *             tracer.spanBuilder("fetchData")
 *                 .setAttribute("id", id)
 *                 .startSpan()
 *                 .use { span ->
 *                     // Perform operation
 *                     performFetch(id)
 *                 }
 *         } ?: performFetch(id)
 *     }
 * }
 * ```
 *
 * @see SettingContext.openTelemetry for how services access this
 */
public expect interface OpenTelemetry

// Note: The commented-out code below represents a potential future API for simplified tracing.
// Currently not implemented to keep the interface minimal and platform-agnostic.
// If implemented, would provide:
// - Simplified span creation with automatic context propagation
// - Suspending and blocking variants
// - Automatic attribute handling

//expect class OpenTelemetrySdkNamespace
//expect suspend fun <T> OpenTelemetrySdkNamespace.trace(
//    name: String,
//    attributes: Map<String, String> = mapOf(),
//    block: suspend () -> T
//): T
//expect fun <T> OpenTelemetrySdkNamespace.traceBlocking(
//    name: String,
//    attributes: Map<String, String> = mapOf(),
//    block: () -> T
//): T

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding simplified tracing helpers:
 *    - Extension functions on SettingContext for common tracing patterns
 *    - Reduce boilerplate in service implementations
 *    - Example: context.traced("operation-name") { ... }
 *
 * 2. Consider multi-platform telemetry:
 *    - Investigate OpenTelemetry JS SDK for browser/Node support
 *    - Consider basic telemetry for Native platforms
 *    - Could provide unified API with platform-specific backends
 *
 * 3. Consider adding metrics helper API:
 *    - Currently only tracing is documented
 *    - Metrics and logging are also part of OpenTelemetry
 *    - Could add helpers for common patterns (counters, gauges, histograms)
 */
