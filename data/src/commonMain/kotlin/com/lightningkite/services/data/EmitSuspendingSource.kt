package com.lightningkite.services.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.io.Buffer
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

/**
 * A [SuspendingSource] that produces data via an [emit] lambda which writes to a [SuspendingSink].
 *
 * This class bridges the gap between "push" style producers (that write to a sink) and "pull" style
 * consumers (that read from a source). The [emit] lambda runs in a separate coroutine and writes data
 * to a provided [SuspendingSink]. Consumers read from this source, and backpressure is applied to the
 * producer based on the [streamSuspendMode].
 *
 * ## Usage
 *
 * ```kotlin
 * val source = EmitSuspendingSource { sink ->
 *     sink.write(bufferOf("Hello "))
 *     sink.write(bufferOf("World"))
 * }
 *
 * val text = source.readRemaining().readString()  // "Hello World"
 * ```
 *
 * ## Automatic Sink Lifecycle
 *
 * The sink is **automatically closed** when the [emit] lambda returns. This means:
 * - You do not need to call [SuspendingSink.close] or wrap with [SuspendingSink.use]
 * - Any buffered data is automatically flushed when the lambda completes
 * - If the lambda throws an exception, the sink is cancelled and the exception propagates to the consumer
 *
 * ## Suspend Modes
 *
 * The [streamSuspendMode] controls when the producer suspends to apply backpressure:
 *
 * - **[SuspendMode.OnFlush]** (default): The producer only suspends when [SuspendingSink.flush] is called
 *   (or when the lambda returns, which closes and flushes automatically). This allows batching multiple
 *   writes before streaming data to the consumer. Best for scenarios where you want to control exactly
 *   when data is streamed. Note: to prevent unbounded memory growth, automatic backpressure is applied
 *   if more than 24KB accumulates without a flush—the producer will suspend until the consumer reads.
 *
 * - **[SuspendMode.OnWrite]**: The producer suspends after each non-empty [SuspendingSink.write] call,
 *   waiting for the consumer to read the data before continuing. This provides tighter backpressure and
 *   is useful when each write represents a logical chunk that should be consumed before producing more.
 *
 * ## Error Handling
 *
 * Exceptions thrown by the [emit] lambda are captured and rethrown to the consumer on the next [read] call.
 * This ensures errors propagate correctly across the producer-consumer boundary.
 *
 * ## Lifecycle
 *
 * - The producer runs in a separate coroutine on [Dispatchers.Default].
 * - When the source is canceled or exhausted, the producer coroutine is canceled.
 * - The sink is automatically closed when the [emit] lambda completes (normally or exceptionally).
 *
 * ## Thread Safety
 *
 * - **Single-reader**: This source must be read by only one coroutine at a time. Concurrent calls to
 *   [read], [cancel], or [close] from multiple coroutines are not supported.
 * - **Single-writer**: The [SuspendingSink] provided to the [emit] lambda must be written by only one
 *   coroutine at a time. Concurrent calls to [SuspendingSink.write], [SuspendingSink.flush], etc. are
 *   not supported.
 *
 * @param streamSuspendMode Controls when backpressure is applied. Defaults to [SuspendMode.OnFlush].
 * @param emit The producer lambda that writes data to the provided [SuspendingSink]. The sink is
 *             automatically closed when this lambda returns.
 *
 * @see SuspendingSource
 * @see SuspendingSink
 */
public class EmitSuspendingSource(
    private val streamSuspendMode: SuspendMode = SuspendMode.OnFlush,
    private val emit: suspend (SuspendingSink) -> Unit,
): AbstractSuspendingSource() {
    /**
     * Controls when the producer suspends to apply backpressure.
     */
    public enum class SuspendMode {
        /**
         * Suspend after each non-empty write, providing per-write backpressure.
         * Use when each write is a logical chunk that should be consumed before producing more.
         */
        OnWrite,

        /**
         * Suspend only when flush is called (or on close, which flushes automatically).
         * Allows batching multiple writes before streaming to the consumer.
         * This is the default mode.
         */
        OnFlush
    }

    private typealias TransferBytes = Long

    private val buffer = Buffer()

    private val emitScope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var emitContinuation: CancellableContinuation<Unit>? = null

    private val stream = Channel<Result<TransferBytes>>(Channel.CONFLATED)

    override suspend fun fill(into: Buffer): Long {
        val ec = emitContinuation
        emitContinuation = null  // Clear BEFORE resuming to avoid race with ensureWritable()

        if (ec == null) emitScope.launch {  // first read - start emitting
            val sink = object : AbstractSuspendingSink() {
                fun resumeStream(transfer: TransferBytes = TRANSFER_ALL) {
                    stream.trySend(Result.success(transfer))
                }

                fun ensureWritable() {
                    super.checkWritable()
                    check(emitContinuation == null) {
                        "Multiple writes occurred before more bytes were requested"
                    }
                }

                override fun release(cause: Throwable?) {
                    if (cause == null) resumeStream()
                    else stream.trySend(Result.failure(cause))
                }

                override suspend fun write(from: Buffer) {
                    ensureWritable()
                    from.transferTo(buffer)
                    if (streamSuspendMode == SuspendMode.OnWrite && buffer.size > 0L) {
                        // if buffer is empty then we just wrote no data, don't suspend, keep writing until some data is available.
                        suspendCancellableCoroutine {
                            emitContinuation = it
                            resumeStream()
                        }
                    }
                    else if (streamSuspendMode == SuspendMode.OnFlush && buffer.size >= AUTO_BACKPRESSURE) {
                        // backpressure automatically to prevent too much from being held in memory
                        suspendCancellableCoroutine {
                            emitContinuation = it
                            resumeStream(AUTO_BACKPRESSURE)
                        }
                    }
                }

                override suspend fun flush() {
                    checkWritable()  // Only check terminal state, not emitContinuation
                    if (streamSuspendMode == SuspendMode.OnFlush && buffer.size > 0L) {
                        suspendCancellableCoroutine {
                            emitContinuation = it
                            resumeStream()
                        }
                    }
                }
            }
            try {
                sink.use(emit)
            } catch (e: Throwable) {
                stream.trySend(Result.failure(e))
            }
        }
        else ec.resume(Unit)    // we've already started reading, resume from where we left off

        // Wait for producer to signal data is ready (or stream ended/failed)
        val transfer = stream.receive().getOrThrow()    // rethrow exceptions here so the receiver sees them instead of being confined to emit scope

        if (buffer.size == 0L) {
            return -1L   // If no bytes were written then emit closed.
        }

        return if (transfer == TRANSFER_ALL) buffer.transferTo(into)
        else buffer.readAtMostTo(into, transfer)
    }

    override fun release(cause: Throwable?) {
        stream.close()
        emitScope.cancel()
    }

    public companion object {
        private const val SEGMENT_SIZE = 8192L

        private const val AUTO_BACKPRESSURE: TransferBytes = SEGMENT_SIZE * 3

        private const val TRANSFER_ALL: TransferBytes = -1L
    }
}

