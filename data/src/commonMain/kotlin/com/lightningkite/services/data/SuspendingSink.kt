package com.lightningkite.services.data

import kotlinx.io.Buffer
import kotlinx.io.RawSink

/**
 * Bytes you write to, cooperatively (via suspension) rather than by blocking a thread.
 *
 * **Single-writer:** [write], [flush] and the [close] methods must not be invoked concurrently.
 */
public interface SuspendingSink {
    /** Current lifecycle state. */
    public val state: StreamState

    /**
     * Writes [count] bytes from [from], consuming them from [from].
     *
     * May suspend for backpressure; whether and when it does is **implementation-defined** — the interface makes no
     * guarantee about buffering bounds. Returning means the bytes were *accepted* (buffered or delivered), **not**
     * necessarily flushed to the far end; use [flush] for that.
     */
    public suspend fun write(from: Buffer, count: Long)

    /** Pushes any accepted-but-buffered bytes toward the far end. */
    public suspend fun flush()

    /** Signals clean end-of-stream, transitioning [state] to [StreamState.Complete]. Idempotent. */
    public suspend fun close()

    /** Signals abnormal termination, transitioning [state] to [StreamState.ClosedAbnormally]. Idempotent. */
    public fun close(cause: Throwable)
}

/**
 * Writes all remaining bytes of [from].
 */
public suspend fun SuspendingSink.writeAll(from: Buffer) {
    if (from.size > 0L) write(from, from.size)
}

/**
 * A [SuspendingSink] that accumulates everything written into an in-memory [Buffer]. Never applies backpressure.
 */
public class BufferSuspendingSink : SuspendingSink {
    public val buffer: Buffer = Buffer()

    override var state: StreamState = StreamState.Open
        private set

    override suspend fun write(from: Buffer, count: Long) {
        buffer.write(from, count)
    }

    override suspend fun flush() {}

    override suspend fun close() {
        if (state == StreamState.Open) state = StreamState.Complete
    }

    override fun close(cause: Throwable) {
        if (state == StreamState.Open) state = StreamState.ClosedAbnormally(cause)
    }
}

/**
 * Adapts a blocking [RawSink] to a [SuspendingSink].
 *
 * **Caution:** the underlying writes are blocking; if [sink] can block a thread (a socket, pipe, or file), drive the
 * returned [SuspendingSink] on a blocking-capable dispatcher, never on an engine's event loop.
 *
 * @param closeUnderlying if false, [close] and [close] flush but do not close [sink] — use this when writing into a
 * caller-owned sink whose lifecycle you must not affect.
 */
public fun RawSink.asSuspendingSink(closeUnderlying: Boolean = true): SuspendingSink =
    RawSinkSuspendingSink(this, closeUnderlying)

internal class RawSinkSuspendingSink(
    private val sink: RawSink,
    private val closeUnderlying: Boolean,
) : SuspendingSink {
    override var state: StreamState = StreamState.Open
        private set

    override suspend fun write(from: Buffer, count: Long) {
        sink.write(from, count)
    }

    override suspend fun flush() {
        sink.flush()
    }

    override suspend fun close() {
        if (state != StreamState.Open) return
        if (closeUnderlying) sink.close() else sink.flush()
        state = StreamState.Complete
    }

    override fun close(cause: Throwable) {
        if (state != StreamState.Open) return
        if (closeUnderlying) sink.close() else sink.flush()
        state = StreamState.ClosedAbnormally(cause)
    }
}
