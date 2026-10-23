package com.lightningkite.services.telemetry

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A W3C `traceparent` header received from a caller. A backend parents the next span to it when no
 * span is current, so a server request continues the client's trace.
 */
public class TraceParent(public val header: String) : AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<TraceParent>
}
