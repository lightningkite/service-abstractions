package com.lightningkite.services.data

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuspendingIoTest {

    private fun bufferOf(text: String) = Buffer().also { it.writeString(text) }

    @Test
    fun readHandsOverWhateverIsAvailable() = runTest {
        val src = bufferOf("hello world").asSuspendingSource() // 11 bytes
        val into = Buffer()
        assertEquals(11L, src.read(into))
        assertEquals(11L, into.size)
        assertEquals(-1L, src.read(into), "a drained source reports exhaustion, not a zero-length read")
    }

    @Test
    fun requestSatisfiesFramingAndKeepsTheSurplus() = runTest {
        val src = bufferOf("hello world").asSuspendingSource()
        val into = Buffer()
        // The buffer-backed source hands over everything at once; request() is satisfied and the extra stays put
        // for the next frame, which is the whole point of a caller-owned buffer.
        assertTrue(src.request(into, 5))
        assertEquals(11L, into.size)
    }

    @Test
    fun requestIsANoOpWhenAlreadySatisfied() = runTest {
        val src = bufferOf("more data here").asSuspendingSource()
        val into = Buffer()
        src.request(into, 4)
        val sizeAfterFirst = into.size
        assertTrue(src.request(into, sizeAfterFirst - 1)) // asking for less than we hold must not read again
        assertEquals(sizeAfterFirst, into.size)
    }

    @Test
    fun requestSignalsEndOfStreamAndKeepsWhatItGot() = runTest {
        val src = bufferOf("abc").asSuspendingSource() // 3 bytes
        val into = Buffer()
        assertFalse(src.request(into, 8))
        assertEquals(3L, into.size, "the bytes read before the end must remain in the caller's buffer")
    }

    @Test
    fun readRemainingCollectsEverything() = runTest {
        val src = bufferOf("the quick brown fox").asSuspendingSource()
        assertEquals("the quick brown fox", src.readRemaining().readString())
    }

    @Test
    fun readAfterCancelThrowsRatherThanLookingLikeAnEnd() = runTest {
        val src = bufferOf("x").asSuspendingSource()
        src.cancel(IllegalStateException("boom"))
        assertFailsWith<IllegalStateException> { src.read(Buffer()) }
    }

    @Test
    fun useReturnsTheBlockResultAndReleasesAnExhaustedSourceCleanly() = runTest {
        val src = bufferOf("payload").asSuspendingSource()
        assertEquals("payload", src.use { it.readRemaining() }.readString())
        // Reading to the end is a clean end; use()'s cancel afterward must not retroactively make it an abort.
        assertEquals(-1L, src.read(Buffer()))
    }

    @Test
    fun useAbandoningEarlyLeavesTheSourceUnreadable() = runTest {
        val src = bufferOf("payload").asSuspendingSource()
        src.use { /* walk away without reading */ }
        assertFailsWith<IllegalStateException> { src.read(Buffer()) }
    }

    @Test
    fun bufferSinkCollectsThenRefusesMoreOnceClosed() = runTest {
        val sink = BufferSuspendingSink()
        sink.write(bufferOf("payload"))
        sink.close()
        assertEquals("payload", sink.buffer.readString())
        assertFailsWith<IllegalStateException> { sink.write(bufferOf("late")) }
    }

    @Test
    fun dataSuspendingRoundTrips() = runTest {
        val data: Data = Data.Suspending(bufferOf("streamed").asSuspendingSource())
        assertEquals("streamed", data.text())
    }

    @Test
    fun dataSuspendingProducerRoundTrips() = runTest {
        val data: Data = Data.SuspendingProducer {
            it.write(bufferOf("produced "))
            it.write(bufferOf("in chunks"))
        }
        assertEquals("produced in chunks", data.text())
    }

    @Test
    fun dataWriteToStreamsIntoSink() = runTest {
        val data: Data = Data.Bytes("copy this".encodeToByteArray())
        val sink = BufferSuspendingSink()
        data.writeTo(sink)
        assertEquals("copy this", sink.buffer.readString())
    }

    @Test
    fun largePayloadThroughProducerToSuspendingData() = runTest {
        val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
        val total = 40
        val data: Data = Data.SuspendingProducer {
            repeat(total) { _ -> it.write(Buffer().also { b -> b.write(chunk) }) }
        }
        val bytes = data.bytes()
        assertEquals(chunk.size.toLong() * total, bytes.size.toLong())
        assertTrue(bytes.all { it == 'a'.code.toByte() })
    }
}
