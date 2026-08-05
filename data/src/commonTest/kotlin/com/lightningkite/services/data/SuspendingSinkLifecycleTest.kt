package com.lightningkite.services.data

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Lifecycle guarantees for [AbstractSuspendingSink]. The invariant that matters most: `release(null)` happens **only**
 * on a clean [SuspendingSink.close], so an implementation can commit its stream on a null cause. If cancel could also
 * produce null, every sink would silently certify truncated bodies as complete.
 */
class SuspendingSinkLifecycleTest {

    /** Records the terminal transition an [AbstractSuspendingSink] subclass observes. */
    private class RecordingSink : AbstractSuspendingSink() {
        val written = Buffer()
        var finishCount = 0; private set
        var releaseCount = 0; private set
        var releasedCause: Throwable? = null; private set

        override suspend fun write(from: Buffer) {
            checkWritable()
            from.transferTo(written)
        }

        override suspend fun flush() {
            checkWritable()
        }

        override suspend fun finish() {
            finishCount++
        }

        override fun release(cause: Throwable?) {
            releaseCount++
            releasedCause = cause
        }
    }

    /** A [RawSink] that records flushes and closes so the caller-owned-sink policy can be pinned. */
    private class TrackingRawSink : RawSink {
        val buffer = Buffer()
        var flushCount = 0; private set
        var closeCount = 0; private set

        override fun write(source: Buffer, byteCount: Long): Unit = buffer.write(source, byteCount)
        override fun flush() { flushCount++ }
        override fun close() { closeCount++ }
    }

    @Test
    fun cleanCloseFinishesThenReleasesWithoutACause() = runTest {
        val sink = RecordingSink()
        sink.write(Buffer().also { it.writeString("body") })
        sink.close()
        assertEquals(1, sink.finishCount)
        assertEquals(1, sink.releaseCount)
        assertNull(sink.releasedCause, "a null cause is the proof of a clean finish")
    }

    @Test
    fun cancelReleasesWithTheCauseAndNeverFinishes() = runTest {
        val sink = RecordingSink()
        val boom = IllegalStateException("upstream died")
        sink.cancel(boom)
        assertEquals(0, sink.finishCount, "an abandoned stream must not get its trailer")
        assertSame(boom, sink.releasedCause)
    }

    @Test
    fun terminalTransitionHappensOnceAndFirstOneWins() = runTest {
        val sink = RecordingSink()
        sink.cancel(IllegalStateException("first"))
        sink.close()
        sink.cancel(IllegalStateException("second"))
        assertEquals(1, sink.releaseCount)
        assertEquals(0, sink.finishCount)
        assertEquals("first", sink.releasedCause?.message)
    }

    @Test
    fun writeAndFlushAfterTerminalFailFast() = runTest {
        val sink = RecordingSink()
        sink.close()
        assertFailsWith<IllegalStateException> { sink.write(Buffer().also { it.writeString("late") }) }
        assertFailsWith<IllegalStateException> { sink.flush() }
    }

    @Test
    fun finishThrowingStillReleasesWithTheFailure() = runTest {
        val boom = IllegalStateException("trailer failed")
        val sink = object : AbstractSuspendingSink() {
            var released: Throwable? = null
            override suspend fun write(from: Buffer) = checkWritable()
            override suspend fun flush() = checkWritable()
            override suspend fun finish(): Unit = throw boom
            override fun release(cause: Throwable?) { released = cause }
        }
        assertSame(boom, assertFailsWith<IllegalStateException> { sink.close() })
        assertSame(boom, sink.released, "a failed finish is an abandoned stream, not a clean one")
    }

    @Test
    fun useClosesOnSuccessAndCancelsOnFailure() = runTest {
        val clean = RecordingSink()
        clean.use { it.write(Buffer().also { b -> b.writeString("ok") }) }
        assertEquals(1, clean.finishCount)
        assertNull(clean.releasedCause)

        val failed = RecordingSink()
        val boom = IllegalStateException("producer died")
        assertFailsWith<IllegalStateException> { failed.use { throw boom } }
        assertEquals(0, failed.finishCount)
        assertSame(boom, failed.releasedCause)
    }

    // ---- the caller-owned RawSink policy ----

    @Test
    fun cleanCloseFlushesACallerOwnedSinkButDoesNotCloseIt() = runTest {
        val raw = TrackingRawSink()
        raw.asSuspendingSink(closeUnderlying = false).use { it.write(Buffer().also { b -> b.writeString("hi") }) }
        assertTrue(raw.flushCount > 0, "a clean close must push the tail")
        assertEquals(0, raw.closeCount, "a caller-owned sink's lifecycle is not ours to end")
    }

    @Test
    fun abandonDoesNotCommitPartialBytesToACallerOwnedSink() = runTest {
        val raw = TrackingRawSink()
        val sink = raw.asSuspendingSink(closeUnderlying = false)
        sink.write(Buffer().also { it.writeString("half") })
        sink.cancel(IllegalStateException("client gone"))
        assertEquals(0, raw.flushCount, "an abandoned body must not be flushed as though it were complete")
        assertEquals(0, raw.closeCount)
    }

    @Test
    fun ownedSinkIsFlushedThenClosedOnCleanClose() = runTest {
        val raw = TrackingRawSink()
        raw.asSuspendingSink(closeUnderlying = true).use { it.write(Buffer().also { b -> b.writeString("hi") }) }
        assertTrue(raw.flushCount > 0)
        assertEquals(1, raw.closeCount)
    }
}
