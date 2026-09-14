package com.lightningkite.services.ai.bedrock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Frame-level tests for the AWS event-stream parser.
 *
 * We build frames in-memory matching the AWS spec and then parse them, verifying both the
 * CRC checks and the payload/header decoding. Round-trip style keeps this test independent
 * of any canned binary blob and makes failures easy to read.
 */
class EventStreamTest {

    /**
     * Build a single event-stream frame with the given headers (all STRING typed) and
     * payload. Prelude CRC and message CRC are computed here, so the frame is bit-identical
     * to what Bedrock would put on the wire.
     *
     * Exported to tests only — the signer/stream writer doesn't need to produce frames.
     */
    private fun buildFrame(headers: Map<String, String>, payload: ByteArray): ByteArray {
        val headersBytes = buildHeadersBytes(headers)
        val totalLength = 12 + headersBytes.size + payload.size + 4
        val buf = ByteArray(totalLength)

        writeUInt32BE(buf, 0, totalLength)
        writeUInt32BE(buf, 4, headersBytes.size)
        val preludeCrc = crc32(buf, 0, 8).toInt()
        writeUInt32BE(buf, 8, preludeCrc)

        headersBytes.copyInto(buf, 12)
        payload.copyInto(buf, 12 + headersBytes.size)

        val msgCrc = crc32(buf, 0, totalLength - 4).toInt()
        writeUInt32BE(buf, totalLength - 4, msgCrc)
        return buf
    }

    private fun buildHeadersBytes(headers: Map<String, String>): ByteArray {
        val out = mutableListOf<Byte>()
        for ((name, value) in headers) {
            val nameBytes = name.encodeToByteArray()
            require(nameBytes.size < 256) { "header name too long" }
            out.add(nameBytes.size.toByte())
            for (b in nameBytes) out.add(b)
            out.add(7)  // STRING
            val valueBytes = value.encodeToByteArray()
            require(valueBytes.size < 0x10000) { "header value too long" }
            out.add(((valueBytes.size shr 8) and 0xff).toByte())
            out.add((valueBytes.size and 0xff).toByte())
            for (b in valueBytes) out.add(b)
        }
        return out.toByteArray()
    }

    private fun writeUInt32BE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xff).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xff).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xff).toByte()
        buf[offset + 3] = (value and 0xff).toByte()
    }

    @Test fun singleFrameRoundTrip() {
        val headers = mapOf(
            ":event-type" to "contentBlockDelta",
            ":message-type" to "event",
            ":content-type" to "application/json",
        )
        val payload = """{"contentBlockIndex":0,"delta":{"text":"hi"}}""".encodeToByteArray()
        val frame = buildFrame(headers, payload)

        val parser = EventStreamParser()
        parser.feed(frame)
        val messages = parser.drain()
        assertEquals(1, messages.size)
        assertEquals(headers, messages[0].headers)
        assertEquals(payload.decodeToString(), messages[0].payload.decodeToString())
    }

    /**
     * A split across the 12-byte prelude boundary is the trickiest edge case: the parser must
     * buffer partial bytes and resume cleanly. We drive it by feeding one byte at a time.
     */
    @Test fun bytewiseFeedingHandlesSplitFrames() {
        val headers = mapOf(":event-type" to "messageStart", ":message-type" to "event")
        val payload = """{"role":"assistant"}""".encodeToByteArray()
        val frame = buildFrame(headers, payload)

        val parser = EventStreamParser()
        val collected = mutableListOf<EventStreamMessage>()
        for (b in frame) {
            parser.feed(byteArrayOf(b))
            collected.addAll(parser.drain())
        }
        assertEquals(1, collected.size)
        assertEquals(headers, collected[0].headers)
    }

    /**
     * Two frames back-to-back should each decode and come out in order. Models the typical
     * `contentBlockDelta` burst pattern Bedrock produces during streaming text.
     */
    @Test fun twoFramesBackToBack() {
        val f1 = buildFrame(
            headers = mapOf(":event-type" to "contentBlockDelta", ":message-type" to "event"),
            payload = """{"contentBlockIndex":0,"delta":{"text":"a"}}""".encodeToByteArray(),
        )
        val f2 = buildFrame(
            headers = mapOf(":event-type" to "contentBlockDelta", ":message-type" to "event"),
            payload = """{"contentBlockIndex":0,"delta":{"text":"b"}}""".encodeToByteArray(),
        )
        val combined = f1 + f2
        val parser = EventStreamParser()
        parser.feed(combined)
        val messages = parser.drain()
        assertEquals(2, messages.size)
        assertTrue(messages[0].payload.decodeToString().contains("\"text\":\"a\""))
        assertTrue(messages[1].payload.decodeToString().contains("\"text\":\"b\""))
    }

    /**
     * Canonical CRC32 check vector: the string "123456789" should produce 0xcbf43926 under the
     * IEEE 802.3 reflected polynomial. Round-trip tests always agree with themselves, so this
     * independent vector guards against a regression in [crc32] that would break real Bedrock
     * traffic but pass every round-trip.
     */
    @Test fun crc32CanonicalVector() {
        assertEquals(0xcbf43926u, crc32("123456789".encodeToByteArray()))
    }

    @Test fun corruptPreludeCrcFails() {
        val frame = buildFrame(
            headers = mapOf(":event-type" to "event"),
            payload = "{}".encodeToByteArray(),
        )
        // Flip a single byte in the prelude CRC region (offset 8-11) — parser should throw.
        frame[9] = (frame[9].toInt() xor 0x01).toByte()

        val parser = EventStreamParser()
        parser.feed(frame)
        var threw = false
        try {
            parser.drain()
        } catch (e: IllegalStateException) {
            threw = true
            assertTrue("CRC" in (e.message ?: ""))
        }
        assertTrue(threw, "corrupted prelude CRC should cause a parser failure")
    }
}
