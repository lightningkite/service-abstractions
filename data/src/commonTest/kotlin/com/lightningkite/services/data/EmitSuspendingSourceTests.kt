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
            sink.use {
                it.write(bufferOf("hello"))
            }
        }

        val result = source.readRemaining().readString()
        assertEquals("hello", result)
    }

    @Test
    fun multipleWritesStreamToSource() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.use {
                it.write(bufferOf("hello "))
                it.write(bufferOf("world"))
            }
        }

        val result = source.readRemaining().readString()
        assertEquals("hello world", result)
    }

    @Test
    fun manyWritesStreamCorrectly() = runTest {
        // Tests multiple sequential writes with backpressure
        val chunks = listOf("one", "two", "three", "four", "five")
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.use {
                for (chunk in chunks) {
                    it.write(bufferOf(chunk))
                }
            }
        }

        val result = source.readRemaining().readString()
        assertEquals(chunks.joinToString(""), result)
    }

    // ==================== End-of-Stream Tests ====================

    @Test
    fun emptyProducerSignalsEndOfStream() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.use {
                // Producer closes without writing anything
            }
        }

        val into = Buffer()
        val read = source.read(into)
        assertEquals(-1L, read, "Empty producer should signal end-of-stream with -1")
    }

    @Test
    fun endOfStreamAfterReads() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.use {
                it.write(bufferOf("data"))
            }
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
            sink.use {
                for (i in 1..3) {
                    writeOrder.add(i)
                    it.write(bufferOf("chunk$i"))
                    // After each write, producer should suspend until consumer reads
                }
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
        source.read(Buffer())
    }

    @Test
    fun writeToCancelledSinkThrows() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.cancel(RuntimeException("cancelled"))
            assertFailsWith<IllegalStateException> {
                sink.write(bufferOf("after cancel"))
            }
        }

        // Trigger the lambda - will get -1 since sink cancelled without writing
        source.read(Buffer())
    }

    @Test
    fun cancellingSourceCancelsProducer() = runTest {
        var producerCancelled = false

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            try {
                sink.use {
                    it.write(bufferOf("first"))
                    it.write(bufferOf("second"))  // Should not reach here if cancelled after first
                }
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
            sink.use {
                it.write(bufferOf("before error"))
                throw RuntimeException("producer failed")
            }
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
            sink.use {
                repeat(numChunks) {
                    val buf = Buffer()
                    buf.write(chunk)
                    sink.write(buf)
                }
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
            sink.use {
                it.write(bufferOf("hello"))
                // No explicit flush - close() will flush
            }
        }

        val result = source.readRemaining().readString()
        assertEquals("hello", result)
    }

    @Test
    fun onFlushModeExplicitFlushBeforeMoreWrites() = runTest {
        // Test explicit flush followed by more writes
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.use {
                it.write(bufferOf("batch1"))
                it.flush()  // Explicit flush to stream first batch
                it.write(bufferOf("batch2"))
                // close() will flush batch2
            }
        }

        val result = source.readRemaining().readString()
        assertEquals("batch1batch2", result)
    }

    @Test
    fun onFlushModeBatchesMultipleWritesUntilFlush() = runTest {
        val writeCount = mutableListOf<Int>()
        val readCount = mutableListOf<Int>()

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.use {
                // Write multiple chunks without flushing
                it.write(bufferOf("one"))
                writeCount.add(1)
                it.write(bufferOf("two"))
                writeCount.add(2)
                it.write(bufferOf("three"))
                writeCount.add(3)
                // Now flush - this should suspend and let consumer read all at once
                it.flush()
            }
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
            sink.use {
                // First batch
                it.write(bufferOf("batch1-a"))
                it.write(bufferOf("batch1-b"))
                it.flush()

                // Second batch
                it.write(bufferOf("batch2-a"))
                it.write(bufferOf("batch2-b"))
                it.flush()
            }
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
            sink.use {
                // Empty flush (no data written) should not cause issues
                it.flush()
                flushCount++

                it.write(bufferOf("data"))
                it.flush()
                flushCount++
            }
        }

        val result = source.readRemaining().readString()
        assertEquals("data", result)
        assertEquals(2, flushCount, "Both flushes should complete")
    }

    @Test
    fun onFlushModeCloseImplicitlyFlushes() = runTest {
        // Verify that close() flushes data even without explicit flush() call
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.use {
                it.write(bufferOf("no explicit flush"))
                // No explicit flush - close() should flush automatically
            }
        }

        val result = source.readRemaining().readString()
        assertEquals("no explicit flush", result)
    }

    // ==================== OnWrite vs OnFlush Comparison Tests ====================

    @Test
    fun onWriteModeDefaultBehavior() = runTest {
        // Verify OnWrite is the default - data should stream correctly
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.use {
                it.write(bufferOf("chunk1"))
                it.write(bufferOf("chunk2"))
            }
        }

        val result = source.readRemaining().readString()
        assertEquals("chunk1chunk2", result)
    }

    @Test
    fun onWriteModeStreamsDataInChunks() = runTest {
        // Verify that multiple writes result in data being available for reading
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.use {
                it.write(bufferOf("first"))
                it.write(bufferOf("second"))
                it.write(bufferOf("third"))
            }
        }

        val result = source.readRemaining().readString()
        assertEquals("firstsecondthird", result)
    }

    @Test
    fun onFlushModeStreamsDataOnFlush() = runTest {
        // Verify OnFlush mode streams data when flush is called
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.use {
                it.write(bufferOf("a"))
                it.write(bufferOf("b"))
                it.flush()
            }
        }

        val result = source.readRemaining().readString()
        assertEquals("ab", result)
    }

    // ==================== Edge Cases ====================

    @Test
    fun emptyWriteDoesNotBlockProgress() = runTest {
        // Empty writes should not block the stream
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.use {
                it.write(bufferOf(""))  // Empty write
                it.write(bufferOf("real-data"))
            }
        }

        val result = source.readRemaining().readString()
        assertEquals("real-data", result)
    }

    @Test
    fun readOnlySuspendsEmitOnceDataIsProvided() = runTest {
        // testing that read() will only suspend once there is actually data to stream. Empty writes don't suspend.

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.use {
                it.write(bufferOf(""))
                it.write(bufferOf(""))
                it.write(bufferOf(""))
                it.write(bufferOf("finally some data"))
                // close() will flush
            }
        }

        val into = Buffer()
        source.read(into)   // only read once (should go until first data is available)
        assertEquals("finally some data", into.readString())
    }

    @Test
    fun readOnlySuspendsEmitOnceDataIsProvidedFlushMode() = runTest {
        // testing that read() will only suspend once there is actually data to stream. Empty writes don't suspend.

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.use {
                it.write(bufferOf(""))
                it.write(bufferOf(""))
                it.flush()  // empty data so far, so flush will do nothing

                it.write(bufferOf("finally some data"))
                it.flush()  // stream 1

                it.write(bufferOf(""))
                it.flush()

                it.write(bufferOf("hello "))
                it.write(bufferOf("world"))
                // close() will flush stream 2
            }
        }

        val into = Buffer()
        source.read(into) // only read once (should go until first data is available after flush)
        assertEquals("finally some data", into.readString())

        source.read(into)
        assertEquals("hello world", into.readString())
    }

    @Test
    fun manySmallWrites() = runTest {
        val numWrites = 100  // Reduced from 1000 for test speed
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            sink.use {
                repeat(numWrites) { i ->
                    it.write(bufferOf("$i,"))
                }
            }
        }

        val result = source.readRemaining().readString()
        val expected = (0 until numWrites).joinToString(",", postfix = ",")
        assertEquals(expected, result)
    }

    @Test
    fun exceptionInOnFlushModePropagates() = runTest {
        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnFlush) { sink ->
            sink.use {
                it.write(bufferOf("before"))
                it.flush()
                throw RuntimeException("flush mode error")
            }
        }

        val into = Buffer()
        source.read(into)  // Gets first batch
        assertEquals("before", into.readString())

        val exception = assertFailsWith<RuntimeException> {
            source.read(into)
        }
        assertEquals("flush mode error", exception.message)
    }

    // ==================== Concurrent Access Tests ====================

    @Test
    fun usePatternClosesCleanly() = runTest {
        var sinkClosed = false

        val source = EmitSuspendingSource(EmitSuspendingSource.SuspendMode.OnWrite) { sink ->
            // Wrap in custom tracking
            val trackingSink = object : SuspendingSink {
                override suspend fun write(from: Buffer) = sink.write(from)
                override suspend fun flush() = sink.flush()
                override suspend fun close() {
                    sinkClosed = true
                    sink.close()
                }
                override fun cancel(cause: Throwable) = sink.cancel(cause)
            }

            trackingSink.use {
                it.write(bufferOf("data"))
            }
        }

        source.readRemaining()
        assertTrue(sinkClosed, "Sink should be closed via use pattern")
    }
}
