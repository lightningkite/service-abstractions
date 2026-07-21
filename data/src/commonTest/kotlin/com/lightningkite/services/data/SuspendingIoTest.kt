package com.lightningkite.services.data

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuspendingIoTest {

    private fun bufferOf(text: String) = Buffer().also { it.writeString(text) }

    @Test
    fun readEnsuresCountAndReportsAvailable() = runTest {
        val src = bufferOf("hello world").asSuspendingSource() // 11 bytes
        val into = Buffer()
        // Ask for 5 — buffer-backed source moves everything available (read-ahead), so we get all 11.
        assertTrue(src.read(into, 5))        // satisfied (>= 5)
        assertEquals(11L, into.size)
    }

    @Test
    fun alreadySatisfiedReadIsNoOp() = runTest {
        val src = bufferOf("more data here").asSuspendingSource()
        val into = Buffer()
        src.read(into, 4)            // pull some in
        val sizeAfterFirst = into.size
        assertTrue(src.read(into, sizeAfterFirst - 1)) // ask for less than we already hold -> no-op, satisfied
        assertEquals(sizeAfterFirst, into.size)        // nothing changed
    }

    @Test
    fun shortReadSignalsEndOfStream() = runTest {
        val src = bufferOf("abc").asSuspendingSource() // 3 bytes
        val into = Buffer()
        assertFalse(src.read(into, 8))       // asked for more than exists -> EOF first
        assertEquals(3L, into.size)          // the bytes read before EOF remain
        assertEquals(StreamState.Complete, src.state)
    }

    @Test
    fun readRemainingCollectsEverything() = runTest {
        val src = bufferOf("the quick brown fox").asSuspendingSource()
        assertEquals("the quick brown fox", src.readRemaining().readString())
    }

    @Test
    fun bufferSinkWriteAllAndClose() = runTest {
        val sink = BufferSuspendingSink()
        sink.writeAll(bufferOf("payload"))
        assertEquals(StreamState.Open, sink.state)
        sink.close()
        assertEquals(StreamState.Complete, sink.state)
        assertEquals("payload", sink.buffer.readString())
    }

    @Test
    fun dataSuspendingRoundTrips() = runTest {
        val data: Data = Data.Suspending(bufferOf("streamed").asSuspendingSource())
        assertEquals("streamed", data.text())
    }

    @Test
    fun dataSuspendingProducerRoundTrips() = runTest {
        val data: Data = Data.SuspendingProducer {
            it.writeAll(bufferOf("produced "))
            it.writeAll(bufferOf("in chunks"))
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
    fun cancelMarksAbnormal() = runTest {
        val src = bufferOf("x").asSuspendingSource()
        val cause = IllegalStateException("boom")
        src.cancel(cause)
        assertEquals(StreamState.ClosedAbnormally(cause), src.state)
    }

    @Test
    fun largePayloadThroughProducerToSuspendingData() = runTest {
        val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
        val total = 40
        val data: Data = Data.SuspendingProducer {
            repeat(total) { _ -> it.writeAll(Buffer().also { b -> b.write(chunk) }) }
        }
        val bytes = data.bytes()
        assertEquals(chunk.size.toLong() * total, bytes.size.toLong())
        assertTrue(bytes.all { it == 'a'.code.toByte() })
    }
}
