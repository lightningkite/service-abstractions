@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.serviceabstractions.data

import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readDouble
import kotlinx.io.readFloat
import kotlinx.io.readString
import kotlinx.io.writeDouble
import kotlinx.io.writeFloat
import kotlinx.io.writeString
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule

public class KotlinBytesFormat(override val serializersModule: SerializersModule) : BinaryFormat {
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
        override val serializersModule: SerializersModule get() = this@KotlinBytesFormat.serializersModule
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
            output.writeShort(encoded.size.toShort())
            output.write(encoded)
        }

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
            if(enumDescriptor.elementsCount > 0x7F) output.writeShort(index.toShort())
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
            if(descriptor.kind == StructureKind.CLASS) {
                if(index == lastMarkerWritten + 1 && !descriptor.isElementOptional(index)) {
                    lastMarkerWritten = index
                } else {
                    if(descriptor.elementsCount >= 0xFE) {
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
            if(descriptor.kind == StructureKind.CLASS && lastMarkerWritten != descriptor.elementsCount - 1) {
                if(descriptor.elementsCount >= 0xFE) {
                    output.writeShort(0xFFFF.toShort())
                } else {
                    output.writeByte(0xFF.toByte())
                }
            }
        }
    }

    private inner class DataInputDecoder(val input: Source, var elementsCount: Int = 0, val seq: Boolean = false) : AbstractDecoder() {
        private var elementIndex = 0
        override val serializersModule: SerializersModule get() = this@KotlinBytesFormat.serializersModule
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
            return if(enumDescriptor.elementsCount > 0x7F) input.readShort().toUShort().toInt()
            else input.readByte().toUByte().toInt()
        }

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if(descriptor.kind == StructureKind.CLASS) {
                if(elementIndex >= descriptor.elementsCount) {
                    return (CompositeDecoder.DECODE_DONE)
                }
                if(!descriptor.isElementOptional(elementIndex) && elementIndex < descriptor.elementsCount) {
                    return (elementIndex++)
                }
                if(descriptor.elementsCount >= 0xFE) {
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
            decodeInt().also { elementsCount = it }

        override fun decodeNotNullMark(): Boolean = decodeBoolean()
    }
}


