@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.serializers

import kotlinx.io.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.*
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Gives a field an explicit wire id instead of one hashed from its serial name, so its key takes 1 byte (ids 1 to 15)
 * or 2 (up to 2047) instead of 4.  Adding, removing or changing it changes the field's identity on the wire, like a
 * rename, so choose it when the field is created.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class WireId(val id: Int)

/**
 * A compact binary format whose data survives schema changes in both directions: fields can be added (with defaults),
 * removed, reordered, and renamed with `@SerialName`, and both older and newer readers still read the data.
 *
 * Each class field is a key (its wire id and wire type) followed by its value; classes, lists, maps, strings, enums and
 * polymorphic values are length-prefixed, so a reader skips fields it doesn't know.  Field ids are hashed from serial
 * names unless the field has a [WireId].  Defaults are not written (`@EncodeDefault` opts a field in), and null is
 * written, so `T? = default` round-trips null.  Enums are written by name.  `Uuid` and `kotlin.time.Instant` with their
 * default serializers are written compactly (16 bytes; epoch seconds and nanoseconds as varints) instead of as strings.
 *
 * The bytes carry no header: the caller records which format wrote them (a token prefix, a media type, a storage
 * attribute).  Full specification: `plans/kotlin-bytes-format-v2.md`.
 */
public class KotlinWireFormat(override val serializersModule: SerializersModule) : BinaryFormat {
    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray {
        val output = Buffer()
        WireEncoder(output, fieldIds = null, parentOutput = null, isTopLevel = true).encodeSerializableValue(serializer, value)
        return output.readByteArray()
    }

    override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        val input = Buffer().apply { write(bytes) }
        val value = try {
            WireDecoder(input, structure = null, isTopLevel = true).decodeSerializableValue(deserializer)
        } catch (e: EOFException) {
            throw SerializationException("A value ends early", e)
        }
        if (!input.exhausted()) throw SerializationException("${input.size} bytes left over after the value")
        return value
    }

    // fieldIds is null in element position (top level, collection and map entries, polymorphic type and value),
    // where values have no key and nullable ones a 00/01 mark.  The top-level structure has no length: the input's end
    // is its end, so it writes straight into the output and parentOutput is null.
    private inner class WireEncoder(
        val output: Buffer,
        val fieldIds: IntArray?,
        val parentOutput: Buffer?,
        val isTopLevel: Boolean = false,
    ) : AbstractEncoder() {
        override val serializersModule: SerializersModule get() = this@KotlinWireFormat.serializersModule
        private var pendingFieldId = 0

        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            if (fieldIds != null) pendingFieldId = fieldIds[index]
            return true
        }

        override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean = false

        private fun writeKey(wireType: Int) {
            if (fieldIds == null) return
            check(pendingFieldId != 0) { "A value was written outside a field" }
            output.writeVarint((pendingFieldId.toLong() shl 3) or wireType.toLong())
            pendingFieldId = 0
        }

        override fun encodeNull() {
            if (fieldIds == null) output.writeByte(0) else writeKey(NULL)
        }

        override fun encodeNotNullMark() {
            if (fieldIds == null) output.writeByte(1)
        }

        override fun encodeBoolean(value: Boolean) {
            writeKey(VARINT)
            output.writeByte(if (value) 1 else 0)
        }

        override fun encodeByte(value: Byte) {
            if (fieldIds == null) {
                output.writeByte(value)
            } else {
                writeKey(VARINT)
                output.writeVarint(zigzag(value.toLong()))
            }
        }

        override fun encodeShort(value: Short) {
            writeKey(VARINT)
            output.writeVarint(zigzag(value.toLong()))
        }

        override fun encodeInt(value: Int) {
            writeKey(VARINT)
            output.writeVarint(zigzag(value.toLong()))
        }

        override fun encodeLong(value: Long) {
            writeKey(VARINT)
            output.writeVarint(zigzag(value))
        }

        override fun encodeChar(value: Char) {
            writeKey(VARINT)
            output.writeVarint(value.code.toLong())
        }

        override fun encodeFloat(value: Float) {
            writeKey(FIXED32)
            output.writeIntLe(value.toRawBits())
        }

        override fun encodeDouble(value: Double) {
            writeKey(FIXED64)
            output.writeLongLe(value.toRawBits())
        }

        override fun encodeString(value: String) {
            writeKey(LEN)
            val bytes = value.encodeToByteArray()
            output.writeVarint(bytes.size.toLong())
            output.write(bytes)
        }

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
            encodeString(enumDescriptor.getElementName(index))
        }

        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            when (serializer.descriptor.serialName) {
                UUID -> {
                    writeKey(LEN)
                    output.writeVarint(16)
                    output.write((value as Uuid).toByteArray())
                }
                INSTANT -> {
                    writeKey(LEN)
                    val instant = value as Instant
                    val payload = Buffer()
                    payload.writeVarint(zigzag(instant.epochSeconds))
                    payload.writeVarint(instant.nanosecondsOfSecond.toLong())
                    output.writeVarint(payload.size)
                    output.write(payload, payload.size)
                }
                else -> super.encodeSerializableValue(serializer, value)
            }
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            writeKey(LEN)
            return if (isTopLevel) WireEncoder(output, fieldIdsOf(descriptor), parentOutput = null)
            else WireEncoder(Buffer(), fieldIdsOf(descriptor), parentOutput = output)
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            if (parentOutput == null) return
            parentOutput.writeVarint(output.size)
            parentOutput.write(output, output.size)
        }
    }

    // structure is the class being read, or null in element position (see WireEncoder).
    private inner class WireDecoder(
        val input: Buffer,
        val structure: SerialDescriptor?,
        val isTopLevel: Boolean = false,
    ) : AbstractDecoder() {
        override val serializersModule: SerializersModule get() = this@KotlinWireFormat.serializersModule
        private val fieldIds = structure?.let { fieldIdsOf(it) }
        private val seen = BooleanArray(fieldIds?.size ?: 0)
        private var nextIndex = 0
        private var field = -1
        private var pendingWireType = -1

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if (fieldIds == null) {
                return when (descriptor.kind) {
                    StructureKind.LIST -> if (input.exhausted()) CompositeDecoder.DECODE_DONE else nextIndex++
                    // A key at the end of the payload still gets its index, so reading its missing value throws.
                    StructureKind.MAP -> if (input.exhausted() && nextIndex % 2 == 0) CompositeDecoder.DECODE_DONE else nextIndex++
                    else -> if (nextIndex == descriptor.elementsCount) CompositeDecoder.DECODE_DONE else nextIndex++
                }
            }
            while (!input.exhausted()) {
                val key = input.readVarint()
                val wireType = (key and 7).toInt()
                val id = key ushr 3
                val index = fieldIds.indexOfFirst { it.toLong() == id }
                if (index == -1) {
                    skip(wireType)
                    continue
                }
                if (seen[index]) throw SerializationException("${fieldName(index)} appears twice")
                seen[index] = true
                field = index
                pendingWireType = wireType
                return index
            }
            return CompositeDecoder.DECODE_DONE
        }

        private fun fieldName(index: Int): String = "${structure!!.serialName}.${structure.getElementName(index)}"

        private fun skip(wireType: Int) {
            when (wireType) {
                VARINT -> input.readVarint()
                FIXED64 -> input.skip(8)
                LEN -> input.skip(readLength())
                NULL -> {}
                FIXED32 -> input.skip(4)
                else -> throw SerializationException("Unknown wire type $wireType")
            }
        }

        private fun expect(wireType: Int) {
            if (fieldIds == null) return
            if (pendingWireType != wireType) {
                throw SerializationException("${fieldName(field)}: expected wire type $wireType, found $pendingWireType")
            }
            pendingWireType = -1
        }

        private fun readLength(): Long {
            val length = input.readVarint()
            if (length !in 0..input.size) throw SerializationException("Length $length exceeds the ${input.size} bytes left")
            return length
        }

        private fun readSigned(range: LongRange): Long {
            val value = unzigzag(input.readVarint())
            if (value !in range) throw SerializationException("$value is out of range $range")
            return value
        }

        override fun decodeNotNullMark(): Boolean {
            if (fieldIds != null) return pendingWireType != NULL
            return when (val mark = input.readByte().toInt()) {
                0 -> false
                1 -> true
                else -> throw SerializationException("Invalid null mark $mark")
            }
        }

        override fun decodeNull(): Nothing? {
            expect(NULL)
            return null
        }

        override fun decodeBoolean(): Boolean {
            expect(VARINT)
            return when (val value = input.readVarint()) {
                0L -> false
                1L -> true
                else -> throw SerializationException("Invalid boolean $value")
            }
        }

        override fun decodeByte(): Byte {
            if (fieldIds == null) return input.readByte()
            expect(VARINT)
            return readSigned(Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()).toByte()
        }

        override fun decodeShort(): Short {
            expect(VARINT)
            return readSigned(Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()).toShort()
        }

        override fun decodeInt(): Int {
            expect(VARINT)
            return readSigned(Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()).toInt()
        }

        override fun decodeLong(): Long {
            expect(VARINT)
            return unzigzag(input.readVarint())
        }

        override fun decodeChar(): Char {
            expect(VARINT)
            val code = input.readVarint()
            if (code !in 0L..0xFFFFL) throw SerializationException("Invalid char $code")
            return code.toInt().toChar()
        }

        override fun decodeFloat(): Float {
            expect(FIXED32)
            return Float.fromBits(input.readIntLe())
        }

        override fun decodeDouble(): Double {
            expect(FIXED64)
            return Double.fromBits(input.readLongLe())
        }

        override fun decodeString(): String {
            expect(LEN)
            return input.readString(readLength())
        }

        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
            val name = decodeString()
            val index = enumDescriptor.getElementIndex(name)
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                throw SerializationException("${enumDescriptor.serialName} has no entry '$name'")
            }
            return index
        }

        private fun readPayload(): Buffer {
            val payload = Buffer()
            payload.write(input, readLength())
            return payload
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
            return when (deserializer.descriptor.serialName) {
                UUID -> {
                    expect(LEN)
                    val length = readLength()
                    if (length != 16L) throw SerializationException("A Uuid is 16 bytes, not $length")
                    Uuid.fromByteArray(input.readByteArray(16)) as T
                }
                INSTANT -> {
                    expect(LEN)
                    val payload = readPayload()
                    val seconds = unzigzag(payload.readVarint())
                    val nanoseconds = payload.readVarint()
                    if (nanoseconds !in 0L until 1_000_000_000L) throw SerializationException("Invalid nanoseconds $nanoseconds")
                    if (!payload.exhausted()) throw SerializationException("Instant: ${payload.size} bytes left over")
                    val instant = Instant.fromEpochSeconds(seconds, nanoseconds)
                    // fromEpochSeconds clamps out-of-range values instead of failing.
                    if (instant.epochSeconds != seconds) throw SerializationException("Instant out of range: $seconds s")
                    instant as T
                }
                else -> super.decodeSerializableValue(deserializer)
            }
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
            expect(LEN)
            val payload = if (isTopLevel) input else readPayload()
            return WireDecoder(payload, descriptor.takeIf { it.kind == StructureKind.CLASS || it.kind == StructureKind.OBJECT })
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            if (!input.exhausted()) throw SerializationException("${descriptor.serialName}: ${input.size} bytes left over")
        }
    }
}

private const val VARINT = 0
private const val FIXED64 = 1
private const val LEN = 2
private const val NULL = 3
private const val FIXED32 = 5

// Only these exact (default) serializers are written compactly; one an app registers under another name is left alone.
private const val UUID = "kotlin.uuid.Uuid"
private const val INSTANT = "kotlin.time.Instant"

// Hashed ids always have this bit set, so their keys are always 4 bytes and never equal an explicit `@WireId`.
private const val HASHED_ID_BIT = 0x80_0000

private fun fieldIdsOf(descriptor: SerialDescriptor): IntArray? {
    if (descriptor.kind != StructureKind.CLASS && descriptor.kind != StructureKind.OBJECT) return null
    val ids = IntArray(descriptor.elementsCount) { index ->
        val name = descriptor.getElementName(index)
        val explicit = descriptor.getElementAnnotations(index).filterIsInstance<WireId>().firstOrNull()
        if (explicit == null) {
            (fnv1a32(name) and (HASHED_ID_BIT - 1)) or HASHED_ID_BIT
        } else {
            if (explicit.id !in 1 until HASHED_ID_BIT) {
                throw SerializationException("${descriptor.serialName}.$name: @WireId must be in 1 until $HASHED_ID_BIT, was ${explicit.id}")
            }
            explicit.id
        }
    }
    for (later in ids.indices) {
        for (earlier in 0 until later) {
            if (ids[earlier] == ids[later]) {
                throw SerializationException(
                    "${descriptor.serialName}.${descriptor.getElementName(earlier)} and " +
                        "${descriptor.getElementName(later)} share wire id ${ids[later]}; add @SerialName or @WireId"
                )
            }
        }
    }
    return ids
}

private fun fnv1a32(text: String): Int {
    var hash = 0x811C9DC5.toInt()
    for (byte in text.encodeToByteArray()) hash = (hash xor (byte.toInt() and 0xFF)) * 0x01000193
    return hash
}

private fun zigzag(value: Long): Long = (value shl 1) xor (value shr 63)
private fun unzigzag(value: Long): Long = (value ushr 1) xor -(value and 1)

private fun Sink.writeVarint(value: Long) {
    var rest = value
    while (rest and 0x7FL.inv() != 0L) {
        writeByte(((rest and 0x7F) or 0x80).toByte())
        rest = rest ushr 7
    }
    writeByte(rest.toByte())
}

private fun Source.readVarint(): Long {
    var result = 0L
    for (shift in 0 until 64 step 7) {
        val byte = readByte().toLong() and 0xFF
        if (shift == 63 && byte > 1) throw SerializationException("Varint overflows 64 bits")
        result = result or ((byte and 0x7F) shl shift)
        if (byte and 0x80 == 0L) return result
    }
    throw SerializationException("Varint longer than 10 bytes")
}
