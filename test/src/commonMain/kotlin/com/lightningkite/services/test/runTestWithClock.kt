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

/**
 * Runs a coroutine test with a controllable test clock.
 *
 * Combines [runTest] with [ClockContextElement] to provide:
 * - Virtual time control via [TestScope]
 * - Fixed starting time (2025-01-01 01:01:01 UTC)
 * - Clock that advances with test time
 *
 * This allows testing time-dependent code without actual delays.
 *
 * ## Usage
 *
 * ```kotlin
 * @Test
 * fun testExpirationLogic() = runTestWithClock {
 *     val token = createToken(expiresIn = 1.hours)
 *     assertFalse(token.isExpired())
 *
 *     advanceTimeBy(2.hours)  // Virtual time advance
 *     assertTrue(token.isExpired())
 * }
 * ```
 *
 * @param context Additional coroutine context elements
 * @param timeout Maximum test duration (default: 60 seconds)
 * @param testBody Test code to execute
 * @return TestResult for test framework integration
 */
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

/**
 * Measures the average execution time of a code block.
 *
 * Runs warmup iterations to allow JIT compilation, then measures
 * the average time across all iterations.
 *
 * ## Usage
 *
 * ```kotlin
 * val avgTime = performance(times = 100_000) {
 *     expensiveOperation()
 * }
 * println("Average: ${avgTime.inWholeNanoseconds} ns")
 * ```
 *
 * @param times Number of measurement iterations (default: 1,000,000)
 * @param warmup Number of warmup iterations (default: times / 10)
 * @param block Code to measure
 * @return Average duration per iteration
 */
inline fun performance(times: Int = 1_000_000, warmup: Int = times / 10, block: ()->Unit): Duration {
    repeat(warmup) { block() }
    return measureTime {
        repeat(times) { block() }
    }.div(times.toDouble())
}
