package com.lightningkite.services.data

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule

class StringArrayFormat(override val serializersModule: SerializersModule) : StringFormat {

    @OptIn(ExperimentalSerializationApi::class)
    private inner class DataOutputEncoder(val output: (String)->Unit) : AbstractEncoder() {
        override val serializersModule: SerializersModule get() = this@StringArrayFormat.serializersModule
        override fun encodeBoolean(value: Boolean) { output(value.toString()) }
        override fun encodeByte(value: Byte) { output(value.toString()) }
        override fun encodeShort(value: Short) { output(value.toString()) }
        override fun encodeInt(value: Int) { output(value.toString()) }
        override fun encodeLong(value: Long) { output(value.toString()) }
        override fun encodeFloat(value: Float) { output(value.toString()) }
        override fun encodeDouble(value: Double) { output(value.toString()) }
        override fun encodeChar(value: Char) { output(value.toString()) }
        override fun encodeString(value: String) { output(value) }
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) { output(enumDescriptor.getElementName(index)) }
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
                    output(descriptor.getElementName(index))
                    lastMarkerWritten = index
                }
            }
            return true
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            if(descriptor.kind == StructureKind.CLASS && lastMarkerWritten != descriptor.elementsCount - 1) {
                if(descriptor.elementsCount >= 0xFE) {
                    output("end")
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private inner class DataInputDecoder(val input: () -> String, var elementsCount: Int = 0, val seq: Boolean = false) : AbstractDecoder() {
        private var elementIndex = 0
        override val serializersModule: SerializersModule get() = this@StringArrayFormat.serializersModule
        override fun decodeBoolean(): Boolean = input().toBoolean()
        override fun decodeByte(): Byte {
            val v = input()
            try {
                return v.toByte()
            } catch(n: NumberFormatException) {
                throw SerializationException("Expected a Byte, but got '$v'", n)
            }
        }
        override fun decodeShort(): Short {
            val v = input()
            try {
                return v.toShort()
            } catch(n: NumberFormatException) {
                throw SerializationException("Expected a Short, but got '$v'", n)
            }
        }
        override fun decodeInt(): Int {
            val v = input()
            try {
                return v.toInt()
            } catch(n: NumberFormatException) {
                throw SerializationException("Expected a Int, but got '$v'", n)
            }
        }
        override fun decodeLong(): Long {
            val v = input()
            try {
                return v.toLong()
            } catch(n: NumberFormatException) {
                throw SerializationException("Expected a Long, but got '$v'", n)
            }
        }
        override fun decodeFloat(): Float {
            val v = input()
            try {
                return v.toFloat()
            } catch(n: NumberFormatException) {
                throw SerializationException("Expected a Float, but got '$v'", n)
            }
        }
        override fun decodeDouble(): Double {
            val v = input()
            try {
                return v.toDouble()
            } catch(n: NumberFormatException) {
                throw SerializationException("Expected a Double, but got '$v'", n)
            }
        }
        override fun decodeChar(): Char = input().first()
        override fun decodeString(): String = input()

        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
            return enumDescriptor.getElementIndex(input())
        }

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if(descriptor.kind == StructureKind.CLASS) {
                if(elementIndex >= descriptor.elementsCount) {
                    return CompositeDecoder.DECODE_DONE
                }
                if(!descriptor.isElementOptional(elementIndex) && elementIndex < descriptor.elementsCount) {
                    return elementIndex++
                }
                val index = descriptor.getElementIndex(input())
                if (index == 0xFF) {
                    return CompositeDecoder.DECODE_DONE
                }
                if (index >= descriptor.elementsCount) throw SerializationException()
                elementIndex = index + 1
                return index
            } else {
                if (elementIndex == elementsCount) return CompositeDecoder.DECODE_DONE
                return elementIndex++
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

    fun <T> decodeFromStringList(deserializer: DeserializationStrategy<T>, list: List<String>): T {
        var index = 0
        return DataInputDecoder({ list[index++]}).decodeSerializableValue(deserializer)
    }
    fun <T> encodeToStringList(serializer: SerializationStrategy<T>, value: T): List<String> = buildList{
        DataOutputEncoder {add(it)}.encodeSerializableValue(serializer, value)
    }

    override fun <T> decodeFromString(deserializer: DeserializationStrategy<T>, string: String): T {
        val list = buildList {
            val current = StringBuilder()
            var i = 0
            while (i < string.length) {
                when {
                    string[i] == '\\' && i + 1 < string.length -> {
                        current.append(string[i + 1])
                        i += 2
                    }
                    string[i] == ',' -> {
                        add(current.toString())
                        current.clear()
                        i++
                    }
                    else -> {
                        current.append(string[i])
                        i++
                    }
                }
            }
            add(current.toString())
        }
        var index = 0
        return DataInputDecoder({ list[index++]}).decodeSerializableValue(deserializer)
    }

    override fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String = buildString {
        var first = true
        DataOutputEncoder {
            if(first) first = false
            else append(',')
            append(it.replace("\\", "\\\\").replace(",", "\\,"))
        }.encodeSerializableValue(serializer, value)
    }
}

