package com.lightningkite.services.ai.bedrock

import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmStreamEvent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Offline guard against the class of bug that produced a butchered Nova response: a stream that
 * ends before its terminal `metadata` frame must FAIL, not come back as a clean EndTurn/zero-usage
 * result. Drives [collectBedrockStream] against canned (and deliberately truncated) event-stream
 * bytes with no network, so it runs everywhere in CI.
 */
class BedrockStreamTruncationTest {

    // A minimal but well-formed ConverseStream sequence: role → one text delta → stops → usage.
    private fun messageStart() = frame(
        mapOf(":message-type" to "event", ":event-type" to "messageStart"),
        """{"role":"assistant"}""",
    )

    private fun textDelta(text: String) = frame(
        mapOf(":message-type" to "event", ":event-type" to "contentBlockDelta"),
        """{"contentBlockIndex":0,"delta":{"text":"$text"}}""",
    )

    private fun contentBlockStop() = frame(
        mapOf(":message-type" to "event", ":event-type" to "contentBlockStop"),
        """{"contentBlockIndex":0}""",
    )

    private fun messageStop() = frame(
        mapOf(":message-type" to "event", ":event-type" to "messageStop"),
        """{"stopReason":"end_turn"}""",
    )

    private fun metadata(inTok: Int, outTok: Int) = frame(
        mapOf(":message-type" to "event", ":event-type" to "metadata"),
        """{"usage":{"inputTokens":$inTok,"outputTokens":$outTok}}""",
    )

    @Test
    fun completeStreamYieldsTextAndRealUsage() = runTest {
        val bytes = messageStart() + textDelta("Hello") + contentBlockStop() +
            messageStop() + metadata(inTok = 12, outTok = 3)
        val events = mutableListOf<LlmStreamEvent>()
        collectBedrockStream(ByteReadChannel(bytes), model = null) { events.add(it) }

        val text = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello", text)
        val finished = events.filterIsInstance<LlmStreamEvent.Finished>().single()
        assertEquals(12, finished.usage.inputTokens)
        assertEquals(3, finished.usage.outputTokens, "Real usage must come through, not zeros")
    }

    @Test
    fun streamMissingTerminalMetadataThrows() = runTest {
        // Everything except the terminal metadata frame — the exact shape of a cut connection.
        val bytes = messageStart() + textDelta("I was about to call a tool") + contentBlockStop() + messageStop()
        val ex = assertFailsWith<LlmException.Transport> {
            collectBedrockStream(ByteReadChannel(bytes), model = null) { /* drain */ }
        }
        assertTrue("truncated" in (ex.message ?: ""), "Error must name the truncation; got: ${ex.message}")
    }

    @Test
    fun streamCutMidFrameThrows() = runTest {
        // Cut in the middle of the text-delta frame: the parser holds a partial frame, the channel
        // closes, and no terminal frame is ever seen.
        val full = messageStart() + textDelta("partial...") + messageStop() + metadata(1, 1)
        val truncated = full.copyOfRange(0, full.size - 25)
        assertFailsWith<LlmException.Transport> {
            collectBedrockStream(ByteReadChannel(truncated), model = null) { /* drain */ }
        }
    }

    /**
     * Build one AWS event-stream frame (all-STRING headers) with correct prelude and message
     * CRC32s, so [EventStreamParser] accepts it exactly as it would a real Bedrock frame.
     */
    private fun frame(headers: Map<String, String>, payload: String): ByteArray {
        val payloadBytes = payload.encodeToByteArray()
        val headerBytes = buildList {
            for ((name, value) in headers) {
                val n = name.encodeToByteArray()
                add(n.size.toByte()); addAll(n.toList())
                add(7.toByte()) // STRING
                val v = value.encodeToByteArray()
                add(((v.size shr 8) and 0xff).toByte()); add((v.size and 0xff).toByte())
                addAll(v.toList())
            }
        }.toByteArray()

        val total = 12 + headerBytes.size + payloadBytes.size + 4
        val buf = ByteArray(total)
        writeUInt32BE(buf, 0, total)
        writeUInt32BE(buf, 4, headerBytes.size)
        writeUInt32BE(buf, 8, crc32(buf, 0, 8).toInt())
        headerBytes.copyInto(buf, 12)
        payloadBytes.copyInto(buf, 12 + headerBytes.size)
        writeUInt32BE(buf, total - 4, crc32(buf, 0, total - 4).toInt())
        return buf
    }

    private fun writeUInt32BE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xff).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xff).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xff).toByte()
        buf[offset + 3] = (value and 0xff).toByte()
    }
}
