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

    /**
     * Moves whatever bytes are available into [into], suspending until at least one is.
     *
     * A single call may hand over an arbitrarily large batch — how much arrives at once is the producer's choice, not
     * the reader's, so a consumer doing synchronous per-batch work should not assume a bounded size.
     *
     * @return the number of bytes moved (always > 0), or `-1` once the source is exhausted.
     * @throws IllegalStateException if the source was already cancelled, or already failed with an error you caught.
     * Reading on from either is a bug, and a silent `-1` there would be indistinguishable from a clean end — exactly
     * the truncation-looks-complete failure this API exists to prevent.
     *
     * Implementations release their resources on the terminal transition, but the reader still owns the lifecycle:
     * [cancel] the source if you stop reading before exhaustion (see [use]).
     */
    public suspend fun read(into: Buffer): Long

    /**
     * Abandon reading; the producer may stop and release resources. Idempotent, and safe on an already-terminal
     * source.
     *
     * @param cause why reading stopped, or null for an ordinary "done with this". Implementations forward it to the
     * producer, so a failed consumer can tell the far end this was not a clean finish.
     */
    public fun cancel(cause: Throwable? = null)
}

/**
 * Optional base for [SuspendingSource] implementations: owns the terminal transition, so a subclass supplies only the
 * read primitive and its cleanup.
 *
 * Guarantees for implementations:
 * - [release] runs **exactly once**, on the first terminal event — exhaustion, a [fill] that threw, or [cancel].
 * - [fill] is never called on a terminal source, so it never has to guard against use-after-end.
 */
public abstract class AbstractSuspendingSource : SuspendingSource {
    private var terminal: Terminal? = null

    /** Moves at least one byte into [into], or returns -1 at end of stream. Throw to report an error. */
    protected abstract suspend fun fill(into: Buffer): Long

    /** Release underlying resources. [cause] is null for a clean end or a plain cancel. Called exactly once. */
    protected abstract fun release(cause: Throwable?)

    final override suspend fun read(into: Buffer): Long {
        when (terminal) {
            Terminal.Exhausted -> return -1L
            Terminal.Aborted -> throw IllegalStateException("This source was cancelled or failed; it cannot be read again.")
            null -> {}
        }
        val moved = try {
            fill(into)
        } catch (e: Throwable) {
            terminate(Terminal.Aborted, e)
            throw e
        }
        if (moved < 0L) {
            terminate(Terminal.Exhausted, null)
            return -1L
        }
        // Contract violation: no bytes moved and no end-of-stream reported. Every drain helper treats that as
        // progress, so letting it through is a silent busy-loop on the caller's thread. Fail fast instead.
        if (moved == 0L) {
            val error = IllegalStateException("fill() moved no bytes without reporting end-of-stream; this would spin forever")
            terminate(Terminal.Aborted, error)
            throw error
        }
        return moved
    }

    final override fun cancel(cause: Throwable?): Unit = terminate(Terminal.Aborted, cause)

    private fun terminate(state: Terminal, cause: Throwable?) {
        if (terminal != null) return
        terminal = state
        release(cause)
    }

    /** Exhaustion and abandonment are both terminal, but only exhaustion may report itself again as a clean `-1`. */
    private enum class Terminal { Exhausted, Aborted }
}

/**
 * Runs [block] with this source, then cancels it — passing the failure as the cause if [block] threw, so the producer
 * learns the read was abandoned rather than finished. Unlike a sink, a source that is dropped early is unremarkable,
 * so cancelling on the success path is not an error signal.
 *
 * The cleanup is in a `finally`, so it survives a non-local `return` out of [block].
 */
public inline fun <T> SuspendingSource.use(block: (SuspendingSource) -> T): T {
    var cause: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        cause = e
        throw e
    } finally {
        cancel(cause)
    }
}

/**
 * Ensures [into] holds at least [count] bytes, reading as needed; returns false if the source ended first, in which
 * case the bytes read before the end are still in [into].
 *
 * For consumers that need framing — a length prefix, a fixed-size header, a multipart boundary. Pass the same buffer
 * on every call and consume what you use between calls; on return [into] may hold considerably more than [count], and
 * that surplus is yours to keep for the next frame.
 */
public suspend fun SuspendingSource.request(into: Buffer, count: Long): Boolean {
    while (into.size < count) if (read(into) < 0L) return false
    return true
}

/** Reads the entire remaining stream into a new [Buffer]. */
public suspend fun SuspendingSource.readRemaining(): Buffer {
    val out = Buffer()
    while (read(out) >= 0L) { /* accumulate until exhausted */ }
    return out
}

/** Streams everything remaining into a blocking [Sink], returning the number of bytes transferred. */
public suspend fun SuspendingSource.transferTo(sink: Sink): Long {
    var total = 0L
    val staging = Buffer()
    while (true) {
        val moved = read(staging)
        if (moved < 0L) return total
        total += moved
        staging.transferTo(sink)
    }
}

/** Streams everything remaining into a cooperative [SuspendingSink], returning the number of bytes transferred. */
public suspend fun SuspendingSource.transferTo(sink: SuspendingSink): Long {
    var total = 0L
    val staging = Buffer()
    while (true) {
        val moved = read(staging)
        if (moved < 0L) return total
        total += moved
        sink.write(staging)
    }
}

/**
 * Adapts an in-memory [Buffer] to a [SuspendingSource]. Consuming it never suspends for I/O. The buffer's bytes are
 * moved out as they are read.
 */
public fun Buffer.asSuspendingSource(): SuspendingSource = BufferSuspendingSource(this)

internal class BufferSuspendingSource(private val buffer: Buffer) : AbstractSuspendingSource() {
    override suspend fun fill(into: Buffer): Long = if (buffer.exhausted()) -1L else buffer.transferTo(into)
    override fun release(cause: Throwable?): Unit = buffer.clear()
}

/**
 * Adapts a blocking [RawSource] to a [SuspendingSource].
 *
 * **Caution:** the underlying reads are blocking; if [source] can block a thread (a socket, pipe, or file), consume
 * the returned [SuspendingSource] on a blocking-capable dispatcher, never on an engine's event loop. Prefer a natively
 * non-blocking implementation for those cases.
 *
 * @param chunkSize how many bytes to request from [source] per read. This is the one place a size must be chosen,
 * because this is where bytes are pulled into existence rather than handed over from an existing buffer.
 */
public fun RawSource.asSuspendingSource(chunkSize: Long = 8192): SuspendingSource =
    RawSourceSuspendingSource(this, chunkSize)

internal class RawSourceSuspendingSource(
    private val source: RawSource,
    private val chunkSize: Long,
) : AbstractSuspendingSource() {
    override suspend fun fill(into: Buffer): Long = source.readAtMostTo(into, chunkSize)
    override fun release(cause: Throwable?): Unit = source.close()
}
