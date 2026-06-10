package com.lightningkite.services

import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext


/*
 * Coroutine-first metrics model.
 *
 * The unifying primitive is the *ambient attribute bag*: attributes are carried in the coroutine
 * context and accumulate as you descend the call tree ([metricsAttributes]). Everything emitted
 * beneath inherits them, so call sites never decide cardinality.
 *
 * - **Spans** receive the full ambient bag (high cardinality is fine on a trace).
 * - **Metrics** project the ambient bag down to each instrument's `defaultDimensions` — the
 *   low-cardinality keys promoted to dimensions. That declaration, made once next to the instrument,
 *   is the cardinality firewall.
 *
 * The telemetry backend is provided by [SettingContext.metricsBackend] (a plain interface — no
 * expect/actual). When the default [MetricsBackend.Noop] is active, every function below runs with
 * no-op instruments. Parenting flows through the coroutine context, so child spans nest with no
 * manual threading.
 */

/**
 * Enriches the ambient attribute bag for the duration of [action]; does not open a span. Nested
 * calls accumulate, child keys overriding parent keys. Everything recorded within [action] — spans
 * and projected metrics alike — inherits these attributes.
 */
public suspend fun <T> metricsAttributes(attributes: MetricAttributes, action: suspend () -> T): T {
    if (attributes.map.isEmpty()) return action()
    val parent = coroutineContext[MetricAttributeElement]
    return withContext(MetricAttributeElement(attributes, parent)) { action() }
}

/**
 * Enriches the ambient bag with [attributes], opens a span (parented via the coroutine context),
 * runs [action], and records RED metrics tagged `{name, operation, outcome}`. The span carries the
 * full ambient bag; [MetricSpan.enrich] attaches attributes discovered *during* the operation (rows
 * returned, sanitized query, error code) to the span.
 *
 * [dimensions] names low-cardinality keys to additionally promote onto the RED counter/duration
 * (e.g. `cache.hit`). Their values are taken at completion from the ambient bag and anything
 * `enrich`ed during the operation, so result-derived dimensions work. Keep this set small — it is
 * the cardinality firewall for the trace's metrics.
 */
public suspend fun <T> Namespaced.metricsTrace(
    opName: String,
    attributes: MetricAttributes = MetricAttributes.empty,
    dimensions: Set<MetricKey<*>> = emptySet(),
    action: suspend (MetricSpan) -> T,
): T {
    val owner = this
    return metricsAttributes(attributes) {
        context.metricsBackend.span(owner, opName, currentMetricAttributes(), dimensions, action)
    }
}

/**
 * Instruments scoped to the [Namespaced] owner; `defaultDimensions` = the promotable (low-cardinality)
 * keys pulled from the ambient bag at record time. `unit` follows OTel base-unit convention. Create
 * once and hold in a `val`; these are cheap no-ops when no backend is configured.
 */
public fun Namespaced.metricsHistogram(name: String, unit: MetricUnit, defaultDimensions: Set<MetricKey<*>>): Histogram =
    context.metricsBackend.histogram(this, name, unit, defaultDimensions)

public fun Namespaced.metricsCounter(name: String, unit: MetricUnit, defaultDimensions: Set<MetricKey<*>>): Counter =
    context.metricsBackend.counter(this, name, unit, defaultDimensions)

public fun Namespaced.metricsInFlight(name: String, defaultDimensions: Set<MetricKey<*>>): InFlight =
    context.metricsBackend.inFlight(this, name, defaultDimensions)

/**
 * Registers an observable gauge sampled by the exporter. Note: [sample] runs outside any coroutine,
 * so a gauge carries no ambient attributes — only static ones derived from the owner.
 */
public fun Namespaced.metricsGauge(name: String, unit: MetricUnit, defaultDimensions: Set<MetricKey<*>>, sample: () -> Long): AutoCloseable =
    context.metricsBackend.gauge(this, name, unit, MetricAttributes.empty, sample)