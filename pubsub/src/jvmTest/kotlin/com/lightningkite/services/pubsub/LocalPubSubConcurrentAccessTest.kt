package com.lightningkite.services.pubsub

import com.lightningkite.services.TestSettingContext
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Verifies that [LocalPubSub.get] / [DebugPubSub.get] hand out a single, shared channel per key
 * even when many threads race to create it for the first time.
 *
 * Uses real OS [Thread]s released simultaneously via a [CountDownLatch] barrier (not coroutines
 * on a shared dispatcher, which tend to serialize short-lived work and rarely land on the exact
 * race window) - this is exactly the per-request `getUserChannel(userId)` pattern the module's
 * own docs recommend. The channel cache used to be a plain, non-synchronized `mutableMapOf`; two
 * threads racing on the very first `get(key)` could each construct their own independent flow,
 * so a publisher wired to one instance and a subscriber wired to the other would silently never
 * see each other's messages.
 */
class LocalPubSubConcurrentAccessTest {

    @Test
    fun concurrentFirstAccessYieldsSameLocalChannel() {
        assertAllRacersGetTheSameChannel(LocalPubSub("test", TestSettingContext()))
    }

    @Test
    fun concurrentFirstAccessYieldsSameDebugChannel() {
        assertAllRacersGetTheSameChannel(DebugPubSub("test", TestSettingContext()))
    }

    private fun assertAllRacersGetTheSameChannel(pubsub: PubSub) {
        val key = "concurrent-first-access-${Uuid.random()}"
        val racers = 64

        val ready = CountDownLatch(racers)
        val start = CountDownLatch(1)
        val results = arrayOfNulls<PubSubChannel<Int>>(racers)

        val threads = (0 until racers).map { i ->
            Thread {
                ready.countDown()
                start.await() // All threads block here until released together, below.
                results[i] = pubsub.get<Int>(key)
            }
        }
        threads.forEach { it.start() }
        ready.await() // Wait for every thread to reach the barrier before releasing any of them.
        start.countDown()
        threads.forEach { it.join() }

        val first = results[0]
        assertTrue(
            results.all { it === first },
            "All concurrent first accesses to the same key must return the same channel instance",
        )
    }
}
