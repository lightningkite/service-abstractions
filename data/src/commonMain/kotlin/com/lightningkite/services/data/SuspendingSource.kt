package com.lightningkite.services.data

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Sink

/**
 * Bytes you read from, cooperatively (via suspension) rather than by blocking a thread.
 *
 * **Single-reader:** [read] and [cancel] must not be invoked concurrently.
 */
public interface SuspendingSource {
    /** Current lifecycle state. Read-only to consumers; the producer drives transitions. */
    public val state: StreamState

    /**
     * Ensures [into] holds at least [count] bytes — reading from the underlying stream (and possibly buffering
     * further ahead) as needed — then reports whether that succeeded.
     *
     * - Returns `true` when [into] holds at least [count] bytes.
     * - Returns `false` when end-of-stream was reached first; the bytes read before the end are still present in
     *   [into], and [state] distinguishes a clean end ([StreamState.Complete]) from an error
     *   ([StreamState.ClosedAbnormally]).
     * - If [into] already holds at least [count] bytes, this is a no-op and returns `true`.
     *
     * [into] is your **persistent** read buffer: pass the same buffer on every call and consume the bytes you use
     * from it between calls. Implementations may leave more than [count] bytes in [into] (read-ahead). Pass
     * `count = 1` for "give me whatever is available right now."
     */
    public suspend fun read(into: Buffer, count: Long): Boolean

    /** Abandon reading; the producer may stop and release resources. Idempotent. */
    public fun cancel(cause: Throwable? = null)
}

/**
 * Base class that owns the [state] machine and the [read] loop, so implementations only provide a single primitive.
 *
 * Guarantees for implementations:
 * - [release] is called **exactly once**, on the first transition out of [StreamState.Open] — whether that is a clean
 *   end-of-stream, a [fill] that threw, or an explicit [cancel]. So resource cleanup lives in one place and never
 *   leaks on the happy path.
 */
public abstract class AbstractSuspendingSource : SuspendingSource {
    final override var state: StreamState = StreamState.Open
        private set

    /**
     * Reads more bytes into [into], making progress toward [count]. Called only while [state] is
     * [StreamState.Open] and `into.size < count`.
     *
     * @return `true` if more bytes may still be available, `false` if the underlying stream has ended. Must move at
     * least one byte into [into] whenever it returns `true`, otherwise it may throw to signal an error.
     */
    protected abstract suspend fun fill(into: Buffer, count: Long): Boolean

    /** Release underlying resources. [cause] is null for a clean end/cancel. Called at most once, on the terminal transition. */
    protected abstract fun release(cause: Throwable?)

    private fun terminate(newState: StreamState, cause: Throwable?) {
        if (state != StreamState.Open) return
        state = newState
        release(cause)
    }

    final override suspend fun read(into: Buffer, count: Long): Boolean {
        // A source that already ended abnormally keeps reporting the error, rather than masquerading as a clean EOF.
        (state as? StreamState.ClosedAbnormally)?.let { throw it.cause }
        while (into.size < count && state == StreamState.Open) {
            val before = into.size
            val more = try {
                fill(into, count)
            } catch (e: Throwable) {
                terminate(StreamState.ClosedAbnormally(e), e)
                throw e
            }
            if (!more) {
                terminate(StreamState.Complete, null)
            } else if (into.size <= before) {
                // Contract violation: fill claimed progress without moving a byte. Fail fast, but still release.
                val err = IllegalStateException("fill() returned true without moving any bytes; this would spin forever")
                terminate(StreamState.ClosedAbnormally(err), err)
                throw err
            }
        }
        return into.size >= count
    }

    final override fun cancel(cause: Throwable?) {
        terminate(cause?.let { StreamState.ClosedAbnormally(it) } ?: StreamState.Complete, cause)
    }
}

/** How many bytes the "drain everything" helpers pull per read. Only a batching hint; sources may read-ahead more. */
private const val TRANSFER_CHUNK: Long = 8192

/**
 * Reads the entire remaining stream into a new [Buffer].
 */
public suspend fun SuspendingSource.readRemaining(): Buffer {
    val out = Buffer()
    val staging = Buffer()
    while (read(staging, TRANSFER_CHUNK)) staging.transferTo(out)
    staging.transferTo(out) // final partial left in staging once EOF was reached
    return out
}

/**
 * Streams the entire remaining source into a blocking [Sink], returning the number of bytes transferred.
 *
 * Uses `read(_, 1)` so each hop forwards whatever bytes are available immediately instead of stalling until a fixed
 * chunk fills — important for trickle/segmented streams being proxied through.
 */
public suspend fun SuspendingSource.transferTo(sink: Sink): Long {
    var total = 0L
    val staging = Buffer()
    while (read(staging, 1)) {
        total += staging.size
        staging.transferTo(sink)
    }
    total += staging.size
    staging.transferTo(sink)
    return total
}

/**
 * Streams the entire remaining source into a cooperative [SuspendingSink], returning the number of bytes transferred.
 *
 * Uses `read(_, 1)` so each hop forwards whatever bytes are available immediately (see [transferTo] above).
 */
public suspend fun SuspendingSource.transferTo(sink: SuspendingSink): Long {
    var total = 0L
    val staging = Buffer()
    while (read(staging, 1)) {
        total += staging.size
        sink.writeAll(staging)
    }
    total += staging.size
    sink.writeAll(staging)
    return total
}

/**
 * Adapts an in-memory [Buffer] to a [SuspendingSource]. Consuming it never suspends for I/O. The buffer's bytes are
 * moved out as they are read.
 */
public fun Buffer.asSuspendingSource(): SuspendingSource = BufferSuspendingSource(this)

internal class BufferSuspendingSource(private val buffer: Buffer) : AbstractSuspendingSource() {
    override suspend fun fill(into: Buffer, count: Long): Boolean {
        if (buffer.exhausted()) return false
        buffer.transferTo(into) // move everything available (read-ahead is fine)
        return !buffer.exhausted()
    }

    override fun release(cause: Throwable?) {
        buffer.clear()
    }
}

/**
 * Adapts a blocking [RawSource] to a [SuspendingSource].
 *
 * **Caution:** the underlying reads are blocking; if [source] can block a thread (a socket, pipe, or file), consume
 * the returned [SuspendingSource] on a blocking-capable dispatcher, never on an engine's event loop. Prefer a natively
 * non-blocking implementation for those cases.
 */
public fun RawSource.asSuspendingSource(): SuspendingSource = RawSourceSuspendingSource(this)

internal class RawSourceSuspendingSource(private val source: RawSource) : AbstractSuspendingSource() {
    override suspend fun fill(into: Buffer, count: Long): Boolean {
        val want = (count - into.size).coerceAtLeast(TRANSFER_CHUNK)
        return source.readAtMostTo(into, want) != -1L
    }

    override fun release(cause: Throwable?) {
        source.close()
    }
}
