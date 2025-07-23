package com.lightningkite.serviceabstractions.cache

import com.lightningkite.serviceabstractions.ClockContextElement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


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