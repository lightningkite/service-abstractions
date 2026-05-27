@file:Suppress("OPT_IN_USAGE")

package com.lightningkite.services.database.sql

import com.lightningkite.services.database.mapformat.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

/**
 * CollectionHandler that writes/reads collection elements as child table rows.
 *
 * Each collection element becomes a ChildRow written via WriteTarget.writeChild() and
 * read back via ReadSource.readChildren(). This is the natural strategy for generic SQL
 * databases that lack native array or collection column types.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class ChildTableCollectionHandler(
    private val configProvider: () -> MapFormatConfig,
) : CollectionHandler {

    private val config: MapFormatConfig get() = configProvider()

    override fun createListEncoder(
        fieldPath: String,
        elementDescriptor: SerialDescriptor,
        output: WriteTarget,
    ): CompositeEncoder = ChildTableListEncoder(fieldPath, elementDescriptor, output, config)

    override fun createListDecoder(
        fieldPath: String,
        elementDescriptor: SerialDescriptor,
        input: ReadSource,
    ): CompositeDecoder {
        val rows = input.readChildren(fieldPath).sortedBy { it.index ?: 0 }
        return ChildTableListDecoder(rows, elementDescriptor, config)
    }

    override fun createMapEncoder(
        fieldPath: String,
        keyDescriptor: SerialDescriptor,
        valueDescriptor: SerialDescriptor,
        output: WriteTarget,
    ): CompositeEncoder = ChildTableMapEncoder(fieldPath, keyDescriptor, valueDescriptor, output, config)

    override fun createMapDecoder(
        fieldPath: String,
        keyDescriptor: SerialDescriptor,
        valueDescriptor: SerialDescriptor,
        input: ReadSource,
    ): CompositeDecoder {
        val rows = input.readChildren(fieldPath).sortedBy { it.index ?: 0 }
        return ChildTableMapDecoder(rows, keyDescriptor, valueDescriptor, config)
    }
}

// ========== List/Set Encoding ==========

/**
 * Encodes list/set elements as ChildRows, writing each element immediately via writeChild().
 * Primitives are stored as `mapOf("value" to v)`. Complex types are flattened via MapEncoder.
 */
@OptIn(ExperimentalSerializationApi::class)
private class ChildTableListEncoder(
    private val fieldPath: String,
    private val elementDescriptor: SerialDescriptor,
    private val output: WriteTarget,
    private val config: MapFormatConfig,
) : CompositeEncoder {

    override val serializersModule: SerializersModule = config.serializersModule

    private fun writePrimitive(index: Int, value: Any?) {
        output.writeChild(fieldPath, ChildRow(index = index, key = null, values = mapOf("value" to value)))
    }

    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
        writePrimitive(index, value)
    }

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
        writePrimitive(index, value)
    }

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
        writePrimitive(index, value)
    }

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
        writePrimitive(index, value)
    }

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
        writePrimitive(index, value)
    }

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
        writePrimitive(index, value)
    }

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
        writePrimitive(index, value)
    }

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
        writePrimitive(index, value.toString())
    }

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
        writePrimitive(index, value)
    }

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
        config.converters.get(elementDescriptor)?.let {
            return ChildTableListElementConverterEncoder(fieldPath, index, it, output)
        }
        return ChildTableListElementEncoder(fieldPath, index, config, output)
    }

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        config.converters.get(serializer.descriptor)?.let {
            val converted = config.converters.toDatabase(serializer.descriptor, value as Any)
            writePrimitive(index, converted)
            return
        }

        if (serializer.descriptor.kind == StructureKind.CLASS && !serializer.descriptor.isInline) {
            val subTarget = SimpleWriteTarget()
            val subEncoder = MapEncoder(config, subTarget)
            serializer.serialize(subEncoder, value)
            output.writeChild(fieldPath, ChildRow(index = index, key = null, values = subTarget.result().mainRecord))
        } else {
            val elementEncoder = ChildTableListElementEncoder(fieldPath, index, config, output)
            serializer.serialize(elementEncoder, value)
        }
    }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?,
    ) {
        if (value == null) {
            writePrimitive(index, null)
        } else {
            encodeSerializableElement(descriptor, index, serializer, value)
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        // All elements written inline via writeChild()
    }
}

/**
 * Encoder for individual list elements that need to go through the Encoder interface
 * (inline classes, enums).
 */
@OptIn(ExperimentalSerializationApi::class)
private class ChildTableListElementEncoder(
    private val fieldPath: String,
    private val index: Int,
    private val config: MapFormatConfig,
    private val output: WriteTarget,
) : Encoder {

    override val serializersModule: SerializersModule = config.serializersModule

    private fun writePrimitive(value: Any?) {
        output.writeChild(fieldPath, ChildRow(index = index, key = null, values = mapOf("value" to value)))
    }

    override fun encodeBoolean(value: Boolean) = writePrimitive(value)
    override fun encodeByte(value: Byte) = writePrimitive(value)
    override fun encodeShort(value: Short) = writePrimitive(value)
    override fun encodeInt(value: Int) = writePrimitive(value)
    override fun encodeLong(value: Long) = writePrimitive(value)
    override fun encodeFloat(value: Float) = writePrimitive(value)
    override fun encodeDouble(value: Double) = writePrimitive(value)
    override fun encodeChar(value: Char) = writePrimitive(value.toString())
    override fun encodeString(value: String) = writePrimitive(value)
    override fun encodeNull() = writePrimitive(null)

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        writePrimitive(enumDescriptor.getElementName(index))
    }

    override fun encodeInline(descriptor: SerialDescriptor): Encoder {
        config.converters.get(descriptor)?.let {
            return ChildTableListElementConverterEncoder(fieldPath, this.index, it, output)
        }
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        val subTarget = SimpleWriteTarget()
        return ChildTableListNestedStructureEncoder(fieldPath, index, config, subTarget, output)
    }
}

/**
 * Encoder for list elements with a value converter.
 */
@OptIn(ExperimentalSerializationApi::class)
private class ChildTableListElementConverterEncoder(
    private val fieldPath: String,
    private val index: Int,
    private val converter: ValueConverter<*, *>,
    private val output: WriteTarget,
) : Encoder {

    override val serializersModule: SerializersModule = output.let { _ ->
        kotlinx.serialization.modules.EmptySerializersModule()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> write(value: T) {
        val conv = converter as ValueConverter<T, Any>
        output.writeChild(fieldPath, ChildRow(index = index, key = null, values = mapOf("value" to conv.toDatabase(value))))
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
    override fun encodeNull() {
        output.writeChild(fieldPath, ChildRow(index = index, key = null, values = mapOf("value" to null)))
    }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
        write(enumDescriptor.getElementName(index))

    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        throw kotlinx.serialization.SerializationException("ChildTableListElementConverterEncoder cannot encode structures")
}

/**
 * Encoder for nested structures (data classes) within list elements.
 * Delegates to MapEncoder, then wraps the result as a ChildRow on endStructure().
 */
@OptIn(ExperimentalSerializationApi::class)
private class ChildTableListNestedStructureEncoder(
    private val fieldPath: String,
    private val index: Int,
    private val config: MapFormatConfig,
    private val subTarget: SimpleWriteTarget,
    private val output: WriteTarget,
) : CompositeEncoder {

    private val delegate = MapEncoder(config, subTarget)

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
        value: T,
    ) = delegate.encodeSerializableElement(descriptor, index, serializer, value)

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?,
    ) = delegate.encodeNullableSerializableElement(descriptor, index, serializer, value)

    override fun endStructure(descriptor: SerialDescriptor) {
        output.writeChild(fieldPath, ChildRow(index = this.index, key = null, values = subTarget.result().mainRecord))
    }
}

// ========== List/Set Decoding ==========

/**
 * Decodes list/set elements from pre-loaded ChildRows.
 * Each row's values map is decoded back to the element type.
 */
@OptIn(ExperimentalSerializationApi::class)
private class ChildTableListDecoder(
    private val rows: List<ChildRow>,
    private val elementDescriptor: SerialDescriptor,
    private val config: MapFormatConfig,
) : CompositeDecoder {

    override val serializersModule: SerializersModule = config.serializersModule

    private var currentIndex = 0

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return if (currentIndex < rows.size) currentIndex++ else CompositeDecoder.DECODE_DONE
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = rows.size

    private fun primitiveValue(index: Int): Any? = rows[index].values["value"]

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean =
        primitiveValue(index) as Boolean

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte =
        (primitiveValue(index) as Number).toByte()

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short =
        (primitiveValue(index) as Number).toShort()

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int =
        (primitiveValue(index) as Number).toInt()

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long =
        (primitiveValue(index) as Number).toLong()

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float =
        (primitiveValue(index) as Number).toFloat()

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double =
        (primitiveValue(index) as Number).toDouble()

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char =
        (primitiveValue(index) as String).first()

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String =
        primitiveValue(index) as String

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        config.converters.get(elementDescriptor)?.let {
            return ChildTableElementConverterDecoder(primitiveValue(index), it)
        }
        return ChildTableElementDecoder(primitiveValue(index), config)
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        val row = rows[index]

        config.converters.get(deserializer.descriptor)?.let {
            @Suppress("UNCHECKED_CAST")
            return config.converters.fromDatabase(deserializer.descriptor, row.values["value"] as Any)
        }

        if (deserializer.descriptor.kind == StructureKind.CLASS && !deserializer.descriptor.isInline) {
            val source = SimpleReadSource(row.values, emptyMap(), config.fieldSeparator)
            return MapDecoder(config, source).decodeSerializableValue(deserializer)
        }

        val decoder = ChildTableElementDecoder(row.values["value"], config)
        return deserializer.deserialize(decoder)
    }

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? {
        val value = rows[index].values["value"]
        return if (value == null) null else decodeSerializableElement(descriptor, index, deserializer, previousValue)
    }

    override fun endStructure(descriptor: SerialDescriptor) {}
}

// ========== Map Encoding ==========

/**
 * Encodes map entries as ChildRows. Keys (even indices) are accumulated; values (odd indices)
 * trigger writing a complete ChildRow with key and values maps.
 */
@OptIn(ExperimentalSerializationApi::class)
private class ChildTableMapEncoder(
    private val fieldPath: String,
    private val keyDescriptor: SerialDescriptor,
    private val valueDescriptor: SerialDescriptor,
    private val output: WriteTarget,
    private val config: MapFormatConfig,
) : CompositeEncoder {

    override val serializersModule: SerializersModule = config.serializersModule

    private var entryIndex = 0
    private var pendingKey: Map<String, Any?> = emptyMap()

    private fun handleElement(index: Int, value: Any?) {
        if (index % 2 == 0) {
            // Key: store as key map (column name is "key" for primitive keys)
            pendingKey = mapOf("key" to value)
        } else {
            // Value: write complete ChildRow
            output.writeChild(
                fieldPath,
                ChildRow(index = entryIndex++, key = pendingKey, values = mapOf("value" to value))
            )
        }
    }

    private fun handleKeyMap(index: Int, keyMap: Map<String, Any?>) {
        if (index % 2 == 0) {
            pendingKey = keyMap
        }
    }

    private fun handleValueMap(index: Int, valueMap: Map<String, Any?>) {
        if (index % 2 == 1) {
            output.writeChild(
                fieldPath,
                ChildRow(index = entryIndex++, key = pendingKey, values = valueMap)
            )
        }
    }

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

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
        val elemDesc = if (index % 2 == 0) keyDescriptor else valueDescriptor
        config.converters.get(elemDesc)?.let {
            return ChildTableMapElementConverterEncoder(this, index, it)
        }
        return ChildTableMapElementEncoder(this, index, config)
    }

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        config.converters.get(serializer.descriptor)?.let {
            handleElement(index, config.converters.toDatabase(serializer.descriptor, value as Any))
            return
        }

        if (serializer.descriptor.kind == StructureKind.CLASS && !serializer.descriptor.isInline) {
            val subTarget = SimpleWriteTarget()
            val subEncoder = MapEncoder(config, subTarget)
            serializer.serialize(subEncoder, value)
            val result = subTarget.result().mainRecord
            if (index % 2 == 0) {
                handleKeyMap(index, result)
            } else {
                handleValueMap(index, result)
            }
        } else {
            val elementEncoder = ChildTableMapElementEncoder(this, index, config)
            serializer.serialize(elementEncoder, value)
        }
    }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?,
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
        // All entries written inline via writeChild()
    }
}

/**
 * Encoder for individual map key/value elements that need the Encoder interface.
 */
@OptIn(ExperimentalSerializationApi::class)
private class ChildTableMapElementEncoder(
    private val parent: ChildTableMapEncoder,
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
            return ChildTableMapElementConverterEncoder(parent, index, it)
        }
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        throw kotlinx.serialization.SerializationException(
            "ChildTableMapElementEncoder does not support nested structures for individual key/value primitives"
        )
    }
}

/**
 * Encoder for map key/value elements with a value converter.
 */
@OptIn(ExperimentalSerializationApi::class)
private class ChildTableMapElementConverterEncoder(
    private val parent: ChildTableMapEncoder,
    private val index: Int,
    private val converter: ValueConverter<*, *>,
) : Encoder {

    override val serializersModule: SerializersModule = kotlinx.serialization.modules.EmptySerializersModule()

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

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        throw kotlinx.serialization.SerializationException("ChildTableMapElementConverterEncoder cannot encode structures")
}

// ========== Map Decoding ==========

/**
 * Decodes map entries from pre-loaded ChildRows. Even indices serve keys (from ChildRow.key),
 * odd indices serve values (from ChildRow.values).
 */
@OptIn(ExperimentalSerializationApi::class)
private class ChildTableMapDecoder(
    private val rows: List<ChildRow>,
    private val keyDescriptor: SerialDescriptor,
    private val valueDescriptor: SerialDescriptor,
    private val config: MapFormatConfig,
) : CompositeDecoder {

    override val serializersModule: SerializersModule = config.serializersModule

    private var currentIndex = 0

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return if (currentIndex < rows.size * 2) currentIndex++ else CompositeDecoder.DECODE_DONE
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = rows.size

    private fun getKeyValue(index: Int): Any? {
        val row = rows[index / 2]
        return row.key?.get("key")
    }

    private fun getValueValue(index: Int): Any? {
        val row = rows[index / 2]
        return row.values["value"]
    }

    private fun getRawValue(index: Int): Any? {
        return if (index % 2 == 0) getKeyValue(index) else getValueValue(index)
    }

    private fun getKeyMap(index: Int): Map<String, Any?> {
        return rows[index / 2].key ?: emptyMap()
    }

    private fun getValueMap(index: Int): Map<String, Any?> {
        return rows[index / 2].values
    }

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean =
        getRawValue(index) as Boolean

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte =
        (getRawValue(index) as Number).toByte()

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short =
        (getRawValue(index) as Number).toShort()

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int =
        (getRawValue(index) as Number).toInt()

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long =
        (getRawValue(index) as Number).toLong()

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float =
        (getRawValue(index) as Number).toFloat()

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double =
        (getRawValue(index) as Number).toDouble()

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char =
        (getRawValue(index) as String).first()

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String =
        getRawValue(index) as String

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        val elemDesc = if (index % 2 == 0) keyDescriptor else valueDescriptor
        config.converters.get(elemDesc)?.let {
            return ChildTableElementConverterDecoder(getRawValue(index), it)
        }
        return ChildTableElementDecoder(getRawValue(index), config)
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        config.converters.get(deserializer.descriptor)?.let {
            @Suppress("UNCHECKED_CAST")
            return config.converters.fromDatabase(deserializer.descriptor, getRawValue(index) as Any)
        }

        if (deserializer.descriptor.kind == StructureKind.CLASS && !deserializer.descriptor.isInline) {
            val dataMap = if (index % 2 == 0) getKeyMap(index) else getValueMap(index)
            val source = SimpleReadSource(dataMap, emptyMap(), config.fieldSeparator)
            return MapDecoder(config, source).decodeSerializableValue(deserializer)
        }

        val decoder = ChildTableElementDecoder(getRawValue(index), config)
        return deserializer.deserialize(decoder)
    }

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? {
        val value = getRawValue(index)
        return if (value == null) null else decodeSerializableElement(descriptor, index, deserializer, previousValue)
    }

    override fun endStructure(descriptor: SerialDescriptor) {}
}

// ========== Shared element decoders ==========

/**
 * Decoder for a single element value (primitives, enums, inline classes).
 */
@OptIn(ExperimentalSerializationApi::class)
private class ChildTableElementDecoder(
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
            return ChildTableElementConverterDecoder(element, it)
        }
        return this
    }

    override fun decodeNotNullMark(): Boolean = element != null

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        @Suppress("UNCHECKED_CAST")
        val map = element as Map<String, Any?>
        val source = SimpleReadSource(map, emptyMap(), config.fieldSeparator)
        return MapDecoder(config, source)
    }
}

/**
 * Decoder for a single element with a value converter applied.
 */
@OptIn(ExperimentalSerializationApi::class)
private class ChildTableElementConverterDecoder(
    private val element: Any?,
    private val converter: ValueConverter<*, *>,
) : Decoder {

    override val serializersModule: SerializersModule = kotlinx.serialization.modules.EmptySerializersModule()

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

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        throw kotlinx.serialization.SerializationException("ChildTableElementConverterDecoder cannot decode structures")
}
