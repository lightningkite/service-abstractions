@file:Suppress("OPT_IN_USAGE")

package com.lightningkite.services.database.mapformat

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.StructureKind
import com.lightningkite.services.database.isSelfReferential
import com.lightningkite.services.database.nullElement
import kotlinx.serialization.KSerializer
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
public class ChildTableCollectionHandler(
    private val configProvider: () -> MapFormatConfig,
) : CollectionHandler {

    // A child row has one column per element field, so a collection nested inside an element
    // can't get its own child table; it is stored as JSON in its column instead.
    private val config: MapFormatConfig by lazy {
        val outer = configProvider()
        MapFormatConfig(
            serializersModule = outer.serializersModule,
            converters = outer.converters,
            collectionHandler = outer.collectionHandler,
            fieldSeparator = outer.fieldSeparator,
            serializeCollectionEmbeddedAsJson = outer.serializeCollectionEmbeddedAsJson,
            collectionsAsJson = true,
        )
    }

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
    ) = encodeElement(index, serializer, value, nullable = serializer.descriptor.isNullable)

    // Collection serializers pass a nullable element's NullableSerializer here, never to
    // encodeNullableSerializableElement, so nullability comes from the element's descriptor.
    private fun <T> encodeElement(index: Int, serializer: SerializationStrategy<T>, value: T, nullable: Boolean) {
        val element = serializer.nonNull()
        val values: Map<String, Any?> = when {
            value == null -> if (element.descriptor.isStruct(config)) mapOf(EXISTS to false) else mapOf("value" to null)
            config.converters.has(element.descriptor) -> mapOf("value" to config.converters.toDatabase(element.descriptor, value as Any))
            element.descriptor.isJson(config) -> mapOf("value" to config.json.encodeToString(element, value as Any))
            element.descriptor.isStruct(config) -> {
                val subTarget = SimpleWriteTarget()
                element.serialize(MapEncoder(config, subTarget), value as Any)
                subTarget.result().mainRecord + if (nullable) mapOf(EXISTS to true) else mapOf()
            }
            else -> {
                element.serialize(ChildTableListElementEncoder(fieldPath, index, config, output), value as Any)
                return
            }
        }
        output.writeChild(fieldPath, ChildRow(index = index, key = null, values = values))
    }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?,
    ) = @Suppress("UNCHECKED_CAST") encodeElement(index, serializer as SerializationStrategy<T?>, value, nullable = true)

    override fun endStructure(descriptor: SerialDescriptor) {
        // All elements written inline via writeChild(). Also mark the field as present (even
        // when empty) so a nullable collection's decodeNotNullMark() can tell "empty" apart from
        // "null" by its marker column. Harmless if the field
        // isn't nullable: then there is no such column, and drivers ignore the value.
        output.writeField(fieldPath, true)
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
    private fun <T : Any> write(value: T) {
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
    ): T = decodeElement(index, deserializer, nullable = deserializer.descriptor.isNullable)

    @Suppress("UNCHECKED_CAST")
    private fun <T> decodeElement(index: Int, deserializer: DeserializationStrategy<T>, nullable: Boolean): T {
        val values = rows[index].values
        val element = deserializer.nonNull()
        val present = if (element.descriptor.isStruct(config)) !nullable || values[EXISTS] == true else values["value"] != null
        return when {
            !present -> null
            config.converters.has(element.descriptor) -> config.converters.fromDatabase(element.descriptor, values["value"] as Any)
            element.descriptor.isJson(config) -> config.json.decodeFromString(element, values["value"] as String)
            element.descriptor.isStruct(config) ->
                MapDecoder(config, SimpleReadSource(values, emptyMap(), config.fieldSeparator)).decodeSerializableValue(element)
            else -> element.deserialize(ChildTableElementDecoder(values["value"], config))
        } as T
    }

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? = decodeElement(index, deserializer, nullable = true)

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
            pendingKey = keyMap.mapKeys { "key__${it.key}" }
        }
    }

    private fun handleValueMap(index: Int, valueMap: Map<String, Any?>) {
        if (index % 2 == 1) {
            output.writeChild(
                fieldPath,
                ChildRow(index = entryIndex++, key = pendingKey, values = valueMap.mapKeys { "value__${it.key}" })
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
    ) = encodeElement(index, serializer, value, nullable = serializer.descriptor.isNullable)

    // As for lists, a nullable key or value arrives with its NullableSerializer.
    private fun <T> encodeElement(index: Int, serializer: SerializationStrategy<T>, value: T, nullable: Boolean) {
        val element = serializer.nonNull()
        when {
            value == null -> if (element.descriptor.isStruct(config)) handleStruct(index, mapOf("exists" to false)) else handleElement(index, null)
            config.converters.has(element.descriptor) -> handleElement(index, config.converters.toDatabase(element.descriptor, value as Any))
            element.descriptor.isJson(config) -> handleElement(index, config.json.encodeToString(element, value as Any))
            element.descriptor.isStruct(config) -> {
                val subTarget = SimpleWriteTarget()
                element.serialize(MapEncoder(config, subTarget), value as Any)
                handleStruct(index, subTarget.result().mainRecord + if (nullable) mapOf("exists" to true) else mapOf())
            }
            else -> element.serialize(ChildTableMapElementEncoder(this, index, config), value as Any)
        }
    }

    private fun handleStruct(index: Int, fields: Map<String, Any?>) {
        if (index % 2 == 0) handleKeyMap(index, fields) else handleValueMap(index, fields)
    }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?,
    ) = @Suppress("UNCHECKED_CAST") encodeElement(index, serializer as SerializationStrategy<T?>, value, nullable = true)

    internal fun addElement(index: Int, value: Any?) {
        handleElement(index, value)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        // All entries written inline via writeChild(). Also mark the field as present (even
        // when empty) so a nullable map's decodeNotNullMark() can tell "empty" apart from
        // "null" by its marker column. Harmless if the field
        // isn't nullable: then there is no such column, and drivers ignore the value.
        output.writeField(fieldPath, true)
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
    private fun <T : Any> write(value: T) {
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
        return (rows[index / 2].key ?: emptyMap()).unprefixed("key__")
    }

    private fun getValueMap(index: Int): Map<String, Any?> {
        return rows[index / 2].values.unprefixed("value__")
    }

    private fun Map<String, Any?>.unprefixed(prefix: String): Map<String, Any?> =
        entries.filter { it.key.startsWith(prefix) }.associate { it.key.removePrefix(prefix) to it.value }

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
    ): T = decodeElement(index, deserializer, nullable = deserializer.descriptor.isNullable)

    @Suppress("UNCHECKED_CAST")
    private fun <T> decodeElement(index: Int, deserializer: DeserializationStrategy<T>, nullable: Boolean): T {
        val element = deserializer.nonNull()
        val fields = if (index % 2 == 0) getKeyMap(index) else getValueMap(index)
        val present = if (element.descriptor.isStruct(config)) !nullable || fields["exists"] == true else getRawValue(index) != null
        return when {
            !present -> null
            config.converters.has(element.descriptor) -> config.converters.fromDatabase(element.descriptor, getRawValue(index) as Any)
            element.descriptor.isJson(config) -> config.json.decodeFromString(element, getRawValue(index) as String)
            element.descriptor.isStruct(config) ->
                MapDecoder(config, SimpleReadSource(fields, emptyMap(), config.fieldSeparator)).decodeSerializableValue(element)
            else -> element.deserialize(ChildTableElementDecoder(getRawValue(index), config))
        } as T
    }

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? = decodeElement(index, deserializer, nullable = true)

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
    private fun <T : Any> read(): T {
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

// The `exists` column a nullable struct element gets, named as MapEncoder names it at a value's root.
private const val EXISTS = "__exists"

// Sealed and self-referential values can't be flattened into columns; like MapEncoder does for fields, they are stored as JSON.
@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.isJson(config: MapFormatConfig): Boolean =
    kind is PolymorphicKind || (kind == StructureKind.CLASS && isSelfReferential(config.serializersModule))

@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.isStruct(config: MapFormatConfig): Boolean =
    kind == StructureKind.CLASS && !isInline && !isJson(config)

@Suppress("UNCHECKED_CAST")
private fun SerializationStrategy<*>.nonNull(): SerializationStrategy<Any> =
    ((this as KSerializer<*>).nullElement() ?: this) as SerializationStrategy<Any>

@Suppress("UNCHECKED_CAST")
private fun DeserializationStrategy<*>.nonNull(): DeserializationStrategy<Any> =
    ((this as KSerializer<*>).nullElement() ?: this) as DeserializationStrategy<Any>
