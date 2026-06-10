package com.lightningkite.services

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext


// ---- Ambient attribute plumbing (coroutine context) ----

internal class MetricAttributeElement(
    val attributes: MetricAttributes,
    val parent: MetricAttributeElement?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MetricAttributeElement>

    fun flattened(): MetricAttributes {
        if (parent == null) return attributes
        val merged = LinkedHashMap<MetricKey<*>, Any?>()
        fun add(element: MetricAttributeElement) {
            element.parent?.let(::add) // root first so children override
            merged.putAll(element.attributes.map)
        }
        add(this)
        return MetricAttributes(merged)
    }
}

/** The full ambient attribute bag accumulated by enclosing [metricsAttributes]/[metricsTrace] calls. */
public suspend fun currentMetricAttributes(): MetricAttributes =
    currentCoroutineContext()[MetricAttributeElement]?.flattened() ?: MetricAttributes.empty
