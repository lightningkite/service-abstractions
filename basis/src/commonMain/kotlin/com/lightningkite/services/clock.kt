package com.lightningkite.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Retrieves the current clock from coroutine context, or system clock if none is set.
 *
 * This allows suspending functions to respect test clocks without explicitly
 * passing them as parameters. The clock can be set via [withClock].
 *
 * ## Usage
 *
 * In production code:
 * ```kotlin
 * suspend fun timestampNow(): Instant {
 *     return Clock.default().now()
 * }
 * ```
 *
 * In tests:
 * ```kotlin
 * @Test
 * fun testWithFixedTime() = runTest {
 *     val fixedClock = object : Clock {
 *         override fun now() = Instant.parse("2025-01-01T00:00:00Z")
 *     }
 *
 *     withClock(fixedClock) {
 *         val timestamp = timestampNow()
 *         assertEquals(Instant.parse("2025-01-01T00:00:00Z"), timestamp)
 *     }
 * }
 * ```
 *
 * @return The clock from coroutine context, or [Clock.System] if not in a [withClock] block
 * @see withClock to set a custom clock
 * @see ClockContextElement for the context element implementation
 */
public suspend fun Clock.Companion.default(): Clock {
    return coroutineContext[ClockContextElement]?.clock ?: Clock.System
}

/**
 * Executes a block with a custom clock in the coroutine context.
 *
 * All calls to [Clock.Companion.default] within the block will return the provided clock.
 * This is useful for:
 * - Testing time-dependent logic with fixed or controllable time
 * - Simulating time passage in tests
 * - Mocking time for deterministic behavior
 *
 * ## Usage
 *
 * ```kotlin
 * @Test
 * fun testExpirationLogic() = runTest {
 *     val clock = TestClock(Instant.parse("2025-01-01T00:00:00Z"))
 *
 *     withClock(clock) {
 *         val token = createToken(expiresIn = 1.hours)
 *         assertFalse(token.isExpired())
 *
 *         clock.advance(2.hours) // Advance test clock
 *         assertTrue(token.isExpired())
 *     }
 * }
 * ```
 *
 * @param clock The clock to use within the block
 * @param block Suspending code that will use the provided clock
 * @return The result of the block
 * @see Clock.Companion.default to retrieve the current clock
 */
public suspend fun <R> withClock(clock: Clock, block: suspend CoroutineScope.() -> R): R {
    return withContext(ClockContextElement(clock), block)
}

/**
 * Coroutine context element that carries a [Clock] instance.
 *
 * Used by [withClock] and [Clock.Companion.default] to propagate custom clocks
 * through coroutine contexts. This enables testable time-dependent code without
 * explicit clock parameters.
 *
 * @property clock The clock instance carried by this context element
 */
public class ClockContextElement(public val clock: Clock) : AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<ClockContextElement>
}

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding a TestClock implementation:
 *    - Include a built-in TestClock with advance() and set() methods
 *    - Would make testing easier without requiring external test utilities
 *    - Common pattern that every project currently has to implement
 *
 * 2. Consider clock context nesting behavior:
 *    - Document what happens with nested withClock calls (inner wins)
 *    - Consider if there are use cases for clock inheritance/composition
 *    - Could add restoreClock() to pop back to parent clock
 *
 * 3. Consider performance optimization:
 *    - Accessing coroutineContext has overhead
 *    - For production code that doesn't need clock mocking, direct Clock.System is faster
 *    - Could add inline hint or documentation about performance implications
 *
 * 4.  Consider making a non-suspendable clock function that leverages ThreadLocal, much like OpenTelemetry does.
 */
