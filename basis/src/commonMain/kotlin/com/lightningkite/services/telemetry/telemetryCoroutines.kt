package com.lightningkite.services.telemetry

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext


// ---- Ambient attribute plumbing (coroutine context) ----

internal class TelemetryAttributeElement(
    val attributes: TelemetryAttributes,
    val parent: TelemetryAttributeElement?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TelemetryAttributeElement>

    fun flattened(): TelemetryAttributes {
        if (parent == null) return attributes
        val merged = LinkedHashMap<TelemetryKey<*>, Any?>()
        fun add(element: TelemetryAttributeElement) {
            element.parent?.let(::add) // root first so children override
            merged.putAll(element.attributes.map)
        }
        add(this)
        return TelemetryAttributes(merged)
    }
}

/** The full ambient attribute bag accumulated by enclosing [telemetryAttributes]/[telemetryTrace] calls. */
public suspend fun currentTelemetryAttributes(): TelemetryAttributes =
    currentCoroutineContext()[TelemetryAttributeElement]?.flattened() ?: TelemetryAttributes.empty
