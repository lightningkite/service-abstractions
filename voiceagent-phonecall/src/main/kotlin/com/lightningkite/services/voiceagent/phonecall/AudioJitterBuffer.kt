package com.lightningkite.services.voiceagent.phonecall

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger("AudioJitterBuffer")

/**
 * A jitter buffer that smooths out irregular audio delivery for real-time playback.
 *
 * Unlike a simple throttle buffer, this accumulates an initial buffer before starting
 * playback, then drains at a steady rate regardless of when audio arrives. This absorbs
 * jitter from network latency, PubSub polling, etc.
 *
 * ## Usage
 *
 * ```kotlin
 * val buffer = AudioJitterBuffer(targetBufferMs = 150)
 *
 * // In one coroutine: run the playback loop
 * launch {
 *     buffer.runPlayback { audio ->
 *         sendToPhone(audio)
 *     }
 * }
 *
 * // In another coroutine: add audio as it arrives
 * audioEvents.collect { event ->
 *     buffer.add(event.audio)
 * }
 *
 * // On interruption (user speaks):
 * buffer.clear()
 * ```
 *
 * @param targetBufferMs How much audio (in ms) to buffer before starting playback.
 *   Higher values = more latency but smoother playback. 100-200ms is typical.
 * @param bytesPerMs Audio byte rate. Default is 8 for µ-law (8000 Hz, 1 byte/sample).
 */
public class AudioJitterBuffer(
    public val targetBufferMs: Long = 150L,
    private val bytesPerMs: Int = 8,  // µ-law: 8000 Hz = 8 bytes/ms
) {
    private val timeSource = TimeSource.Monotonic
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var bufferedMs: Long = 0L

    @Volatile
    private var generation = 0  // Incremented on clear to signal restart

    @Volatile
    private var stopped = false

    /**
     * Add audio to the buffer.
     *
     * @param audio Raw audio bytes (µ-law by default)
     */
    public fun add(audio: ByteArray) {
        if (stopped) return
        val durationMs = audio.size / bytesPerMs
        bufferedMs += durationMs
        chunks.trySend(audio)
    }

    /**
     * Clear the buffer and restart buffering phase.
     * Call this when the user starts speaking to cancel pending audio.
     */
    public fun clear() {
        generation++
        bufferedMs = 0
        // Drain existing chunks
        while (chunks.tryReceive().isSuccess) {
            // discard
        }
        logger.debug { "Buffer cleared, restarting buffering phase" }
    }

    /**
     * Stop the buffer permanently. Call when the session ends.
     */
    public fun stop() {
        stopped = true
        generation++
        chunks.close()
    }

    /**
     * Current buffer level in milliseconds.
     */
    public val currentBufferMs: Long get() = bufferedMs

    /**
     * Run the playback loop. This suspends and continuously plays audio at a steady rate.
     * Call this from a dedicated coroutine.
     *
     * @param sendAudio Called for each audio chunk at the appropriate playback time
     */
    public suspend fun runPlayback(sendAudio: suspend (ByteArray) -> Unit) {
        while (!stopped) {
            val currentGen = generation

            // Phase 1: Wait for initial buffer to fill
            logger.debug { "Buffering phase started, target=${targetBufferMs}ms" }
            while (bufferedMs < targetBufferMs && generation == currentGen && !stopped) {
                delay(10)
            }

            if (generation != currentGen || stopped) {
                logger.debug { "Buffering interrupted, restarting" }
                continue
            }

            logger.debug { "Buffering complete (${bufferedMs}ms), starting playback" }

            // Phase 2: Steady playback
            val playbackStart = timeSource.markNow()
            var playedMs = 0L
            var underrunCount = 0

            while (generation == currentGen && !stopped) {
                val chunk = chunks.tryReceive().getOrNull()
                if (chunk == null) {
                    // Buffer underrun - wait for more audio
                    underrunCount++
                    if (underrunCount > 20) {  // ~100ms of underrun
                        logger.warn { "Buffer underrun for ${underrunCount * 5}ms, buffer=${bufferedMs}ms" }
                        underrunCount = 0
                    }
                    delay(5)
                    continue
                }

                underrunCount = 0
                val chunkMs = chunk.size / bytesPerMs
                bufferedMs -= chunkMs

                // Calculate when this chunk should be sent
                val targetTimeMs = playedMs
                val actualElapsedMs = playbackStart.elapsedNow().inWholeMilliseconds
                val waitMs = targetTimeMs - actualElapsedMs

                if (waitMs > 0) {
                    delay(waitMs)
                }

                sendAudio(chunk)
                playedMs += chunkMs
            }

            logger.debug { "Playback interrupted after ${playedMs}ms" }
        }

        logger.debug { "Playback loop stopped" }
    }
}
