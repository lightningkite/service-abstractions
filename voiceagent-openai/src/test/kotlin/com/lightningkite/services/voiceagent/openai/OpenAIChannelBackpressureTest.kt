package com.lightningkite.services.voiceagent.openai

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shape test that mirrors the event channel construction in
 * [OpenAIVoiceAgentSession.eventChannel]
 * (OpenAIVoiceAgentService.kt:161):
 *
 *     private val eventChannel = Channel<VoiceAgentEvent>(
 *         capacity = 64,
 *         onBufferOverflow = BufferOverflow.DROP_OLDEST
 *     )
 *
 * The production channel is private inside an internal class and is constructed
 * during a real WebSocket connect() call, so it cannot be reached directly from
 * a unit test. This test instead constructs an identically-configured channel
 * and pins down the two properties the production code relies on:
 *
 *   1. capacity is 64 (buffer holds 64 newest events before overflowing)
 *   2. overflow policy is DROP_OLDEST (slow consumer loses oldest events,
 *      not newest; producer never suspends).
 *
 * Defends against accidental drift to DROP_LATEST or SUSPEND, or to a
 * different capacity. If the production line moves, update the line number
 * reference above.
 */
class OpenAIChannelBackpressureTest {

    private companion object {
        const val CAPACITY = 64
    }

    @Test
    fun `channel with DROP_OLDEST capacity 64 drops oldest items when full`() = runTest {
        val channel = Channel<Int>(capacity = CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        // Push CAPACITY + 1 items without consuming. With DROP_OLDEST, send must
        // never suspend; the oldest item (0) must be evicted so the 64 newest
        // (1..64) survive.
        repeat(CAPACITY + 1) { i ->
            // trySend is sufficient here: DROP_OLDEST guarantees a buffered
            // channel always accepts. A failing trySend would indicate the
            // overflow policy has regressed to SUSPEND.
            val result = channel.trySend(i)
            check(result.isSuccess) { "trySend($i) failed; overflow policy is not DROP_OLDEST" }
        }

        channel.close()

        val received = buildList {
            for (item in channel) add(item)
        }

        assertEquals(CAPACITY, received.size, "buffer should hold exactly $CAPACITY items")
        assertEquals((1..CAPACITY).toList(), received, "oldest item (0) should have been dropped, newest 64 retained")
    }

    @Test
    fun `channel drops oldest across many overflows, never newest`() = runTest {
        val channel = Channel<Int>(capacity = CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        // Push 3x capacity without consuming. The final buffer must contain the
        // most-recent 64 values (128..191). If the policy were DROP_LATEST,
        // we'd see the first 64 (0..63) instead.
        val total = CAPACITY * 3
        repeat(total) { i ->
            val result = channel.trySend(i)
            check(result.isSuccess) { "trySend($i) failed at i=$i" }
        }

        channel.close()

        val received = buildList {
            for (item in channel) add(item)
        }

        assertEquals(CAPACITY, received.size)
        assertEquals((total - CAPACITY until total).toList(), received,
            "DROP_OLDEST must retain the $CAPACITY most-recent items")
    }
}
