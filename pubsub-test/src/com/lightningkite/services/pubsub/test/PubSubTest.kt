package com.lightningkite.services.pubsub.test

import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.get
import com.lightningkite.services.test.runTestWithClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Contract tests shared by every [PubSub] implementation. Extend this in an implementation
 * module's test source set and supply [pubsub] to hold that backend to the same behavior.
 */
public abstract class PubSubTest {
    public abstract val pubsub: PubSub

    /**
     * Regression test: [PubSub]'s documented contract is fire-and-forget with no backpressure
     * (see the "Important Gotchas" section on [PubSub]) - a subscriber that never keeps up with
     * its own `collect {}` body must not be able to stall `emit()` for every other publisher on
     * the channel. LocalPubSub/DebugPubSub used to back channels with a rendezvous flow
     * (buffer=0, suspend on overflow), so `emit()` blocked indefinitely once a slow subscriber
     * fell behind.
     */
    @Test
    public fun emitDoesNotBlockOnASlowSubscriber(): TestResult = runTestWithClock {
        val channel = pubsub.get<Int>("slow-subscriber-${Uuid.random()}")

        val subscriberReceivedFirstValue = CompletableDeferred<Unit>()
        val releaseSubscriber = CompletableDeferred<Unit>()

        // CoroutineStart.UNDISPATCHED runs the coroutine eagerly up to its first suspension
        // point, which is flow subscription registration - so by the time launch() returns, the
        // collector is guaranteed to be actively subscribed and ready to receive.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            channel.collect {
                subscriberReceivedFirstValue.complete(Unit)
                releaseSubscriber.await() // Simulate a subscriber stuck processing this value.
            }
        }

        try {
            // Delivered to the collector, which then gets stuck handling it.
            withTimeout(5.seconds) { channel.emit(1) }
            withTimeout(5.seconds) { subscriberReceivedFirstValue.await() }

            // The subscriber is now permanently busy. This is the call that must not block.
            withTimeout(5.seconds) { channel.emit(2) }
        } finally {
            releaseSubscriber.complete(Unit)
            job.cancel()
        }
    }
}
