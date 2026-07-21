package com.lightningkite.services.data

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lifecycle guarantees for [AbstractSuspendingSource]: resources are released on **every** terminal
 * transition (clean EOF, error, or cancel — not only explicit cancel), errors resurface on later reads,
 * and a misbehaving [AbstractSuspendingSource.fill] cannot spin forever.
 */
class SuspendingSourceLifecycleTest {

    /** A [RawSource] over [bytes] that records how many bytes were pulled and whether it was closed. */
    private class TrackingRawSource(private val bytes: ByteArray) : RawSource {
        var bytesRead = 0L; private set
        var closeCount = 0; private set
        val closed get() = closeCount > 0
        private var pos = 0

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (pos >= bytes.size) return -1L
            val n = minOf(byteCount, (bytes.size - pos).toLong()).toInt()
            sink.write(bytes, pos, pos + n)
            pos += n
            bytesRead += n
            return n.toLong()
        }

        override fun close() { closeCount++ }
    }

    // ---- A2: release on every terminal transition ----

    @Test
    fun cleanEofReleasesUnderlying() = runTest {
        val raw = TrackingRawSource("payload".encodeToByteArray())
        val src = raw.asSuspendingSource()
        assertEquals("payload", src.readRemaining().readString())
        assertEquals(StreamState.Complete, src.state)
        assertTrue(raw.closed, "reaching clean EOF must release the underlying source (fd leak otherwise)")
    }

    @Test
    fun explicitCancelReleasesUnderlyingOnce() = runTest {
        val raw = TrackingRawSource("payload".encodeToByteArray())
        val src = raw.asSuspendingSource()
        src.cancel()
        assertTrue(raw.closed)
        assertEquals(1, raw.closeCount)
        src.cancel(IllegalStateException("again")) // idempotent: no second release, state unchanged
        assertEquals(1, raw.closeCount)
        assertEquals(StreamState.Complete, src.state)
    }

    @Test
    fun fillThrowReleasesUnderlyingAndMarksAbnormal() = runTest {
        val boom = IllegalStateException("disk exploded")
        var released: Throwable? = null
        val src = object : AbstractSuspendingSource() {
            override suspend fun fill(into: Buffer, count: Long): Boolean = throw boom
            override fun release(cause: Throwable?) { released = cause }
        }
        val thrown = assertFailsWith<IllegalStateException> { src.read(Buffer(), 1) }
        assertEquals(boom, thrown)
        assertEquals(StreamState.ClosedAbnormally(boom), src.state)
        assertEquals(boom, released, "a thrown fill must release resources with the cause")
    }

    // ---- A6: a source that ended abnormally reports the error on every subsequent read ----

    @Test
    fun readAfterAbnormalCloseThrows() = runTest {
        val boom = IllegalStateException("connection reset")
        val src = object : AbstractSuspendingSource() {
            override suspend fun fill(into: Buffer, count: Long): Boolean = throw boom
            override fun release(cause: Throwable?) {}
        }
        assertFailsWith<IllegalStateException> { src.read(Buffer(), 1) } // first read surfaces + records it
        val again = assertFailsWith<IllegalStateException> { src.read(Buffer(), 1) } // A6: not a silent EOF
        assertEquals(boom, again)
    }

    // ---- A8: fill claiming progress without moving bytes must fail fast, not spin ----

    @Test
    fun fillWithoutProgressFailsFastAndReleases() = runTest {
        var released = false
        val src = object : AbstractSuspendingSource() {
            override suspend fun fill(into: Buffer, count: Long): Boolean = true // lies: never moves a byte
            override fun release(cause: Throwable?) { released = true }
        }
        assertFailsWith<IllegalStateException> { src.read(Buffer(), 10) }
        assertTrue(released, "the spin-guard must still release resources when it fails fast")
        assertTrue(src.state is StreamState.ClosedAbnormally)
    }

    // ---- MED-2: a downstream sink failure cancels the source so it can't leak ----

    @Test
    fun writeToCancelsSourceWhenSinkThrows() = runTest {
        val raw = TrackingRawSource("payload".encodeToByteArray())
        val data = Data.Suspending(raw.asSuspendingSource())
        val boom = IllegalStateException("client gone")
        val failingSink = object : SuspendingSink {
            override val state: StreamState = StreamState.Open
            override suspend fun write(from: Buffer, count: Long): Unit = throw boom
            override suspend fun flush() {}
            override suspend fun close() {}
            override fun close(cause: Throwable) {}
        }
        assertFailsWith<IllegalStateException> { data.writeTo(failingSink) }
        assertTrue(raw.closed, "a failing destination sink must not leave the source's resource open")
    }

    // ---- A5: read(count = 1) yields whatever is available without waiting for a fixed chunk ----

    @Test
    fun readOfOneReturnsImmediatelyWithAvailableBytes() = runTest {
        val raw = TrackingRawSource(ByteArray(3) { 'a'.code.toByte() })
        val src = raw.asSuspendingSource()
        val into = Buffer()
        assertTrue(src.read(into, 1)) // "give me whatever is there" — must not block for a larger chunk
        assertEquals(3L, into.size)   // grabbed what was available in one shot
    }

    @Test
    fun shortSourceReadStillSignalsEof() = runTest {
        val raw = TrackingRawSource("ab".encodeToByteArray())
        val src = raw.asSuspendingSource()
        val into = Buffer()
        assertFalse(src.read(into, 8))
        assertEquals(2L, into.size)
        assertEquals(StreamState.Complete, src.state)
    }
}
