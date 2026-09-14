package com.lightningkite.services.data

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Lifecycle guarantees for [AbstractSuspendingSource]: resources are released on **every** terminal transition (clean
 * EOF, error, or cancel — not only explicit cancel), a source that failed or was abandoned never reports itself as a
 * clean end, and a misbehaving [AbstractSuspendingSource.fill] cannot spin forever.
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
        assertTrue(raw.closed, "reaching clean EOF must release the underlying source (fd leak otherwise)")
        assertEquals(-1L, src.read(Buffer()), "an exhausted source keeps reporting a clean end")
    }

    @Test
    fun explicitCancelReleasesUnderlyingOnce() = runTest {
        val raw = TrackingRawSource("payload".encodeToByteArray())
        val src = raw.asSuspendingSource()
        src.cancel()
        assertTrue(raw.closed)
        assertEquals(1, raw.closeCount)
        src.cancel(IllegalStateException("again")) // idempotent: no second release
        assertEquals(1, raw.closeCount)
    }

    @Test
    fun cancelAfterExhaustionDoesNotDowngradeTheCleanEnd() = runTest {
        // use() cancels on the success path, so an exhausted source must stay "exhausted" and keep answering -1
        // rather than flipping to the aborted state and throwing at a later reader.
        val raw = TrackingRawSource("payload".encodeToByteArray())
        val src = raw.asSuspendingSource()
        src.readRemaining()
        src.cancel()
        assertEquals(1, raw.closeCount)
        assertEquals(-1L, src.read(Buffer()))
    }

    @Test
    fun fillThrowReleasesUnderlyingWithTheCause() = runTest {
        val boom = IllegalStateException("disk exploded")
        var released: Throwable? = null
        val src = object : AbstractSuspendingSource() {
            override suspend fun fill(into: Buffer): Long = throw boom
            override fun release(cause: Throwable?) { released = cause }
        }
        val thrown = assertFailsWith<IllegalStateException> { src.read(Buffer()) }
        assertSame(boom, thrown)
        assertSame(boom, released, "a thrown fill must release resources with the cause")
    }

    // ---- A6: a source that failed or was abandoned never masquerades as a clean end ----

    @Test
    fun readAfterFailureThrowsInsteadOfReportingEndOfStream() = runTest {
        val boom = IllegalStateException("connection reset")
        val src = object : AbstractSuspendingSource() {
            override suspend fun fill(into: Buffer): Long = throw boom
            override fun release(cause: Throwable?) {}
        }
        assertSame(boom, assertFailsWith<IllegalStateException> { src.read(Buffer()) })
        // A silent -1 here would let a caller that swallowed the first failure treat a truncated body as complete.
        val again = assertFailsWith<IllegalStateException> { src.read(Buffer()) }
        assertFalse(again === boom, "the second read reports use-after-terminal, not a re-thrown original")
    }

    // ---- A8: fill claiming progress without moving bytes must fail fast, not spin ----

    @Test
    fun fillWithoutProgressFailsFastAndReleases() = runTest {
        var released = false
        val src = object : AbstractSuspendingSource() {
            override suspend fun fill(into: Buffer): Long = 0L // lies: neither progress nor end-of-stream
            override fun release(cause: Throwable?) { released = true }
        }
        assertFailsWith<IllegalStateException> { src.read(Buffer()) }
        assertTrue(released, "the spin-guard must still release resources when it fails fast")
    }

    // ---- MED-2: a downstream sink failure cancels the source so it can't leak ----

    @Test
    fun writeToCancelsSourceWhenSinkThrows() = runTest {
        val raw = TrackingRawSource("payload".encodeToByteArray())
        val data = Data.SuspendingSource(raw.asSuspendingSource())
        val boom = IllegalStateException("client gone")
        val failingSink = object : SuspendingSink {
            override suspend fun write(from: Buffer): Unit = throw boom
            override suspend fun flush() {}
            override suspend fun close() {}
            override fun cancel(cause: Throwable) {}
        }
        assertFailsWith<IllegalStateException> { data.writeSuspending(failingSink) }
        assertTrue(raw.closed, "a failing destination sink must not leave the source's resource open")
    }

    // ---- A5: a read yields whatever is available without waiting for a fixed chunk ----

    @Test
    fun readReturnsImmediatelyWithAvailableBytes() = runTest {
        val raw = TrackingRawSource(ByteArray(3) { 'a'.code.toByte() })
        val src = raw.asSuspendingSource()
        val into = Buffer()
        assertEquals(3L, src.read(into)) // must not stall for a larger chunk
        assertEquals(3L, into.size)
    }

    @Test
    fun useForwardsTheFailureAsTheCancelCause() = runTest {
        var released: Throwable? = null
        val src = object : AbstractSuspendingSource() {
            override suspend fun fill(into: Buffer): Long = -1L
            override fun release(cause: Throwable?) { released = cause }
        }
        val boom = IllegalStateException("consumer gave up")
        assertFailsWith<IllegalStateException> { src.use { throw boom } }
        assertSame(boom, released, "the producer must learn the read was abandoned, not finished")
    }

    @Test
    fun useOnTheSuccessPathCancelsWithoutACause() = runTest {
        var released: Throwable? = null
        var releaseCount = 0
        val src = object : AbstractSuspendingSource() {
            override suspend fun fill(into: Buffer): Long = -1L
            override fun release(cause: Throwable?) { releaseCount++; released = cause }
        }
        src.use { it.readRemaining() }
        assertEquals(1, releaseCount)
        assertNull(released)
    }
}
