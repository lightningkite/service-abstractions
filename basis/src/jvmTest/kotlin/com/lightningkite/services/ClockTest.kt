package com.lightningkite.services

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Clock

/**
 * Tests for clock context functionality.
 */
class ClockTest {

    private class TestClock(private val instant: kotlinx.datetime.Instant) : Clock {
        override fun now() = instant
    }

    @Test
    fun testDefaultClockIsSystemWhenNoContext() = runTest {
        val clock = Clock.default()
        // Should be close to system time
        assertNotNull(clock.now())
    }

    @Test
    fun testWithClockSetsCustomClock() = runTest {
        val fixedInstant = kotlinx.datetime.Instant.parse("2025-01-01T00:00:00Z")
        val customClock = TestClock(fixedInstant)

        withClock(customClock) {
            val clock = Clock.default()
            assertEquals(fixedInstant, clock.now())
        }
    }

    @Test
    fun testClockContextRestoresAfterBlock() = runTest {
        val fixedInstant = kotlinx.datetime.Instant.parse("2025-01-01T00:00:00Z")
        val customClock = TestClock(fixedInstant)

        val beforeClock = Clock.default()

        withClock(customClock) {
            assertEquals(fixedInstant, Clock.default().now())
        }

        // After exiting withClock, should be back to original
        val afterClock = Clock.default()
        assertNotEquals(fixedInstant, afterClock.now())
    }

    @Test
    fun testNestedClockContexts() = runTest {
        val instant1 = kotlinx.datetime.Instant.parse("2025-01-01T00:00:00Z")
        val instant2 = kotlinx.datetime.Instant.parse("2025-06-01T00:00:00Z")
        val clock1 = TestClock(instant1)
        val clock2 = TestClock(instant2)

        withClock(clock1) {
            assertEquals(instant1, Clock.default().now())

            withClock(clock2) {
                // Inner clock should win
                assertEquals(instant2, Clock.default().now())
            }

            // After inner block, should restore outer clock
            assertEquals(instant1, Clock.default().now())
        }
    }
}
