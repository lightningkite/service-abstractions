// by Claude
@file:Suppress("OPT_IN_USAGE")

package com.lightningkite.services.database.mapformat

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Collection handler that stores lists as List<Any?> and maps as Map<Any?, Any?>.
 *
 * This is suitable for databases that have native array/list/map support
 * (Postgres arrays, Cassandra collections) where the database layer handles
 * the actual type conversion.
 */
public class ArrayListCollectionHandler(
    private val config: MapFormatConfig,
) : CollectionHandler {

    override fun createListEncoder(
        fieldPath: String,
        elementDescriptor: SerialDescriptor,
        output: WriteTarget,
    ): CompositeEncoder {
        return ListAccumulatorEncoder(fieldPath, elementDescriptor, config, output)
    }

    override fun createListDecoder(
        fieldPath: String,
        elementDescriptor: SerialDescriptor,
        input: ReadSource,
    ): CompositeDecoder {
        val list = input.readField(fieldPath) as? List<*> ?: emptyList<Any?>()
        return ListReadDecoder(list, elementDescriptor, config)
    }

    override fun createMapEncoder(
        fieldPath: String,
        keyDescriptor: SerialDescriptor,
        valueDescriptor: SerialDescriptor,
        output: WriteTarget,
    ): CompositeEncoder {
        return MapAccumulatorEncoder(fieldPath, keyDescriptor, valueDescriptor, config, output)
    }

    override fun createMapDecoder(
        fieldPath: String,
        keyDescriptor: SerialDescriptor,
        valueDescriptor: SerialDescriptor,
        input: ReadSource,
    ): CompositeDecoder {
        val map = input.readField(fieldPath) as? Map<*, *> ?: emptyMap<Any?, Any?>()
        return MapReadDecoder(map, keyDescriptor, valueDescriptor, config)
    }
}

/**
 * Encoder that accumulates list elements into a List<Any?>.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class ListAccumulatorEncoder(
    private val fieldPath: String,
    private val elementDescriptor: SerialDescriptor,
    private val config: MapFormatConfig,
    private val output: WriteTarget,
) : CompositeEncoder {

    override val serializersModule: SerializersModule = config.serializersModule

    private val elements = mutableListOf<Any?>()

    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
        elements.add(value)
    }

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
        elements.add(value)
    }

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
        elements.add(value)
    }

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
        elements.add(value)
    }

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
        elements.add(value)
    }

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
        elements.add(value)
    }

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
        elements.add(value)
    }

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
        elements.add(value.toString())
    }

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
        elements.add(value)
    }

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
        // Check for converter on the element type
        config.converters.get(elementDescriptor)?.let {
            return ListElementConverterEncoder(elements, it)
        }
        return ListElementEncoder(elements, config)
    }

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        // Check for converter
        config.converters.get(serializer.descriptor)?.let {
            elements.add(config.converters.toDatabase(serializer.descriptor, value as Any))
            return
        }

        // For complex types (but not inline classes), encode to a nested map or JSON string
        // by Claude - added isInline check to prevent value classes from being encoded as maps
        if (serializer.descriptor.kind == StructureKind.CLASS && !serializer.descriptor.isInline) {
            // by Claude - when serializeCollectionEmbeddedAsJson is true, encode as JSON string
            // This is needed for databases like Cassandra that store list<text> for embedded objects
            if (config.serializeCollectionEmbeddedAsJson) {
                val json = Json {
                    serializersModule = config.serializersModule
                    encodeDefaults = true
                }
                val jsonString = json.encodeToString(serializer, value)
                elements.add(jsonString)
            } else {
                val nestedTarget = SimpleWriteTarget()
                val nestedEncoder = MapEncoder(config, nestedTarget)
                serializer.serialize(nestedEncoder, value)
                elements.add(nestedTarget.result().mainRecord)
            }
        } else {
            // For primitives wrapped in serializers (including inline classes), just add directly
            val elementEncoder = ListElementEncoder(elements, config)
            serializer.serialize(elementEncoder, value)
        }
    }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?
    ) {
        if (value == null) {
            elements.add(null)
        } else {
            encodeSerializableElement(descriptor, index, serializer, value)
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        output.writeField(fieldPath, elements.toList())
    }
}

/**
 * Simple encoder for list elements that adds values directly to the list.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class ListElementEncoder(
    private val elements: MutableList<Any?>,
    private val config: MapFormatConfig,
) : Encoder {

    override val serializersModule: SerializersModule = config.serializersModule

    override fun encodeBoolean(value: Boolean) { elements.add(value) }
    override fun encodeByte(value: Byte) { elements.add(value) }
    override fun encodeShort(value: Short) { elements.add(value) }
    override fun encodeInt(value: Int) { elements.add(value) }
    override fun encodeLong(value: Long) { elements.add(value) }
    override fun encodeFloat(value: Float) { elements.add(value) }
    override fun encodeDouble(value: Double) { elements.add(value) }
    override fun encodeChar(value: Char) { elements.add(value.toString()) }
    override fun encodeString(value: String) { elements.add(value) }
    override fun encodeNull() { elements.add(null) }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        elements.add(enumDescriptor.getElementName(index))
    }

    override fun encodeInline(descriptor: SerialDescriptor): Encoder {
        config.converters.get(descriptor)?.let {
            return ListElementConverterEncoder(elements, it)
        }
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        // For nested structures in lists, encode to a map
        val nestedTarget = SimpleWriteTarget()
        return NestedStructureEncoder(elements, config, nestedTarget)
    }
}

/**
 * Encoder for nested structures within list elements.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class NestedStructureEncoder(
    private val elements: MutableList<Any?>,
    private val config: MapFormatConfig,
    private val nestedTarget: SimpleWriteTarget,
) : CompositeEncoder {

    private val delegate = MapEncoder(config, nestedTarget)

    override val serializersModule: SerializersModule = config.serializersModule

    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) =
        delegate.encodeBooleanElement(descriptor, index, value)

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) =
        delegate.encodeByteElement(descriptor, index, value)

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) =
        delegate.encodeShortElement(descriptor, index, value)

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) =
        delegate.encodeIntElement(descriptor, index, value)

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) =
        delegate.encodeLongElement(descriptor, index, value)

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) =
        delegate.encodeFloatElement(descriptor, index, value)

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) =
        delegate.encodeDoubleElement(descriptor, index, value)

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) =
        delegate.encodeCharElement(descriptor, index, value)

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) =
        delegate.encodeStringElement(descriptor, index, value)

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder =
        delegate.encodeInlineElement(descriptor, index)

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) = delegate.encodeSerializableElement(descriptor, index, serializer, value)

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?
    ) = delegate.encodeNullableSerializableElement(descriptor, index, serializer, value)

    override fun endStructure(descriptor: SerialDescriptor) {
        elements.add(nestedTarget.result().mainRecord)
    }
}

/**
 * Encoder for list elements with value converter.
 */
internal class ListElementConverterEncoder(
    private val elements: MutableList<Any?>,
    private val converter: ValueConverter<*, *>,
) : Encoder {

    override val serializersModule: SerializersModule = EmptySerializersModule()

    @Suppress("UNCHECKED_CAST")
    private fun <T> write(value: T) {
        val conv = converter as ValueConverter<T, Any>
        elements.add(conv.toDatabase(value))
    }

    override fun encodeBoolean(value: Boolean) = write(value)
    override fun encodeByte(value: Byte) = write(value)
    override fun encodeShort(value: Short) = write(value)
    override fun encodeInt(value: Int) = write(value)
    override fun encodeLong(value: Long) = write(value)
    override fun encodeFloat(value: Float) = write(value)
    override fun encodeDouble(value: Double) = write(value)
    override fun encodeChar(value: Char) = write(value)
    override fun encodeString(value: String) = write(value)
    override fun encodeNull() { elements.add(null) }
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
        write(enumDescriptor.getElementName(index))
    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    @OptIn(ExperimentalSerializationApi::class)
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        throw SerializationException("ListElementConverterEncoder cannot encode structures")
}

/**
 * Decoder that reads list elements from a List<Any?>.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class ListReadDecoder(
    private val list: List<*>,
    private val elementDescriptor: SerialDescriptor,
    private val config: MapFormatConfig,
) : CompositeDecoder {

    override val serializersModule: SerializersModule = config.serializersModule

    private var currentIndex = 0

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return if (currentIndex < list.size) currentIndex++ else CompositeDecoder.DECODE_DONE
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = list.size

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean =
        list[index] as Boolean

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte =
        (list[index] as Number).toByte()

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short =
        (list[index] as Number).toShort()

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int =
        (list[index] as Number).toInt()

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long =
        (list[index] as Number).toLong()

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float =
        (list[index] as Number).toFloat()

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double =
        (list[index] as Number).toDouble()

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char =
        (list[index] as String).first()

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String =
        list[index] as String

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        val elemDesc = descriptor.getElementDescriptor(index)
        config.converters.get(elemDesc)?.let {
            return ListElementConverterDecoder(list[index], it)
        }
        return ListElementDecoder(list[index], config)
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        val element = list[index]

        // Check for converter
        config.converters.get(deserializer.descriptor)?.let {
            @Suppress("UNCHECKED_CAST")
            return config.converters.fromDatabase(deserializer.descriptor, element as Any)
        }

        // For complex types, decode from nested map or JSON string
        if (deserializer.descriptor.kind == StructureKind.CLASS && !deserializer.descriptor.isInline) {
            // by Claude - when serializeCollectionEmbeddedAsJson is true, decode from JSON string
            if (config.serializeCollectionEmbeddedAsJson && element is String) {
                val json = Json {
                    serializersModule = config.serializersModule
                    ignoreUnknownKeys = true
                }
                return json.decodeFromString(deserializer, element)
            } else if (element is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val source = SimpleReadSource(element as Map<String, Any?>, emptyMap(), config.fieldSeparator)
                return MapDecoder(config, source, deserializer.descriptor).decodeSerializableValue(deserializer)
            }
        }

        // For primitives
        val decoder = ListElementDecoder(element, config)
        return deserializer.deserialize(decoder)
    }

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?
    ): T? {
        val element = list[index]
        return if (element == null) null else decodeSerializableElement(descriptor, index, deserializer, previousValue)
    }

    override fun endStructure(descriptor: SerialDescriptor) {}
}

/**
 * Simple decoder for list elements.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class ListElementDecoder(
    private val element: Any?,
    private val config: MapFormatConfig,
) : Decoder {

    override val serializersModule: SerializersModule = config.serializersModule

    override fun decodeBoolean(): Boolean = element as Boolean
    override fun decodeByte(): Byte = (element as Number).toByte()
    override fun decodeShort(): Short = (element as Number).toShort()
    override fun decodeInt(): Int = (element as Number).toInt()
    override fun decodeLong(): Long = (element as Number).toLong()
    override fun decodeFloat(): Float = (element as Number).toFloat()
    override fun decodeDouble(): Double = (element as Number).toDouble()
    override fun decodeChar(): Char = (element as String).first()
    override fun decodeString(): String = element as String
    override fun decodeNull(): Nothing? = null

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val name = element as String
        return enumDescriptor.getElementIndex(name)
    }

    override fun decodeInline(descriptor: SerialDescriptor): Decoder {
        config.converters.get(descriptor)?.let {
            return ListElementConverterDecoder(element, it)
        }
        return this
    }

    override fun decodeNotNullMark(): Boolean = element != null

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        // For nested structures, decode from map
        @Suppress("UNCHECKED_CAST")
        val map = element as Map<String, Any?>
        val source = SimpleReadSource(map, emptyMap(), config.fieldSeparator)
        return MapDecoder(config, source, descriptor)
    }
}

/**
 * Decoder for list elements with value converter.
 */
internal class ListElementConverterDecoder(
    private val element: Any?,
    private val converter: ValueConverter<*, *>,
) : Decoder {

    override val serializersModule: SerializersModule = EmptySerializersModule()

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(): T {
        val conv = converter as ValueConverter<T, Any>
        return conv.fromDatabase(element as Any)
    }

    override fun decodeBoolean(): Boolean = read()
    override fun decodeByte(): Byte = read()
    override fun decodeShort(): Short = read()
    override fun decodeInt(): Int = read()
    override fun decodeLong(): Long = read()
    override fun decodeFloat(): Float = read()
    override fun decodeDouble(): Double = read()
    override fun decodeChar(): Char = read()
    override fun decodeString(): String = read()
    override fun decodeNull(): Nothing? = null
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val name: String = read()
        return enumDescriptor.getElementIndex(name)
    }
    override fun decodeInline(descriptor: SerialDescriptor): Decoder = this
    override fun decodeNotNullMark(): Boolean = element != null

    @OptIn(ExperimentalSerializationApi::class)
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        throw SerializationException("ListElementConverterDecoder cannot decode structures")
}

/**
 * Encoder that accumulates map entries into a Map<Any?, Any?>.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class MapAccumulatorEncoder(
    private val fieldPath: String,
    private val keyDescriptor: SerialDescriptor,
    private val valueDescriptor: SerialDescriptor,
    private val config: MapFormatConfig,
    private val output: WriteTarget,
) : CompositeEncoder {

    override val serializersModule: SerializersModule = config.serializersModule

    private val entries = mutableMapOf<Any?, Any?>()
    private var pendingKey: Any? = null

    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
        handleElement(index, value)
    }

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
        handleElement(index, value)
    }

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
        handleElement(index, value)
    }

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
        handleElement(index, value)
    }

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
        handleElement(index, value)
    }

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
        handleElement(index, value)
    }

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
        handleElement(index, value)
    }

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
        handleElement(index, value.toString())
    }

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
        handleElement(index, value)
    }

    private fun handleElement(index: Int, value: Any?) {
        if (index % 2 == 0) {
            // Key
            pendingKey = value
        } else {
            // Value
            entries[pendingKey] = value
        }
    }

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
        val elemDesc = if (index % 2 == 0) keyDescriptor else valueDescriptor
        config.converters.get(elemDesc)?.let {
            return MapElementConverterEncoder(this, index, it)
        }
        return MapElementEncoder(this, index, config)
    }

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        // Check for converter
        config.converters.get(serializer.descriptor)?.let {
            handleElement(index, config.converters.toDatabase(serializer.descriptor, value as Any))
            return
        }

        // For complex types (but not inline classes), encode to a nested map or JSON string
        // by Claude - added isInline check to prevent value classes from being encoded as maps
        if (serializer.descriptor.kind == StructureKind.CLASS && !serializer.descriptor.isInline) {
            // by Claude - when serializeCollectionEmbeddedAsJson is true, encode as JSON string
            if (config.serializeCollectionEmbeddedAsJson) {
                val json = Json {
                    serializersModule = config.serializersModule
                    encodeDefaults = true
                }
                val jsonString = json.encodeToString(serializer, value)
                handleElement(index, jsonString)
            } else {
                val nestedTarget = SimpleWriteTarget()
                val nestedEncoder = MapEncoder(config, nestedTarget)
                serializer.serialize(nestedEncoder, value)
                handleElement(index, nestedTarget.result().mainRecord)
            }
        } else {
            // For primitives (including inline classes), serialize directly
            val elementEncoder = MapElementEncoder(this, index, config)
            serializer.serialize(elementEncoder, value)
        }
    }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?
    ) {
        if (value == null) {
            handleElement(index, null)
        } else {
            encodeSerializableElement(descriptor, index, serializer, value)
        }
    }

    internal fun addElement(index: Int, value: Any?) {
        handleElement(index, value)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        output.writeField(fieldPath, entries.toMap())
    }
}

/**
 * Simple encoder for map elements.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class MapElementEncoder(
    private val parent: MapAccumulatorEncoder,
    private val index: Int,
    private val config: MapFormatConfig,
) : Encoder {

    override val serializersModule: SerializersModule = config.serializersModule

    override fun encodeBoolean(value: Boolean) { parent.addElement(index, value) }
    override fun encodeByte(value: Byte) { parent.addElement(index, value) }
    override fun encodeShort(value: Short) { parent.addElement(index, value) }
    override fun encodeInt(value: Int) { parent.addElement(index, value) }
    override fun encodeLong(value: Long) { parent.addElement(index, value) }
    override fun encodeFloat(value: Float) { parent.addElement(index, value) }
    override fun encodeDouble(value: Double) { parent.addElement(index, value) }
    override fun encodeChar(value: Char) { parent.addElement(index, value.toString()) }
    override fun encodeString(value: String) { parent.addElement(index, value) }
    override fun encodeNull() { parent.addElement(index, null) }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        parent.addElement(this.index, enumDescriptor.getElementName(index))
    }

    override fun encodeInline(descriptor: SerialDescriptor): Encoder {
        config.converters.get(descriptor)?.let {
            return MapElementConverterEncoder(parent, index, it)
        }
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        val nestedTarget = SimpleWriteTarget()
        return NestedMapStructureEncoder(parent, index, config, nestedTarget)
    }
}

/**
 * Encoder for nested structures within map elements.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class NestedMapStructureEncoder(
    private val parent: MapAccumulatorEncoder,
    private val index: Int,
    private val config: MapFormatConfig,
    private val nestedTarget: SimpleWriteTarget,
) : CompositeEncoder {

    private val delegate = MapEncoder(config, nestedTarget)

    override val serializersModule: SerializersModule = config.serializersModule

    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) =
        delegate.encodeBooleanElement(descriptor, index, value)

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) =
        delegate.encodeByteElement(descriptor, index, value)

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) =
        delegate.encodeShortElement(descriptor, index, value)

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) =
        delegate.encodeIntElement(descriptor, index, value)

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) =
        delegate.encodeLongElement(descriptor, index, value)

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) =
        delegate.encodeFloatElement(descriptor, index, value)

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) =
        delegate.encodeDoubleElement(descriptor, index, value)

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) =
        delegate.encodeCharElement(descriptor, index, value)

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) =
        delegate.encodeStringElement(descriptor, index, value)

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder =
        delegate.encodeInlineElement(descriptor, index)

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) = delegate.encodeSerializableElement(descriptor, index, serializer, value)

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?
    ) = delegate.encodeNullableSerializableElement(descriptor, index, serializer, value)

    override fun endStructure(descriptor: SerialDescriptor) {
        parent.addElement(index, nestedTarget.result().mainRecord)
    }
}

/**
 * Encoder for map elements with value converter.
 */
internal class MapElementConverterEncoder(
    private val parent: MapAccumulatorEncoder,
    private val index: Int,
    private val converter: ValueConverter<*, *>,
) : Encoder {

    override val serializersModule: SerializersModule = EmptySerializersModule()

    @Suppress("UNCHECKED_CAST")
    private fun <T> write(value: T) {
        val conv = converter as ValueConverter<T, Any>
        parent.addElement(index, conv.toDatabase(value))
    }

    override fun encodeBoolean(value: Boolean) = write(value)
    override fun encodeByte(value: Byte) = write(value)
    override fun encodeShort(value: Short) = write(value)
    override fun encodeInt(value: Int) = write(value)
    override fun encodeLong(value: Long) = write(value)
    override fun encodeFloat(value: Float) = write(value)
    override fun encodeDouble(value: Double) = write(value)
    override fun encodeChar(value: Char) = write(value)
    override fun encodeString(value: String) = write(value)
    override fun encodeNull() { parent.addElement(index, null) }
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
        write(enumDescriptor.getElementName(index))
    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    @OptIn(ExperimentalSerializationApi::class)
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        throw SerializationException("MapElementConverterEncoder cannot encode structures")
}

/**
 * Decoder that reads map entries from a Map<*, *>.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class MapReadDecoder(
    private val map: Map<*, *>,
    private val keyDescriptor: SerialDescriptor,
    private val valueDescriptor: SerialDescriptor,
    private val config: MapFormatConfig,
) : CompositeDecoder {

    override val serializersModule: SerializersModule = config.serializersModule

    private val entries = map.entries.toList()
    private var currentIndex = 0

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        // Map encoding uses pairs: key at even indices, value at odd
        return if (currentIndex < entries.size * 2) currentIndex++ else CompositeDecoder.DECODE_DONE
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = map.size

    private fun currentEntry(): Map.Entry<*, *> = entries[currentIndex / 2]
    private fun isKey(): Boolean = currentIndex % 2 == 0
    private fun currentValue(): Any? = if (isKey()) currentEntry().key else currentEntry().value

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean =
        getValue(index) as Boolean

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte =
        (getValue(index) as Number).toByte()

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short =
        (getValue(index) as Number).toShort()

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int =
        (getValue(index) as Number).toInt()

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long =
        (getValue(index) as Number).toLong()

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float =
        (getValue(index) as Number).toFloat()

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double =
        (getValue(index) as Number).toDouble()

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char =
        (getValue(index) as String).first()

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String =
        getValue(index) as String

    private fun getValue(index: Int): Any? {
        val entryIndex = index / 2
        return if (index % 2 == 0) entries[entryIndex].key else entries[entryIndex].value
    }

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        val elemDesc = if (index % 2 == 0) keyDescriptor else valueDescriptor
        config.converters.get(elemDesc)?.let {
            return ListElementConverterDecoder(getValue(index), it)
        }
        return ListElementDecoder(getValue(index), config)
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        val element = getValue(index)

        // Check for converter
        config.converters.get(deserializer.descriptor)?.let {
            @Suppress("UNCHECKED_CAST")
            return config.converters.fromDatabase(deserializer.descriptor, element as Any)
        }

        // For complex types, decode from nested map or JSON string
        if (deserializer.descriptor.kind == StructureKind.CLASS && !deserializer.descriptor.isInline) {
            // by Claude - when serializeCollectionEmbeddedAsJson is true, decode from JSON string
            if (config.serializeCollectionEmbeddedAsJson && element is String) {
                val json = Json {
                    serializersModule = config.serializersModule
                    ignoreUnknownKeys = true
                }
                return json.decodeFromString(deserializer, element)
            } else if (element is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val source = SimpleReadSource(element as Map<String, Any?>, emptyMap(), config.fieldSeparator)
                return MapDecoder(config, source, deserializer.descriptor).decodeSerializableValue(deserializer)
            }
        }

        // For primitives
        val decoder = ListElementDecoder(element, config)
        return deserializer.deserialize(decoder)
    }

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?
    ): T? {
        val element = getValue(index)
        return if (element == null) null else decodeSerializableElement(descriptor, index, deserializer, previousValue)
    }

    override fun endStructure(descriptor: SerialDescriptor) {}
}
