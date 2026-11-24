@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.serializers

import kotlinx.io.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.*
import kotlinx.serialization.modules.SerializersModule

@Deprecated(
    "Positional format that can't evolve; use KotlinWireFormat for new data. OldKotlinBytesFormat remains to read data already written.",
    ReplaceWith("OldKotlinBytesFormat")
)
public typealias KotlinBytesFormat = OldKotlinBytesFormat

/**
 * The original positional binary format, frozen so data already written stays readable.  Write new data with
 * [KotlinWireFormat].
 *
 * Class fields are written in declaration order; required fields carry no marker, optional fields are preceded by
 * their index, and an end marker follows only when trailing fields were skipped.  Nothing has a length, so the only
 * schema change old data survives is optional fields added at the end of a value that ends the input (the top-level
 * value, or a last field all the way up): the input ends where the new field's marker would be.
 */
public class OldKotlinBytesFormat(override val serializersModule: SerializersModule) : BinaryFormat {
    override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        return DataInputDecoder(Buffer().also {
            it.write(bytes)
        }).decodeSerializableValue(deserializer)
    }

    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray {
        val out = Buffer()
        DataOutputEncoder(out).encodeSerializableValue(serializer, value)
        return out.readByteArray()
    }

    private inner class DataOutputEncoder(val output: Sink) : AbstractEncoder() {
        override val serializersModule: SerializersModule get() = this@OldKotlinBytesFormat.serializersModule
        override fun encodeBoolean(value: Boolean) = output.writeByte((if (value) 1 else 0))
        override fun encodeByte(value: Byte) = output.writeByte(value)
        override fun encodeShort(value: Short) = output.writeShort(value)
        override fun encodeInt(value: Int) = output.writeInt(value)
        override fun encodeLong(value: Long) = output.writeLong(value)
        override fun encodeFloat(value: Float) = output.writeFloat(value)
        override fun encodeDouble(value: Double) = output.writeDouble(value)
        override fun encodeChar(value: Char) = output.writeShort(value.code.toShort())
        override fun encodeString(value: String) {
            val encoded = value.encodeToByteArray()
            require(encoded.size <= UShort.MAX_VALUE.toInt()) {
                "String too long for binary encoding: ${encoded.size} bytes (max ${UShort.MAX_VALUE})"
            }
            output.writeShort(encoded.size.toShort())
            output.write(encoded)
        }

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
            if (enumDescriptor.elementsCount > 0x7F) output.writeShort(index.toShort())
            else output.writeByte(index.toByte())
        }

        override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
            encodeInt(collectionSize)
            return DataOutputEncoder(output)
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            return DataOutputEncoder(output)
        }

        override fun encodeNull() = encodeBoolean(false)
        override fun encodeNotNullMark() = encodeBoolean(true)

        var lastMarkerWritten = -1
        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            if (descriptor.kind == StructureKind.CLASS) {
                if (index == lastMarkerWritten + 1 && !descriptor.isElementOptional(index)) {
                    lastMarkerWritten = index
                } else {
                    if (descriptor.elementsCount >= 0xFE) {
                        output.writeShort(index.toShort())
                    } else {
                        output.writeByte(index.toByte())
                    }
                    lastMarkerWritten = index
                }
            }
            return true
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            if (descriptor.kind == StructureKind.CLASS && lastMarkerWritten != descriptor.elementsCount - 1) {
                if (descriptor.elementsCount >= 0xFE) {
                    output.writeShort(0xFFFF.toShort())
                } else {
                    output.writeByte(0xFF.toByte())
                }
            }
        }
    }

    private inner class DataInputDecoder(val input: Source, var elementsCount: Int = 0, val seq: Boolean = false) :
        AbstractDecoder() {
        private var elementIndex = 0
        override val serializersModule: SerializersModule get() = this@OldKotlinBytesFormat.serializersModule
        override fun decodeBoolean(): Boolean = (input.readByte().toInt() != 0)
        override fun decodeByte(): Byte = input.readByte()
        override fun decodeShort(): Short = input.readShort()
        override fun decodeInt(): Int = input.readInt()
        override fun decodeLong(): Long = input.readLong()
        override fun decodeFloat(): Float = input.readFloat()
        override fun decodeDouble(): Double = input.readDouble()
        override fun decodeChar(): Char = input.readShort().toInt().toChar()
        override fun decodeString(): String {
            val size = input.readShort().toUShort().toLong()
            return input.readString(size)
        }

        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
            return if (enumDescriptor.elementsCount > 0x7F) input.readShort().toUShort().toInt()
            else input.readByte().toUByte().toInt()
        }

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if (descriptor.kind == StructureKind.CLASS) {
                if (elementIndex >= descriptor.elementsCount) {
                    return (CompositeDecoder.DECODE_DONE)
                }
                if (!descriptor.isElementOptional(elementIndex) && elementIndex < descriptor.elementsCount) {
                    return (elementIndex++)
                }
                // Data written before trailing optional fields were added ends here, without an end marker.  Markers
                // can't be compared to find an end elsewhere: `PartialSerializer` writes them in insertion order.
                if (input.exhausted()) return CompositeDecoder.DECODE_DONE
                if (descriptor.elementsCount >= 0xFE) {
                    val index = input.readShort().toUShort().toInt()
                    if (index == 0xFFFF) {
                        return CompositeDecoder.DECODE_DONE
                    }
                    if (index >= descriptor.elementsCount) throw SerializationException()
                    elementIndex = index + 1
                    return (index)
                } else {
                    val index = input.readByte().toUByte().toInt()
                    if (index == 0xFF) {
                        return CompositeDecoder.DECODE_DONE
                    }
                    if (index >= descriptor.elementsCount) throw SerializationException()
                    elementIndex = index + 1
                    return (index)
                }
            } else {
                if (elementIndex == elementsCount) return CompositeDecoder.DECODE_DONE
                return (elementIndex++)
            }
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
            return DataInputDecoder(input, descriptor.elementsCount, descriptor.kind != StructureKind.CLASS)
        }

        override fun decodeSequentially(): Boolean = seq

        override fun decodeCollectionSize(descriptor: SerialDescriptor): Int =
            decodeInt().also {
                require(it >= 0) { "Invalid negative collection size: $it" }
                elementsCount = it
            }

        override fun decodeNotNullMark(): Boolean = decodeBoolean()
    }
}


