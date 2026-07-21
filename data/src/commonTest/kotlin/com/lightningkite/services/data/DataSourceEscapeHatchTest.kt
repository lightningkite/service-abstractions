package com.lightningkite.services.data

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Data.Source.source] is the raw-streaming escape hatch: it must hand back the *live* underlying stream so callers
 * that stream it themselves (e.g. `RequestBody.fromInputStream` for an S3 upload, a virus scanner) never pull the whole
 * payload into heap. A regression that materialized the stream here turned every sized S3 upload into a full in-memory
 * copy.
 */
class DataSourceEscapeHatchTest {

    /** Records how many bytes have actually been pulled from the underlying stream. */
    private class CountingRawSource(private val bytes: ByteArray) : RawSource {
        var bytesRead = 0L; private set
        private var pos = 0
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (pos >= bytes.size) return -1L
            val n = minOf(byteCount, (bytes.size - pos).toLong()).toInt()
            sink.write(bytes, pos, pos + n)
            pos += n
            bytesRead += n
            return n.toLong()
        }
        override fun close() {}
    }

    @Test
    fun sourceDoesNotMaterialize() = runTest {
        val counting = CountingRawSource(ByteArray(64 * 1024) { 'z'.code.toByte() })
        val data = Data.Source(counting.buffered(), size = 64L * 1024)

        val stream = data.source() // must NOT read the whole thing up front

        assertTrue(
            counting.bytesRead < 64L * 1024,
            "source() materialized the whole stream (read ${counting.bytesRead} of 65536 bytes) — this is the S3 OOM regression",
        )

        // And the returned stream is still fully readable.
        assertEquals(64 * 1024, stream.buffered().readString().length)
    }

    @Test
    fun sourceStillRoundTripsContent() = runTest {
        val payload = "the-actual-bytes"
        val data = Data.Source(Buffer().also { it.writeString(payload) })
        assertEquals(payload, data.source().buffered().readString())
    }
}
