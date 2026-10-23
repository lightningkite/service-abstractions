package com.lightningkite.services.telemetry

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext


// ---- Ambient attribute plumbing (coroutine context) ----

/**
 * The ambient attribute bag in a coroutine context. Usually placed by [telemetryAttributes]; put one in a
 * context yourself (with `parent = null`) when you build a coroutine context that starts a fresh chain.
 */
public class TelemetryAttributeElement(
    public val attributes: TelemetryAttributes,
    public val parent: TelemetryAttributeElement?,
) : AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<TelemetryAttributeElement>

    public fun flattened(): TelemetryAttributes {
        if (parent == null) return attributes
        return TelemetryAttributes {
            fun add(element: TelemetryAttributeElement) {
                element.parent?.let(::add) // root first so children override
                putAll(element.attributes)
            }
            add(this@TelemetryAttributeElement)
        }
    }
}

/** The full ambient attribute bag accumulated by enclosing [telemetryAttributes]/[telemetryTrace] calls. */
public suspend fun currentTelemetryAttributes(): TelemetryAttributes =
    currentCoroutineContext()[TelemetryAttributeElement]?.flattened() ?: TelemetryAttributes.empty
