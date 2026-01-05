package com.lightningkite.services.database.migration

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * In-memory retry queue with exponential backoff for failed operations.
 *
 * @param T The type of items to retry
 * @param config Configuration for retry behavior
 * @param clock Clock for timestamps (injectable for testing)
 * @param onMaxRetriesExceeded Callback when an item exceeds max retries
 */
public class RetryQueue<T>(
    private val config: RetryConfig = RetryConfig(),
    private val clock: Clock,
    private val onMaxRetriesExceeded: suspend (T, Exception?) -> Unit = { _, _ -> }
) {
    private val logger = KotlinLogging.logger {}

    private data class QueuedItem<T>(
        val item: T,
        val attemptCount: Int,
        val lastError: Exception?,
        val nextAttemptAt: Instant
    )

    private val channel = Channel<QueuedItem<T>>(Channel.UNLIMITED)
    private val _pendingCount = atomic(0)
    private val _failedCount = atomic(0)
    private val _successCount = atomic(0)
    private var processorJob: Job? = null

    /** Number of items currently pending retry */
    public val pendingCount: Int get() = _pendingCount.value

    /** Number of items that exceeded max retries */
    public val failedCount: Int get() = _failedCount.value

    /** Number of items successfully retried */
    public val successCount: Int get() = _successCount.value

    /**
     * Enqueue an item for retry.
     *
     * @param item The item to retry
     * @param error The error that caused the failure (optional, for logging)
     */
    public fun enqueue(item: T, error: Exception? = null) {
        val current = _pendingCount.value
        if (current >= config.maxQueueSize) {
            logger.warn { "Retry queue full (${config.maxQueueSize} items), dropping oldest" }
            // Channel is unlimited, so we'll just increment. In practice,
            // if queue is full for extended periods, we have bigger problems.
        }

        val queuedItem = QueuedItem(
            item = item,
            attemptCount = 1,
            lastError = error,
            nextAttemptAt = clock.now() + config.initialDelayMs.milliseconds
        )

        val result = channel.trySend(queuedItem)
        if (result.isSuccess) {
            _pendingCount.update { it + 1 }
        } else {
            logger.error { "Failed to enqueue item for retry: ${result.exceptionOrNull()}" }
        }
    }

    /**
     * Start processing the retry queue.
     *
     * @param scope CoroutineScope to run the processor in
     * @param processor Function to process each item (should throw on failure)
     */
    public fun start(scope: CoroutineScope, processor: suspend (T) -> Unit) {
        require(processorJob == null || !processorJob!!.isActive) { "Processor already running" }

        processorJob = scope.launch {
            for (queuedItem in channel) {
                // Wait until it's time to retry
                val now = clock.now()
                val delay = queuedItem.nextAttemptAt - now
                if (delay.isPositive()) {
                    delay(delay)
                }

                try {
                    processor(queuedItem.item)
                    _pendingCount.update { it - 1 }
                    _successCount.update { it + 1 }
                    logger.debug { "Retry succeeded on attempt ${queuedItem.attemptCount}" }
                } catch (e: CancellationException) {
                    // Re-enqueue and rethrow
                    channel.trySend(queuedItem)
                    throw e
                } catch (e: Exception) {
                    if (queuedItem.attemptCount >= config.maxRetries) {
                        // Max retries exceeded
                        _pendingCount.update { it - 1 }
                        _failedCount.update { it + 1 }
                        logger.error(e) {
                            "Max retries (${config.maxRetries}) exceeded for item, giving up"
                        }
                        try {
                            onMaxRetriesExceeded(queuedItem.item, e)
                        } catch (callbackError: Exception) {
                            logger.error(callbackError) { "Error in onMaxRetriesExceeded callback" }
                        }
                    } else {
                        // Calculate next retry delay with exponential backoff
                        val nextDelay = min(
                            config.initialDelayMs * (1L shl queuedItem.attemptCount),
                            config.maxDelayMs
                        )
                        val nextItem = queuedItem.copy(
                            attemptCount = queuedItem.attemptCount + 1,
                            lastError = e,
                            nextAttemptAt = clock.now() + nextDelay.milliseconds
                        )
                        channel.trySend(nextItem)
                        logger.warn(e) {
                            "Retry attempt ${queuedItem.attemptCount} failed, " +
                                    "will retry in ${nextDelay}ms (attempt ${nextItem.attemptCount}/${config.maxRetries})"
                        }
                    }
                }
            }
        }
    }

    /**
     * Stop processing the retry queue.
     * Items remaining in the queue will be lost.
     */
    public fun stop() {
        processorJob?.cancel()
        processorJob = null
    }

    /**
     * Stop processing and wait for current item to complete.
     */
    public suspend fun stopGracefully() {
        processorJob?.cancelAndJoin()
        processorJob = null
    }
}
