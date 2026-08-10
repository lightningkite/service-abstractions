package com.lightningkite.services.data

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.Buffer
import kotlin.coroutines.resume

public class EmitSuspendingSource(
    private val streamSuspendMode: SuspendMode = SuspendMode.OnFlush,
    private val emit: suspend (SuspendingSink) -> Unit,
): AbstractSuspendingSource() {
    public enum class SuspendMode {
        OnWrite,
        OnFlush
    }

    private val buffer = Buffer()

    private val emitScope = CoroutineScope(ioDispatcher)

    private var emitContinuation: CancellableContinuation<Unit>? = null
    private var fillContinuation: CancellableContinuation<Unit>? = null

    private var doneWriting = false

    /** Exception thrown by the producer, to be rethrown to the consumer. */
    private var producerException: Throwable? = null

    override suspend fun fill(into: Buffer): Long {
        val ec = emitContinuation
        doneWriting = false

        if (ec == null) emitScope.launch {
            val sink = object : AbstractSuspendingSink() {
                fun resumeStream() {
                    doneWriting = true
                    fillContinuation?.resume(Unit)
                }

                fun ensureWritable() {
                    super.checkWritable()
                    check(emitContinuation == null) {
                        "Multiple writes occurred before more bytes were requested"
                    }
                }

                override fun release(cause: Throwable?) {
                    if (cause != null) {
                        this@EmitSuspendingSource.cancel(cause)
                    }
                    resumeStream()
                }

                override suspend fun write(from: Buffer) {
                    ensureWritable()
                    from.transferTo(buffer)
                    if (streamSuspendMode == SuspendMode.OnWrite && buffer.size > 0L) { // if buffer is empty then we just wrote no data, don't suspend, keep writing until some data is available.
                        suspendCancellableCoroutine {
                            emitContinuation = it
                            resumeStream()
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
                emit(sink)
            } catch (e: Throwable) {
                // Capture the exception to rethrow to the consumer
                producerException = e
                sink.cancel(e)  // This will call release() which calls resumeStream()
            }
        }
        else ec.resume(Unit)

        emitContinuation = null

        // if stream point has already been reached then don't bother suspending
        if (!doneWriting) suspendCancellableCoroutine {
            // suspend until the emit operation reaches the next stream point (on write or flush)
            fillContinuation = it
        }

        // stream point reached, fillContinuation has either been resumed or wasn't necessary

        fillContinuation = null

        // Check if the producer threw an exception and rethrow it to the consumer
        producerException?.let { throw it }

        if (buffer.size == 0L) {
            return -1L   // If no bytes were written then emit either canceled or closed.
        }

        return buffer.transferTo(into)
    }

    override fun release(cause: Throwable?) {
        emitScope.cancel()
    }
}