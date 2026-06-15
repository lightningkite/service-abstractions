package com.lightningkite.services.telemetry

import com.lightningkite.services.Namespaced
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext


/*
 * Coroutine-first metrics model.
 *
 * The unifying primitive is the *ambient attribute bag*: attributes are carried in the coroutine
 * context and accumulate as you descend the call tree ([telemetryAttributes]). Everything emitted
 * beneath inherits them, so call sites never decide cardinality.
 *
 * - **Spans** receive the full ambient bag (high cardinality is fine on a trace).
 * - **Metrics** project the ambient bag down to each instrument's `defaultDimensions` — the
 *   low-cardinality keys promoted to dimensions. That declaration, made once next to the instrument,
 *   is the cardinality firewall.
 *
 * The telemetry backend is provided by [SettingContext.telemetryBackend] (a plain interface — no
 * expect/actual). When the default [TelemetryBackend.Noop] is active, every function below runs with
 * no-op instruments. Parenting flows through the coroutine context, so child spans nest with no
 * manual threading.
 */

/**
 * Enriches the ambient attribute bag for the duration of [action]; does not open a span. Nested
 * calls accumulate, child keys overriding parent keys. Everything recorded within [action] — spans
 * and projected metrics alike — inherits these attributes.
 */
public suspend fun <T> telemetryAttributes(attributes: TelemetryAttributes, action: suspend () -> T): T {
    if (attributes.map.isEmpty()) return action()
    val parent = coroutineContext[TelemetryAttributeElement]
    return withContext(TelemetryAttributeElement(attributes, parent)) { action() }
}

/**
 * Enriches the ambient bag with [attributes], opens a span (parented via the coroutine context),
 * runs [action], and records RED metrics tagged `{name, operation, outcome}`. The span carries the
 * full ambient bag; [TelemetryTrace.enrich] attaches attributes discovered *during* the operation (rows
 * returned, sanitized query, error code) to the span.
 *
 * [dimensions] names low-cardinality keys to additionally promote onto the RED counter/duration
 * (e.g. `cache.hit`). Their values are taken at completion from the ambient bag and anything
 * `enrich`ed during the operation, so result-derived dimensions work. Keep this set small — it is
 * the cardinality firewall for the trace's metrics.
 */
public suspend fun <T> Namespaced.telemetryTrace(
    opName: String,
    attributes: TelemetryAttributes = TelemetryAttributes.empty,
    dimensions: Set<TelemetryKey<*>> = emptySet(),
    action: suspend (TelemetryTrace) -> T,
): T {
    val owner = this
    return telemetryAttributes(attributes) {
        context.telemetryBackend.span(owner, opName, currentTelemetryAttributes(), dimensions, action)
    }
}

/**
 * Instruments scoped to the [Namespaced] owner; `defaultDimensions` = the promotable (low-cardinality)
 * keys pulled from the ambient bag at record time. `unit` follows OTel base-unit convention. Create
 * once and hold in a `val`; these are cheap no-ops when no backend is configured.
 */
public fun Namespaced.telemetryHistogram(name: String, unit: MetricUnit, defaultDimensions: Set<TelemetryKey<*>>): Histogram =
    context.telemetryBackend.histogram(this, name, unit, defaultDimensions)

public fun Namespaced.telemetryCounter(name: String, unit: MetricUnit, defaultDimensions: Set<TelemetryKey<*>>): Counter =
    context.telemetryBackend.counter(this, name, unit, defaultDimensions)

public fun Namespaced.telemetryInFlight(name: String, defaultDimensions: Set<TelemetryKey<*>>): InFlight =
    context.telemetryBackend.inFlight(this, name, defaultDimensions)

/**
 * Registers an observable gauge sampled by the exporter. Note: [sample] runs outside any coroutine,
 * so a gauge carries no ambient attributes — only static ones derived from the owner.
 */
public fun Namespaced.telemetryGauge(name: String, unit: MetricUnit, sample: () -> Long): AutoCloseable =
    context.telemetryBackend.gauge(this, name, unit, TelemetryAttributes.empty, sample)