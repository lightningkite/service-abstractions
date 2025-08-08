package com.lightningkite.services.test

import com.lightningkite.services.ClockContextElement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime


@OptIn(ExperimentalCoroutinesApi::class)
public inline fun runTestWithClock(
    context: CoroutineContext = EmptyCoroutineContext,
    timeout: Duration = 60.seconds,
    crossinline testBody: suspend TestScope.() -> Unit
): TestResult {
    lateinit var started: ComparableTimeMark
    return runTest(context + ClockContextElement(object : Clock {
        val start = LocalDateTime(2025, 1, 1, 1, 1, 1).toInstant(UtcOffset.ZERO)
        override fun now(): Instant {
            return start + started.elapsedNow()
        }
    }), timeout = timeout, testBody = {
        started = testTimeSource.markNow()
        testBody()
    })
}

inline fun performance(times: Int = 1_000_000, warmup: Int = times / 10, block: ()->Unit): Duration {
    repeat(warmup) { block() }
    return measureTime {
        repeat(times) { block() }
    }.div(times.toDouble())
}