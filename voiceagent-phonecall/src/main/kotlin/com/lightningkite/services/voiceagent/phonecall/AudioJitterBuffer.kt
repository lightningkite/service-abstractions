package com.lightningkite.services.voiceagent.phonecall

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.PriorityQueue
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger("AudioJitterBuffer")

/**
 * A jitter buffer that smooths out irregular audio delivery and corrects ordering issues.
 *
 * This buffer accumulates audio chunks with sequence numbers, sorts them by sequence,
 * and plays them back at a steady rate. This handles both timing jitter (from network
 * latency, PubSub polling) and ordering issues (from PubSub reordering).
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
 *     buffer.add(event.contentIndex, event.audio)
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
    private data class SequencedChunk(val seq: Int, val audio: ByteArray) : Comparable<SequencedChunk> {
        override fun compareTo(other: SequencedChunk): Int = seq.compareTo(other.seq)
    }

    private val timeSource = TimeSource.Monotonic
    private val chunks = PriorityQueue<SequencedChunk>()
    private val mutex = Mutex()

    @Volatile
    private var bufferedMs: Long = 0L

    @Volatile
    private var generation = 0  // Incremented on clear to signal restart

    @Volatile
    private var stopped = false

    @Volatile
    private var nextExpectedSeq = 0  // Track expected sequence for reorder detection

    /**
     * Add audio to the buffer with a sequence number for ordering.
     *
     * @param seq Sequence number (e.g., contentIndex from voice agent)
     * @param audio Raw audio bytes (µ-law by default)
     */
    public suspend fun add(seq: Int, audio: ByteArray) {
        if (stopped) return
        val durationMs = audio.size / bytesPerMs
        mutex.withLock {
            chunks.add(SequencedChunk(seq, audio))
            bufferedMs += durationMs
        }
    }

    /**
     * Clear the buffer and restart buffering phase.
     * Call this when the user starts speaking to cancel pending audio.
     */
    public suspend fun clear() {
        mutex.withLock {
            generation++
            bufferedMs = 0
            nextExpectedSeq = 0
            chunks.clear()
        }
        logger.debug { "Buffer cleared, restarting buffering phase" }
    }

    /**
     * Stop the buffer permanently. Call when the session ends.
     */
    public fun stop() {
        stopped = true
        generation++
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

            // Safe diagnostic counters - can't affect control flow
            var chunkCount = 0
            var lastStatsMs = 0L
            var behindCount = 0
            var maxBehindMs = 0L
            var reorderedCount = 0

            while (generation == currentGen && !stopped) {
                val chunk = mutex.withLock { chunks.poll() }
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
                chunkCount++

                // Track if this chunk was reordered (arrived out of sequence)
                if (chunk.seq != nextExpectedSeq && nextExpectedSeq > 0) {
                    reorderedCount++
                    logger.debug { "Reordered chunk: expected seq=$nextExpectedSeq, got seq=${chunk.seq}" }
                }
                nextExpectedSeq = chunk.seq + 1

                val chunkMs = chunk.audio.size / bytesPerMs
                mutex.withLock { bufferedMs -= chunkMs }

                // Calculate when this chunk should be sent
                val targetTimeMs = playedMs
                val actualElapsedMs = playbackStart.elapsedNow().inWholeMilliseconds
                val waitMs = targetTimeMs - actualElapsedMs

                if (waitMs > 0) {
                    delay(waitMs)
                }

                // Safe behind-schedule tracking (doesn't affect flow)
                if (waitMs < -10) {
                    behindCount++
                    val behind = -waitMs
                    if (behind > maxBehindMs) maxBehindMs = behind
                }

                sendAudio(chunk.audio)
                playedMs += chunkMs

                // Summary logging every second (safe - after sendAudio)
                if (playedMs - lastStatsMs >= 1000) {
                    logger.info { "JITTER played=${playedMs}ms buf=${bufferedMs}ms chunks=$chunkCount behind=$behindCount maxBehind=${maxBehindMs}ms reordered=$reorderedCount" }
                    lastStatsMs = playedMs
                    behindCount = 0
                    maxBehindMs = 0
                }
            }

            logger.debug { "Playback interrupted after ${playedMs}ms, $chunkCount chunks, $reorderedCount reordered" }
        }

        logger.debug { "Playback loop stopped" }
    }
}
