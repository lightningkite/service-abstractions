package com.lightningkite.services.data

import kotlinx.io.Buffer
import kotlinx.io.RawSink

/**
 * Bytes you write to, cooperatively (via suspension) rather than by blocking a thread.
 *
 * The close is split in two: [close] ends the stream **cleanly** and may suspend (trailers, a final flush); [cancel]
 * abandons it, cannot suspend, and is therefore callable from a `catch` or `finally`. Whichever happens first wins —
 * both are idempotent and the second is a no-op.
 *
 * **Single-writer:** these methods must not be invoked concurrently.
 */
public interface SuspendingSink {

    /**
     * Writes everything in [from], consuming it. An empty [from] is a no-op.
     *
     * May suspend for backpressure; whether and when it does is **implementation-defined** — the interface makes no
     * guarantee about buffering bounds. Returning means the bytes were *accepted* (buffered or delivered), **not**
     * that they reached the far end; use [flush] for that.
     *
     * @throws IllegalStateException if the sink is already closed or cancelled.
     */
    public suspend fun write(from: Buffer)

    /**
     * Pushes accepted-but-buffered bytes toward the far end.
     *
     * @throws IllegalStateException if the sink is already closed or cancelled.
     */
    public suspend fun flush()

    /**
     * Ends the stream cleanly: writes anything still owed (a trailer, a terminating chunk), flushes, and releases
     * resources. Idempotent, and a no-op if the sink was already cancelled.
     *
     * Suspends, so coroutine cancellation can interrupt it — the stream then ends as abandoned rather than complete,
     * which is the right outcome but not always the desired one. Wrap in `withContext(NonCancellable)` when the
     * trailer must be written even on cancellation.
     */
    public suspend fun close()

    /**
     * Abandons the stream, releasing resources **without** finishing it, so the receiver can tell a truncated stream
     * from a complete one. Idempotent, and a no-op if the sink was already closed.
     *
     * @param cause why writing stopped. Required: a writer either finishes its stream or fails, and "abandoned for no
     * reason" is not a third outcome. Making it non-null is also what lets an implementation's `release(cause)` treat
     * a null cause as *proof* of a clean close. Implementations forward it to the far end where the transport can
     * carry it.
     */
    public fun cancel(cause: Throwable)
}

/**
 * Optional base for [SuspendingSink] implementations: owns the terminal transition, leaving [write] and [flush] to the
 * subclass with their natural signatures.
 *
 * Guarantees for implementations:
 * - [release] runs **exactly once**, on the first terminal event — [close], [cancel], or a [finish] that threw.
 * - [finish] runs at most once, only on a clean [close], and only before [release].
 */
public abstract class AbstractSuspendingSink : SuspendingSink {
    private var terminal = false

    /** True once the sink has been closed or cancelled. */
    protected val isTerminal: Boolean get() = terminal

    /** Fail fast on a write to a finished sink. Call at the top of [write] and [flush]. */
    protected fun checkWritable(): Unit =
        check(!terminal) { "This sink has already been closed or cancelled; it accepts no more bytes." }

    /** Write anything the format still owes (a trailer) and flush. Called at most once, on a clean [close]. */
    protected open suspend fun finish() {}

    /**
     * Release underlying resources. Called exactly once. A null [cause] means the stream finished cleanly and
     * [finish] has already run — that is the only way to observe null, so it is safe to commit the stream on it.
     */
    protected abstract fun release(cause: Throwable?)

    final override suspend fun close() {
        if (terminal) return
        try {
            finish()
        } catch (e: Throwable) {
            terminal = true
            release(e)
            throw e
        }
        terminal = true
        release(null)
    }

    final override fun cancel(cause: Throwable) {
        if (terminal) return
        terminal = true
        release(cause)
    }
}

/**
 * Runs [block] with this sink, then [close]s it — or [cancel]s it with the failure if [block] threw, so a partial
 * stream is never signalled as complete.
 *
 * [block] is `crossinline`: a non-local `return` out of it would skip the [close] and ship a stream with no trailer,
 * so the compiler rejects it instead.
 *
 * The `suspend` on the parameter type is load-bearing, not decoration: a `crossinline` lambda of non-suspend type may
 * be relocated into a non-suspend context, so dropping it would make every real call site — all of which suspend —
 * stop compiling.
 */
public suspend inline fun <T> SuspendingSink.use(crossinline block: suspend (SuspendingSink) -> T): T {
    val result = try {
        block(this)
    } catch (e: Throwable) {
        cancel(e)
        throw e
    }
    close()
    return result
}

/** A [SuspendingSink] that accumulates everything written into an in-memory [Buffer]. Never applies backpressure. */
public class BufferSuspendingSink : AbstractSuspendingSink() {
    public val buffer: Buffer = Buffer()

    override suspend fun write(from: Buffer) {
        checkWritable()
        from.transferTo(buffer)
    }

    override suspend fun flush() {
        checkWritable()
    }

    /** The collected bytes outlive the close — that is the whole point of this sink. */
    override fun release(cause: Throwable?) {}
}

/**
 * Adapts a blocking [RawSink] to a [SuspendingSink].
 *
 * **Caution:** the underlying writes are blocking; if [sink] can block a thread (a socket, pipe, or file), drive the
 * returned [SuspendingSink] on a blocking-capable dispatcher, never on an engine's event loop.
 *
 * @param closeUnderlying if false, finishing flushes but does not close [sink] — use this when writing into a
 * caller-owned sink whose lifecycle you must not affect.
 *
 * Abandoning drops the bytes this wrapper has not yet written, but it cannot un-write what [sink] already accepted:
 * with `closeUnderlying = true`, closing a *buffered* [RawSink] still emits whatever is parked in that lower layer,
 * because kotlinx-io has no abort primitive. Hand in an unbuffered sink when a truncated body must not reach the far
 * end at all.
 */
public fun RawSink.asSuspendingSink(closeUnderlying: Boolean = true): SuspendingSink =
    RawSinkSuspendingSink(this, closeUnderlying)

internal class RawSinkSuspendingSink(
    private val sink: RawSink,
    private val closeUnderlying: Boolean,
) : AbstractSuspendingSink() {
    override suspend fun write(from: Buffer) {
        checkWritable()
        sink.write(from, from.size)
    }

    override suspend fun flush() {
        checkWritable()
        sink.flush()
    }

    /** Only a clean close pushes the tail; an abandoned body must not have its partial bytes committed. */
    override suspend fun finish() {
        sink.flush()
    }

    override fun release(cause: Throwable?) {
        if (closeUnderlying) sink.close()
    }
}
