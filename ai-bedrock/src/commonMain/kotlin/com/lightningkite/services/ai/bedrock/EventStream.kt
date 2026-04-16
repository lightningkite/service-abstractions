package com.lightningkite.services.ai.bedrock

/**
 * Parser for AWS vnd.amazon.event-stream binary framing — the format Bedrock's
 * ConverseStream endpoint uses for its response body (NOT plain text SSE).
 *
 * A message is laid out as:
 *
 * ```
 *   offset  size  field
 *   0       4     totalLength        (big-endian uint32)
 *   4       4     headersLength      (big-endian uint32)
 *   8       4     preludeCrc32       (big-endian uint32, CRC32 of the 8 bytes above)
 *   12      H     headers
 *   12+H    P     payload            (totalLength - 16 - H bytes)
 *   total-4 4     messageCrc32       (CRC32 of bytes [0, total-4))
 * ```
 *
 * Each header is:
 *
 * ```
 *   1 byte  nameLength
 *   N bytes name (UTF-8)
 *   1 byte  type
 *   ...     type-dependent value
 * ```
 *
 * Only the header types Bedrock actually uses (7 = STRING, and the message metadata headers
 * `:message-type`, `:event-type`, `:content-type`) are required. We parse the full set for
 * robustness and ignore types we don't care about.
 *
 * The parser is written against a pull-style byte accumulator — you feed it bytes via [feed]
 * and it emits fully-decoded [EventStreamMessage]s via [drain]. CRC32 checks are performed
 * and throw on mismatch; disable with [validateCrc] in tests where we build synthetic frames
 * without checksums.
 */
internal class EventStreamParser(private val validateCrc: Boolean = true) {
    private val buffer: ArrayDeque<Byte> = ArrayDeque()

    /** Append newly-received bytes. */
    fun feed(bytes: ByteArray) {
        for (b in bytes) buffer.addLast(b)
    }

    /**
     * Extract as many complete messages as the current buffer allows. After this returns,
     * [buffer] holds at most a partial frame awaiting more bytes.
     */
    fun drain(): List<EventStreamMessage> {
        val out = mutableListOf<EventStreamMessage>()
        while (true) {
            val msg = tryParseOne() ?: break
            out.add(msg)
        }
        return out
    }

    private fun tryParseOne(): EventStreamMessage? {
        if (buffer.size < 16) return null  // Need at least the prelude + message CRC.
        val totalLength = peekUInt32BE(0)
        if (totalLength < 16 || totalLength > MAX_MESSAGE_LENGTH) {
            throw IllegalStateException("Invalid event-stream message length: $totalLength")
        }
        if (buffer.size < totalLength) return null

        // Pull exactly totalLength bytes into a flat array for easier random access.
        val msg = ByteArray(totalLength)
        for (i in 0 until totalLength) msg[i] = buffer.removeFirst()

        val headersLength = readUInt32BE(msg, 4)
        val preludeCrc = readUInt32BE(msg, 8)
        val messageCrc = readUInt32BE(msg, totalLength - 4)

        if (validateCrc) {
            val computedPreludeCrc = crc32(msg, 0, 8)
            if (computedPreludeCrc != preludeCrc.toUInt()) {
                throw IllegalStateException(
                    "Event-stream prelude CRC mismatch: got ${computedPreludeCrc.toLong()} expected $preludeCrc",
                )
            }
            val computedMsgCrc = crc32(msg, 0, totalLength - 4)
            if (computedMsgCrc != messageCrc.toUInt()) {
                throw IllegalStateException(
                    "Event-stream message CRC mismatch: got ${computedMsgCrc.toLong()} expected $messageCrc",
                )
            }
        }

        val headers = parseHeaders(msg, 12, headersLength)
        val payloadStart = 12 + headersLength
        val payloadLength = totalLength - payloadStart - 4
        val payload = msg.copyOfRange(payloadStart, payloadStart + payloadLength)
        return EventStreamMessage(headers, payload)
    }

    private fun parseHeaders(bytes: ByteArray, start: Int, length: Int): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var i = start
        val end = start + length
        while (i < end) {
            val nameLen = bytes[i].toInt() and 0xff
            i++
            val name = bytes.decodeToString(i, i + nameLen)
            i += nameLen
            val type = bytes[i].toInt() and 0xff
            i++
            val value: String = when (type) {
                0 -> "true"
                1 -> "false"
                2 -> { // byte
                    val v = bytes[i].toInt().toString()
                    i += 1; v
                }
                3 -> { // short
                    val v = readInt16BE(bytes, i).toString()
                    i += 2; v
                }
                4 -> { // int
                    val v = readUInt32BE(bytes, i).toString()
                    i += 4; v
                }
                5 -> { // long
                    val v = readInt64BE(bytes, i).toString()
                    i += 8; v
                }
                6, 7 -> { // byte-array or string — both have a uint16 length prefix.
                    val len = readUInt16BE(bytes, i)
                    i += 2
                    val s = bytes.decodeToString(i, i + len)
                    i += len
                    s
                }
                8 -> { // timestamp (ms since epoch)
                    val v = readInt64BE(bytes, i).toString()
                    i += 8; v
                }
                9 -> { // UUID (16 bytes) — render as hex for debug purposes only.
                    val v = bytes.copyOfRange(i, i + 16).toHex()
                    i += 16; v
                }
                else -> throw IllegalStateException("Unknown event-stream header type: $type")
            }
            headers[name] = value
        }
        return headers
    }

    private fun peekUInt32BE(offset: Int): Int {
        val b0 = buffer.elementAt(offset).toInt() and 0xff
        val b1 = buffer.elementAt(offset + 1).toInt() and 0xff
        val b2 = buffer.elementAt(offset + 2).toInt() and 0xff
        val b3 = buffer.elementAt(offset + 3).toInt() and 0xff
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    companion object {
        /** Hard cap guarding against corrupt/adversarial streams producing nonsense lengths. */
        private const val MAX_MESSAGE_LENGTH = 16 * 1024 * 1024  // 16 MiB
    }
}

/**
 * A single decoded event-stream frame. [headers] are the typed header map flattened to
 * strings (the types Bedrock uses are all strings); [payload] is the raw bytes AWS places
 * after the headers.
 *
 * For Bedrock ConverseStream events, [payload] is a JSON object like
 * `{"contentBlockDelta": {"contentBlockIndex": 0, "delta": {"text": "..."}}}` and
 * `headers[":event-type"]` holds the event discriminator ("messageStart",
 * "contentBlockDelta", etc.).
 */
internal data class EventStreamMessage(
    val headers: Map<String, String>,
    val payload: ByteArray,
)

private fun readUInt32BE(bytes: ByteArray, offset: Int): Int {
    val b0 = bytes[offset].toInt() and 0xff
    val b1 = bytes[offset + 1].toInt() and 0xff
    val b2 = bytes[offset + 2].toInt() and 0xff
    val b3 = bytes[offset + 3].toInt() and 0xff
    return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
}

private fun readUInt16BE(bytes: ByteArray, offset: Int): Int {
    val b0 = bytes[offset].toInt() and 0xff
    val b1 = bytes[offset + 1].toInt() and 0xff
    return (b0 shl 8) or b1
}

private fun readInt16BE(bytes: ByteArray, offset: Int): Short {
    val b0 = bytes[offset].toInt() and 0xff
    val b1 = bytes[offset + 1].toInt() and 0xff
    return ((b0 shl 8) or b1).toShort()
}

private fun readInt64BE(bytes: ByteArray, offset: Int): Long {
    var v = 0L
    for (i in 0 until 8) {
        v = (v shl 8) or (bytes[offset + i].toLong() and 0xff)
    }
    return v
}
