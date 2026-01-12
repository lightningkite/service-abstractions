package com.lightningkite.services.voiceagent.phonecall

import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for AudioJitterBuffer.
 *
 * Note: These tests use runBlocking with real delays since the jitter buffer
 * uses real TimeSource.Monotonic internally for accurate audio timing.
 */
class AudioJitterBufferTest {

    @Test
    fun `buffer waits for target buffer before starting playback`() = runBlocking {
        val buffer = AudioJitterBuffer(targetBufferMs = 80)
        val sentChunks = mutableListOf<ByteArray>()

        // Start playback in background
        val playbackJob = launch {
            buffer.runPlayback { audio ->
                sentChunks.add(audio)
            }
        }

        // Add chunks that don't reach target (40ms total, need 80ms)
        repeat(5) { i ->
            buffer.add(i, ByteArray(64))  // 64 bytes = 8ms at 8 bytes/ms
        }

        // Give time for potential playback
        delay(50)

        // Should not have started playback yet
        assertEquals(0, sentChunks.size, "Should not start playback before target buffer reached")

        // Add more to reach target (now 120ms total)
        repeat(10) { i ->
            buffer.add(5 + i, ByteArray(64))
        }

        // Allow playback to proceed
        delay(200)

        // Should have started playback now
        assertTrue(sentChunks.isNotEmpty(), "Should start playback after target buffer reached")

        buffer.stop()
        playbackJob.cancelAndJoin()
    }

    @Test
    fun `buffer plays back at steady rate`() = runBlocking {
        val buffer = AudioJitterBuffer(targetBufferMs = 40)
        val sentChunks = mutableListOf<ByteArray>()

        // Start playback
        val playbackJob = launch {
            buffer.runPlayback { audio ->
                sentChunks.add(audio)
            }
        }

        // Add all chunks at once (simulating burst arrival)
        // 10 chunks of 40 bytes = 10 * 5ms = 50ms (reaches target immediately)
        repeat(10) { i ->
            buffer.add(i, ByteArray(40))  // 40 bytes = 5ms
        }

        // Wait for playback to complete
        delay(150)

        // Should have played all 10 chunks
        assertEquals(10, sentChunks.size, "Should have played all chunks")

        buffer.stop()
        playbackJob.cancelAndJoin()
    }

    @Test
    fun `clear resets buffer and restarts buffering phase`() = runBlocking {
        val buffer = AudioJitterBuffer(targetBufferMs = 40)
        val sentChunks = mutableListOf<ByteArray>()

        val playbackJob = launch {
            buffer.runPlayback { audio ->
                sentChunks.add(audio)
            }
        }

        // Fill buffer and start playback
        repeat(10) { i ->
            buffer.add(i, ByteArray(40))  // 50ms total
        }

        // Wait for some playback
        delay(100)

        val chunksBeforeClear = sentChunks.size
        assertTrue(chunksBeforeClear > 0, "Should have sent some chunks before clear")

        // Clear the buffer
        sentChunks.clear()
        buffer.clear()

        // Verify buffer level is reset
        assertEquals(0L, buffer.currentBufferMs, "Buffer should be empty after clear")

        // Add less than target buffer
        repeat(2) { i ->
            buffer.add(i, ByteArray(40))  // 10ms, less than 40ms target
        }

        delay(80)

        // Should not have sent anything since we haven't reached target again
        assertEquals(0, sentChunks.size, "Should not play after clear until buffer refilled")

        // Fill buffer again to exceed target
        repeat(10) { i ->
            buffer.add(2 + i, ByteArray(40))
        }

        delay(150)

        assertTrue(sentChunks.isNotEmpty(), "Should resume playback after buffer refilled")

        buffer.stop()
        playbackJob.cancelAndJoin()
    }

    @Test
    fun `stop terminates playback loop`() = runBlocking {
        val buffer = AudioJitterBuffer(targetBufferMs = 40)
        var playbackLoopExited = false

        val playbackJob = launch {
            buffer.runPlayback { }
            playbackLoopExited = true
        }

        // Fill buffer
        repeat(10) { i ->
            buffer.add(i, ByteArray(40))
        }

        delay(50)

        // Stop the buffer
        buffer.stop()

        // Give time for loop to exit
        delay(50)

        assertTrue(playbackLoopExited || playbackJob.isCompleted, "Playback should complete after stop")
        playbackJob.cancelAndJoin()
    }

    @Test
    fun `handles empty chunks gracefully`() = runBlocking {
        val buffer = AudioJitterBuffer(targetBufferMs = 40)
        val sentChunks = mutableListOf<ByteArray>()

        val playbackJob = launch {
            buffer.runPlayback { audio ->
                sentChunks.add(audio)
            }
        }

        // Add empty chunk (shouldn't crash or cause issues)
        buffer.add(0, ByteArray(0))

        // Add real chunks
        repeat(10) { i ->
            buffer.add(1 + i, ByteArray(40))
        }

        delay(150)

        // Should work normally (empty chunk may or may not be included)
        assertTrue(sentChunks.size >= 10)

        buffer.stop()
        playbackJob.cancelAndJoin()
    }

    @Test
    fun `currentBufferMs tracks buffer level`() = runBlocking {
        val buffer = AudioJitterBuffer(targetBufferMs = 100)

        assertEquals(0L, buffer.currentBufferMs)

        buffer.add(0, ByteArray(80))  // 10ms
        assertEquals(10L, buffer.currentBufferMs)

        buffer.add(1, ByteArray(160))  // 20ms more
        assertEquals(30L, buffer.currentBufferMs)

        buffer.clear()
        assertEquals(0L, buffer.currentBufferMs)
    }

    @Test
    fun `jitter buffer absorbs irregular arrival timing`() = runBlocking {
        // This test simulates the real scenario: audio arriving in bursts
        val buffer = AudioJitterBuffer(targetBufferMs = 80)
        var chunksPlayed = 0

        val playbackJob = launch {
            buffer.runPlayback { audio ->
                chunksPlayed++
            }
        }

        // Simulate bursty arrival: all at once
        repeat(20) { i ->
            buffer.add(i, ByteArray(40))  // 5ms each = 100ms total
        }

        // Wait for playback to complete
        delay(200)

        // All chunks should be played
        assertEquals(20, chunksPlayed, "Should have played all chunks")

        buffer.stop()
        playbackJob.cancelAndJoin()
    }

    @Test
    fun `buffer reorders out-of-sequence chunks`() = runBlocking {
        val buffer = AudioJitterBuffer(targetBufferMs = 40)
        val playedSeqs = mutableListOf<Int>()

        val playbackJob = launch {
            buffer.runPlayback { audio ->
                // Audio size encodes the sequence number for verification
                playedSeqs.add(audio.size)
            }
        }

        // Add chunks out of order (size encodes the seq for verification)
        buffer.add(2, ByteArray(102))  // seq=2, size=102
        buffer.add(0, ByteArray(100))  // seq=0, size=100
        buffer.add(3, ByteArray(103))  // seq=3, size=103
        buffer.add(1, ByteArray(101))  // seq=1, size=101
        buffer.add(5, ByteArray(105))  // seq=5, size=105
        buffer.add(4, ByteArray(104))  // seq=4, size=104

        // Wait for playback
        delay(200)

        // Chunks should be played in sequence order, not arrival order
        assertEquals(listOf(100, 101, 102, 103, 104, 105), playedSeqs, "Should play chunks in sequence order")

        buffer.stop()
        playbackJob.cancelAndJoin()
    }
}
