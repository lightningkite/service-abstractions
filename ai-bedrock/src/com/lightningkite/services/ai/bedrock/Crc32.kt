@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package com.lightningkite.services.ai.bedrock

/**
 * Pure-Kotlin CRC32 (IEEE 802.3 polynomial 0xEDB88320, reflected) — the variant AWS uses
 * for event-stream prelude and message checksums. There is no KMP-friendly standard-library
 * implementation (java.util.zip.CRC32 is JVM-only and kotlinx.io CRC32 is also JVM-only), so
 * a small table-based one lives here.
 *
 * Not a hash of any secret — used for frame integrity only.
 */
internal fun crc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): UInt {
    var crc = 0xffffffffu
    for (i in offset until offset + length) {
        val b = (bytes[i].toInt() and 0xff).toUInt()
        crc = CRC32_TABLE[((crc xor b) and 0xffu).toInt()] xor (crc shr 8)
    }
    return crc xor 0xffffffffu
}

private val CRC32_TABLE: UIntArray = UIntArray(256).also { table ->
    for (i in 0 until 256) {
        var c = i.toUInt()
        repeat(8) {
            c = if ((c and 1u) != 0u) {
                (c shr 1) xor 0xedb88320u
            } else {
                c shr 1
            }
        }
        table[i] = c
    }
}
