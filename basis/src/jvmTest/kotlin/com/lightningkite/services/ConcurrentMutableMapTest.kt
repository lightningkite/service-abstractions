package com.lightningkite.services

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

/**
 * JVM-only concurrency tests for [ConcurrentMutableMap].
 *
 * These exercise real threads to validate that [ConcurrentMutableMap.compute] is atomic on the JVM.
 * The contract tests for non-concurrent behavior live in commonTest.
 */
class ConcurrentMutableMapJvmTest {

    @Test
    fun testConcurrentWrites() = runTest {
        val map = ConcurrentMutableMap<String, Int>()
        val iterations = 1000

        val jobs = (1..10).map { threadId ->
            async(Dispatchers.Default) {
                repeat(iterations) { i ->
                    map["thread-$threadId-$i"] = i
                }
            }
        }

        jobs.awaitAll()

        assertEquals(10 * iterations, map.size)
    }

    /**
     * 16 threads each increment a single shared counter 1000 times via [ConcurrentMutableMap.compute].
     * If compute were not atomic, lost updates would make the final value less than 16_000.
     */
    @Test
    fun testComputeIsAtomicUnderConcurrentIncrement() {
        val map = ConcurrentMutableMap<String, Int>()
        val threadCount = 16
        val perThread = 1000
        val startGate = CountDownLatch(1)

        val threads = (1..threadCount).map {
            thread(start = true) {
                startGate.await()
                repeat(perThread) {
                    map.compute("counter") { _, existing -> (existing ?: 0) + 1 }
                }
            }
        }
        startGate.countDown()
        threads.forEach { it.join() }

        assertEquals(threadCount * perThread, map["counter"])
    }

    /**
     * Validates that under contention only one [ConcurrentMutableMap.computeIfAbsent] mapping function
     * actually executes per key. ConcurrentHashMap guarantees this; documenting it via a test.
     */
    @Test
    fun testComputeIfAbsentRunsMappingOnceUnderContention() {
        val map = ConcurrentMutableMap<String, Int>()
        val calls = AtomicInteger(0)
        val threadCount = 32
        val startGate = CountDownLatch(1)

        val threads = (1..threadCount).map {
            thread(start = true) {
                startGate.await()
                map.computeIfAbsent("shared") {
                    calls.incrementAndGet()
                    // Stall briefly to widen the race window. ConcurrentHashMap should still
                    // hold the per-bin lock, ensuring only one thread enters this block.
                    Thread.sleep(5)
                    42
                }
            }
        }
        startGate.countDown()
        threads.forEach { it.join() }

        assertEquals(42, map["shared"])
        assertEquals(1, calls.get())
    }
}
