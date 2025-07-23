package com.lightningkite.serviceabstractions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

public suspend fun Clock.Companion.default(): Clock {
    return coroutineContext[ClockContextElement]?.clock ?: Clock.System
}

public suspend fun <R> withClock(clock: Clock, block: suspend CoroutineScope.() -> R): R {
    return withContext(ClockContextElement(clock), block)
}

public class ClockContextElement(public val clock: Clock) : AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<ClockContextElement>
}
