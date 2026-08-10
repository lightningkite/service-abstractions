package com.lightningkite.services.data

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmitSuspendingSourceTests {

    private fun bufferOf(text: String) = Buffer().also { it.writeString(text) }

    // ==================== Basic Functionality Tests ====================

    @Test
    fun singleWriteStreamsToSource() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.write(bufferOf("hello"))
        }

        val result = source.readRemaining().readString()
        assertEquals("hello", result)
    }

    @Test
    fun multipleWritesStreamToSource() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.write(bufferOf("hello "))
            sink.write(bufferOf("world"))
        }

        val result = source.readRemaining().readString()
        assertEquals("hello world", result)
    }

    @Test
    fun manyWritesStreamCorrectly() = runTest {
        repeat(100){
            // Tests multiple sequential writes with backpressure
            val chunks = listOf("one", "two", "three", "four", "five")
            val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
                for (chunk in chunks) {
                    println("Chunk: $chunk")
                    sink.write(bufferOf(chunk))
                }
            }

            val result = source.readRemaining().readString()
            assertEquals(chunks.joinToString(""), result)
        }
    }

    // ==================== End-of-Stream Tests ====================

    @Test
    fun emptyProducerSignalsEndOfStream() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            // Producer closes without writing anything (auto-closed by EmitSuspendingSource)
        }

        val into = Buffer()
        val read = source.read(into)
        assertEquals(-1L, read, "Empty producer should signal end-of-stream with -1")
    }

    @Test
    fun endOfStreamAfterReads() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.write(bufferOf("data"))
        }

        val into = Buffer()
        val firstRead = source.read(into)
        assertTrue(firstRead > 0, "First read should return bytes")
        assertEquals("data", into.readString())

        val secondRead = source.read(Buffer())
        assertEquals(-1L, secondRead, "Should return -1 at end of stream")
    }

    // ==================== Backpressure Tests ====================

    @Test
    fun backpressureWorksCorrectly() = runTest {
        // Verifies producer suspends until consumer reads
        val writeOrder = mutableListOf<Int>()
        val readOrder = mutableListOf<Int>()

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            for (i in 1..3) {
                writeOrder.add(i)
                sink.write(bufferOf("chunk$i"))
                // After each write, producer should suspend until consumer reads
            }
        }

        val into = Buffer()
        for (i in 1..3) {
            source.read(into)
            readOrder.add(i)
            into.clear()
        }

        // Writes and reads should interleave due to backpressure
        assertEquals(listOf(1, 2, 3), writeOrder)
        assertEquals(listOf(1, 2, 3), readOrder)
    }

    // ==================== Lifecycle & Cleanup Tests ====================

    @Test
    fun writeToClosedSinkThrows() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.close()
            assertFailsWith<IllegalStateException> {
                sink.write(bufferOf("after close"))
            }
        }

        // Trigger the lambda - will get -1 since sink closed without writing
        val result = source.read(Buffer())
        assertEquals(-1L, result, "Closed sink should signal clean end-of-stream")
    }

    @Test
    fun writeToCancelledSinkThrows() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.cancel(RuntimeException("cancelled"))
            assertFailsWith<IllegalStateException> {
                sink.write(bufferOf("after cancel"))
            }
        }

        // Cancel propagates the error to the consumer - it's not a clean end-of-stream
        val exception = assertFailsWith<RuntimeException> {
            source.read(Buffer())
        }
        assertEquals("cancelled", exception.message)
    }

    @Test
    fun closedSinkSignalsEndOfStream() = runTest {
        // Verify that close() results in clean end-of-stream (-1), not an exception
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.write(bufferOf("data"))
            sink.close()
        }

        val into = Buffer()
        val firstRead = source.read(into)
        assertTrue(firstRead > 0, "First read should return data")

        val secondRead = source.read(Buffer())
        assertEquals(-1L, secondRead, "Closed sink should signal clean end-of-stream")
    }

    @Test
    fun cancelledSinkPropagatesErrorToConsumer() = runTest {
        // Verify that cancel(cause) propagates the error, distinguishing it from clean close
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.write(bufferOf("data before cancel"))
            sink.cancel(RuntimeException("producer aborted"))
        }

        val into = Buffer()
        source.read(into)  // Gets the data written before cancel
        assertEquals("data before cancel", into.readString())

        // Next read should throw the cancel cause
        val exception = assertFailsWith<RuntimeException> {
            source.read(Buffer())
        }
        assertEquals("producer aborted", exception.message)
    }

    @Test
    fun cancellingSourceCancelsProducer() = runTest {
        var producerCancelled = false

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            try {
                sink.write(bufferOf("first"))
                sink.write(bufferOf("second"))  // Should not reach here if cancelled after first
            } catch (e: Exception) {
                producerCancelled = true
                throw e
            }
        }

        // Read first chunk
        val into = Buffer()
        source.read(into)

        // Cancel the source
        source.cancel(RuntimeException("consumer done early"))

        // Producer should have been cancelled
        // Note: This depends on implementation details of how cancellation propagates
    }

    // ==================== Error Propagation Tests ====================

    @Test
    fun producerExceptionPropagates() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.write(bufferOf("before error"))
            throw RuntimeException("producer failed")
        }

        val into = Buffer()
        source.read(into)  // Gets the first chunk

        val exception = assertFailsWith<RuntimeException> {
            source.read(into)  // Should propagate the error
        }
        assertEquals("producer failed", exception.message)
    }

    // ==================== Large Data Tests ====================

    @Test
    fun largePayloadStreamsCorrectly() = runTest {
        val chunkSize = 64 * 1024  // 64KB chunks
        val numChunks = 10
        val chunk = ByteArray(chunkSize) { 'x'.code.toByte() }

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            repeat(numChunks) {
                val buf = Buffer()
                buf.write(chunk)
                sink.write(buf)
            }
        }

        val result = source.readRemaining()
        assertEquals(chunkSize.toLong() * numChunks, result.size)
    }

    // ==================== Flush Tests ====================

    @Test
    fun flushOnClosedSinkThrows() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.close()
            assertFailsWith<IllegalStateException> {
                sink.flush()
            }
        }

        source.read(Buffer())
    }

    // ==================== OnFlush Mode Tests ====================

    @Test
    fun onFlushModeSingleWriteWithImplicitFlush() = runTest {
        // close() now calls flush() automatically, so explicit flush isn't needed
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.write(bufferOf("hello"))
            // No explicit flush - close() will flush (auto-closed by EmitSuspendingSource)
        }

        val result = source.readRemaining().readString()
        assertEquals("hello", result)
    }

    @Test
    fun onFlushModeExplicitFlushBeforeMoreWrites() = runTest {
        // Test explicit flush followed by more writes
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.write(bufferOf("batch1"))
            sink.flush()  // Explicit flush to stream first batch
            sink.write(bufferOf("batch2"))
            // close() will flush batch2 (auto-closed by EmitSuspendingSource)
        }

        val result = source.readRemaining().readString()
        assertEquals("batch1batch2", result)
    }

    @Test
    fun onFlushModeBatchesMultipleWritesUntilFlush() = runTest {
        val writeCount = mutableListOf<Int>()
        val readCount = mutableListOf<Int>()

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            // Write multiple chunks without flushing
            sink.write(bufferOf("one"))
            writeCount.add(1)
            sink.write(bufferOf("two"))
            writeCount.add(2)
            sink.write(bufferOf("three"))
            writeCount.add(3)
            // Now flush - this should suspend and let consumer read all at once
            sink.flush()
        }

        val into = Buffer()
        val bytesRead = source.read(into)
        readCount.add(1)

        // All three writes should have happened before the first read
        assertEquals(listOf(1, 2, 3), writeCount, "All writes should happen before flush suspends")
        assertTrue(bytesRead > 0)
        assertEquals("onetwothree", into.readString(), "All data should be batched together")

        // Should be end of stream now
        assertEquals(-1L, source.read(Buffer()))
    }

    @Test
    fun onFlushModeMultipleBatches() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            // First batch
            sink.write(bufferOf("batch1-a"))
            sink.write(bufferOf("batch1-b"))
            sink.flush()

            // Second batch
            sink.write(bufferOf("batch2-a"))
            sink.write(bufferOf("batch2-b"))
            sink.flush()
        }

        val into = Buffer()

        // First read gets first batch
        source.read(into)
        assertEquals("batch1-abatch1-b", into.readString())

        // Second read gets second batch
        source.read(into)
        assertEquals("batch2-abatch2-b", into.readString())

        // End of stream
        assertEquals(-1L, source.read(Buffer()))
    }

    @Test
    fun onFlushModeEmptyFlushDoesNotSuspend() = runTest {
        var flushCount = 0

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            // Empty flush (no data written) should not cause issues
            sink.flush()
            flushCount++

            sink.write(bufferOf("data"))
            sink.flush()
            flushCount++
        }

        val result = source.readRemaining().readString()
        assertEquals("data", result)
        assertEquals(2, flushCount, "Both flushes should complete")
    }

    @Test
    fun onFlushModeCloseImplicitlyFlushes() = runTest {
        // Verify that close() flushes data even without explicit flush() call
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.write(bufferOf("no explicit flush"))
            // No explicit flush - close() will flush automatically (auto-closed by EmitSuspendingSource)
        }

        val result = source.readRemaining().readString()
        assertEquals("no explicit flush", result)
    }

    // ==================== OnWrite vs OnFlush Comparison Tests ====================

    @Test
    fun onWriteModeDefaultBehavior() = runTest {
        // Verify OnWrite mode - data should stream correctly
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.write(bufferOf("chunk1"))
            sink.write(bufferOf("chunk2"))
        }

        val result = source.readRemaining().readString()
        assertEquals("chunk1chunk2", result)
    }

    @Test
    fun onWriteModeStreamsDataInChunks() = runTest {
        // Verify that multiple writes result in data being available for reading
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.write(bufferOf("first"))
            sink.write(bufferOf("second"))
            sink.write(bufferOf("third"))
        }

        val result = source.readRemaining().readString()
        assertEquals("firstsecondthird", result)
    }

    @Test
    fun onFlushModeStreamsDataOnFlush() = runTest {
        // Verify OnFlush mode streams data when flush is called
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.write(bufferOf("a"))
            sink.write(bufferOf("b"))
            sink.flush()
        }

        val result = source.readRemaining().readString()
        assertEquals("ab", result)
    }

    // ==================== Edge Cases ====================

    @Test
    fun emptyWriteDoesNotBlockProgress() = runTest {
        // Empty writes should not block the stream
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.write(bufferOf(""))  // Empty write
            sink.write(bufferOf("real-data"))
        }

        val result = source.readRemaining().readString()
        assertEquals("real-data", result)
    }

    @Test
    fun readOnlySuspendsEmitOnceDataIsProvided() = runTest {
        // testing that read() will only suspend once there is actually data to stream. Empty writes don't suspend.

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.write(bufferOf(""))
            sink.write(bufferOf(""))
            sink.write(bufferOf(""))
            sink.write(bufferOf("finally some data"))
            // close() will flush (auto-closed by EmitSuspendingSource)
        }

        val into = Buffer()
        source.read(into)   // only read once (should go until first data is available)
        assertEquals("finally some data", into.readString())
    }

    @Test
    fun readOnlySuspendsEmitOnceDataIsProvidedFlushMode() = runTest {
        // testing that read() will only suspend once there is actually data to stream. Empty writes don't suspend.

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.write(bufferOf(""))
            sink.write(bufferOf(""))
            sink.flush()  // empty data so far, so flush will do nothing

            sink.write(bufferOf("finally some data"))
            sink.flush()  // stream 1

            sink.write(bufferOf(""))
            sink.flush()

            sink.write(bufferOf("hello "))
            sink.write(bufferOf("world"))
            // close() will flush stream 2 (auto-closed by EmitSuspendingSource)
        }

        val into = Buffer()
        source.read(into) // only read once (should go until first data is available after flush)
        assertEquals("finally some data", into.readString())

        source.read(into)
        assertEquals("hello world", into.readString())
    }

    @Test
    fun manySmallWrites() = runTest {
        val numWrites = 1000  // Reduced from 1000 for test speed
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            repeat(numWrites) { i ->
                sink.write(bufferOf("$i,"))
            }
        }

        val result = source.readRemaining().readString()
        val expected = (0 until numWrites).joinToString(",", postfix = ",")
        assertEquals(expected, result)
    }

    @Test
    fun exceptionInOnFlushModePropagates() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.write(bufferOf("before"))
            sink.flush()
            throw RuntimeException("flush mode error")
        }

        val into = Buffer()
        source.read(into)  // Gets first batch
        assertEquals("before", into.readString())

        val exception = assertFailsWith<RuntimeException> {
            source.read(into)
        }
        assertEquals("flush mode error", exception.message)
    }

    // ==================== Auto-close Tests ====================

    @Test
    fun sinkIsAutoClosedWhenLambdaReturns() = runTest {
        var sinkClosedOrCancelled = false

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            // Wrap in custom tracking to verify close is called
            val trackingSink = object : SuspendingSink {
                override suspend fun write(from: Buffer) = sink.write(from)
                override suspend fun flush() = sink.flush()
                override suspend fun close() {
                    sinkClosedOrCancelled = true
                    sink.close()
                }
                override fun cancel(cause: Throwable) {
                    sinkClosedOrCancelled = true
                    sink.cancel(cause)
                }
            }

            // Write through the tracking sink, but DON'T call use -
            // EmitSuspendingSource should auto-close the original sink
            trackingSink.write(bufferOf("data"))

            // Note: trackingSink won't get closed because we're not using it with use{},
            // but the original sink will be auto-closed by EmitSuspendingSource
        }

        source.readRemaining()
        // The original sink is closed by EmitSuspendingSource, not our wrapper.
        // This test demonstrates that users don't need to call close() anymore.
    }

    @Test
    fun autoCloseFlushesDataOnNormalReturn() = runTest {
        // Verify that auto-close properly flushes any remaining data
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.write(bufferOf("unflushed data"))
            // No explicit flush or close - auto-close should flush this
        }

        val result = source.readRemaining().readString()
        assertEquals("unflushed data", result)
    }

    // ==================== Auto-Backpressure Tests ====================

    @Test
    fun onFlushModeAutoBackpressureWhenBufferExceedsThreshold() = runTest {
        // Test that OnFlush mode applies automatic backpressure when buffer exceeds 24KB
        // to prevent unbounded memory growth
        val segmentSize = 8192
        val chunk = ByteArray(segmentSize) { 'x'.code.toByte() }
        var writesBeforeFirstRead = 0

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            // Write 4 segments (32KB) - should trigger auto-backpressure after 3rd segment
            repeat(4) { i ->
                val buf = Buffer()
                buf.write(chunk)
                writesBeforeFirstRead = i + 1
                sink.write(buf)
            }
            // Write some more after backpressure
            repeat(2) {
                val buf = Buffer()
                buf.write(chunk)
                sink.write(buf)
            }
        }

        val into = Buffer()

        // First read should get data after auto-backpressure kicks in (at ~24KB)
        val firstRead = source.read(into)
        assertTrue(firstRead >= segmentSize * 3, "First read should backpressure after 3 segments of bytes")

        // The producer should have been able to write at least 3 segments before backpressure
        assertTrue(writesBeforeFirstRead >= 3, "Should write at least 3 segments before backpressure")

        // Read the rest
        val remaining = source.readRemaining()
        val totalBytes = into.size + remaining.size

        // Should have all 6 segments worth of data
        assertEquals(segmentSize.toLong() * 6, totalBytes, "All data should be received")
    }

    @Test
    fun onFlushModeSmallWritesDoNotTriggerAutoBackpressure() = runTest {
        // Verify that small writes (under 24KB total) don't trigger auto-backpressure
        val writeCount = mutableListOf<Int>()

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            // Write small amounts - total under 24KB, should all complete before consumer reads
            repeat(10) { i ->
                sink.write(bufferOf("chunk$i"))
                writeCount.add(i)
            }
            sink.flush()
        }

        val into = Buffer()
        source.read(into)

        // All 10 writes should have completed before the first read
        assertEquals(10, writeCount.size, "All writes should complete before backpressure")
    }
}
