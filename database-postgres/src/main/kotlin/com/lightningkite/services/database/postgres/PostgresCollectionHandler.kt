// by Claude
@file:Suppress("OPT_IN_USAGE")

package com.lightningkite.services.database.postgres

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
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Collection handler for Postgres that uses Structure of Arrays (SOA) pattern.
 *
 * Lists are stored as parallel arrays - one array column per leaf field path.
 * For example, List<Person> with Person(name: String, age: Int) becomes:
 *   - name: TEXT[]
 *   - age: INT[]
 *
 * Maps are stored as key/value parallel arrays with "__value" suffix.
 * For example, Map<String, Int> becomes:
 *   - field: TEXT[]
 *   - field__value: INT[]
 *
 * @param configProvider Lazy provider for the MapFormat configuration (allows self-referential config) - by Claude
 * @param leafPathsResolver Function to enumerate leaf field paths for a descriptor
 */
internal class PostgresCollectionHandler(
    private val configProvider: () -> MapFormatConfig,
    private val leafPathsResolver: (SerialDescriptor) -> List<String>,
) : CollectionHandler {
    private val config: MapFormatConfig get() = configProvider()

    override fun createListEncoder(
        fieldPath: String,
        elementDescriptor: SerialDescriptor,
        output: WriteTarget,
    ): CompositeEncoder {
        return PostgresListEncoder(fieldPath, elementDescriptor, config, output, leafPathsResolver)
    }

    override fun createListDecoder(
        fieldPath: String,
        elementDescriptor: SerialDescriptor,
        input: ReadSource,
    ): CompositeDecoder {
        return PostgresListDecoder(fieldPath, elementDescriptor, config, input, leafPathsResolver)
    }

    override fun createMapEncoder(
        fieldPath: String,
        keyDescriptor: SerialDescriptor,
        valueDescriptor: SerialDescriptor,
        output: WriteTarget,
    ): CompositeEncoder {
        return PostgresMapEncoder(fieldPath, keyDescriptor, valueDescriptor, config, output, leafPathsResolver)
    }

    override fun createMapDecoder(
        fieldPath: String,
        keyDescriptor: SerialDescriptor,
        valueDescriptor: SerialDescriptor,
        input: ReadSource,
    ): CompositeDecoder {
        return PostgresMapDecoder(fieldPath, keyDescriptor, valueDescriptor, config, input, leafPathsResolver)
    }
}

/**
 * Encoder for lists using SOA pattern.
 * Creates parallel ArrayLists for each leaf path in the element type.
 */
@OptIn(ExperimentalSerializationApi::class)
private class PostgresListEncoder(
    private val fieldPath: String,
    private val elementDescriptor: SerialDescriptor,
    private val config: MapFormatConfig,
    private val output: WriteTarget,
    private val leafPathsResolver: (SerialDescriptor) -> List<String>,
) : CompositeEncoder {

    override val serializersModule: SerializersModule = config.serializersModule

    // Parallel arrays - one per leaf path
    private val arrays: Map<String, MutableList<Any?>>

    init {
        val paths = leafPathsResolver(elementDescriptor)
        arrays = paths.associateWith { path ->
            val fullPath = composePath(fieldPath, path)
            val list = ArrayList<Any?>()
            output.writeField(fullPath, list)
            list
        }
    }

    private fun composePath(parent: String, child: String): String =
        if (parent.isEmpty()) child
        else if (child.isEmpty()) parent
        else "$parent${config.fieldSeparator}$child"

    // For primitives, there's a single array with empty path key
    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
        arrays[""]?.add(value)
    }

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
        arrays[""]?.add(value)
    }

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
        arrays[""]?.add(value)
    }

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
        arrays[""]?.add(value)
    }

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
        arrays[""]?.add(value)
    }

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
        arrays[""]?.add(value)
    }

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
        arrays[""]?.add(value)
    }

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
        arrays[""]?.add(value.toString())
    }

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
        arrays[""]?.add(value)
    }

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
        config.converters.get(elementDescriptor)?.let {
            return PostgresListElementConverterEncoder(arrays, it)
        }
        return PostgresListElementEncoder(arrays, config)
    }

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        // Check for converter
        config.converters.get(serializer.descriptor)?.let {
            arrays[""]?.add(config.converters.toDatabase(serializer.descriptor, value as Any))
            return
        }

        // For complex types (including nested collections), encode to nested map and distribute - by Claude
        if (serializer.descriptor.kind in setOf(StructureKind.CLASS, StructureKind.LIST, StructureKind.MAP)) {
            val nestedTarget = SimpleWriteTarget()
            val nestedEncoder = MapEncoder(config, nestedTarget)
            serializer.serialize(nestedEncoder, value)
            val nestedMap = nestedTarget.result().mainRecord

            // Distribute values to parallel arrays
            for ((path, arr) in arrays) {
                arr.add(nestedMap[path])
            }
        } else {
            // For primitives wrapped in serializers
            val elementEncoder = PostgresListElementEncoder(arrays, config)
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
            // Add null to all arrays
            for (arr in arrays.values) {
                arr.add(null)
            }
        } else {
            encodeSerializableElement(descriptor, index, serializer, value)
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        // Arrays were already written via output.writeField in init
    }
}

/**
 * Simple encoder for list elements that adds to parallel arrays.
 */
@OptIn(ExperimentalSerializationApi::class)
private class PostgresListElementEncoder(
    private val arrays: Map<String, MutableList<Any?>>,
    private val config: MapFormatConfig,
) : Encoder {

    override val serializersModule: SerializersModule = config.serializersModule

    override fun encodeBoolean(value: Boolean) { arrays[""]?.add(value) }
    override fun encodeByte(value: Byte) { arrays[""]?.add(value) }
    override fun encodeShort(value: Short) { arrays[""]?.add(value) }
    override fun encodeInt(value: Int) { arrays[""]?.add(value) }
    override fun encodeLong(value: Long) { arrays[""]?.add(value) }
    override fun encodeFloat(value: Float) { arrays[""]?.add(value) }
    override fun encodeDouble(value: Double) { arrays[""]?.add(value) }
    override fun encodeChar(value: Char) { arrays[""]?.add(value.toString()) }
    override fun encodeString(value: String) { arrays[""]?.add(value) }
    override fun encodeNull() { arrays[""]?.add(null) }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        arrays[""]?.add(enumDescriptor.getElementName(index))
    }

    override fun encodeInline(descriptor: SerialDescriptor): Encoder {
        config.converters.get(descriptor)?.let {
            return PostgresListElementConverterEncoder(arrays, it)
        }
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        throw UnsupportedOperationException("Nested structures in list elements should go through encodeSerializableElement")
    }
}

/**
 * Encoder for list elements with value converter.
 */
private class PostgresListElementConverterEncoder(
    private val arrays: Map<String, MutableList<Any?>>,
    private val converter: ValueConverter<*, *>,
) : Encoder {

    override val serializersModule: SerializersModule = EmptySerializersModule()

    @Suppress("UNCHECKED_CAST")
    private fun <T> write(value: T) {
        val conv = converter as ValueConverter<T, Any>
        arrays[""]?.add(conv.toDatabase(value))
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
    override fun encodeNull() { arrays[""]?.add(null) }
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
        write(enumDescriptor.getElementName(index))
    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    @OptIn(ExperimentalSerializationApi::class)
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        throw UnsupportedOperationException("Converter cannot encode structures")
}

/**
 * Decoder for lists using SOA pattern.
 * Reads from parallel arrays, reconstructing elements by index.
 */
@OptIn(ExperimentalSerializationApi::class)
private class PostgresListDecoder(
    private val fieldPath: String,
    private val elementDescriptor: SerialDescriptor,
    private val config: MapFormatConfig,
    private val input: ReadSource,
    private val leafPathsResolver: (SerialDescriptor) -> List<String>,
) : CompositeDecoder {

    override val serializersModule: SerializersModule = config.serializersModule

    private val paths = leafPathsResolver(elementDescriptor)
    private val size: Int
    private var currentIndex = 0

    init {
        // Determine size from first array
        val firstPath = composePath(fieldPath, paths.firstOrNull() ?: "")
        val firstArray = input.readField(firstPath) as? List<*>
        size = firstArray?.size ?: 0
    }

    private fun composePath(parent: String, child: String): String =
        if (parent.isEmpty()) child
        else if (child.isEmpty()) parent
        else "$parent${config.fieldSeparator}$child"

    private fun getValueAt(path: String, index: Int): Any? {
        val fullPath = composePath(fieldPath, path)
        val array = input.readField(fullPath) as? List<*> ?: return null
        return array.getOrNull(index)
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return if (currentIndex < size) currentIndex++ else CompositeDecoder.DECODE_DONE
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = size

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean =
        getValueAt("", index) as Boolean

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte =
        (getValueAt("", index) as Number).toByte()

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short =
        (getValueAt("", index) as Number).toShort()

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int =
        (getValueAt("", index) as Number).toInt()

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long =
        (getValueAt("", index) as Number).toLong()

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float =
        (getValueAt("", index) as Number).toFloat()

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double =
        (getValueAt("", index) as Number).toDouble()

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char =
        (getValueAt("", index) as String).first()

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String =
        getValueAt("", index) as String

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        config.converters.get(elementDescriptor)?.let {
            return PostgresListElementConverterDecoder(getValueAt("", index), it)
        }
        // Return a primitive decoder for inline elements - by Claude
        return PrimitiveValueDecoder(getValueAt("", index), config.serializersModule)
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        // Check for converter
        config.converters.get(deserializer.descriptor)?.let {
            @Suppress("UNCHECKED_CAST")
            return config.converters.fromDatabase(deserializer.descriptor, getValueAt("", index) as Any)
        }

        // For complex types (including nested collections), reconstruct from parallel arrays - by Claude
        if (deserializer.descriptor.kind in setOf(StructureKind.CLASS, StructureKind.LIST, StructureKind.MAP)) {
            val reconstructedMap = paths.associateWith { path ->
                getValueAt(path, index)
            }
            val source = SimpleReadSource(reconstructedMap, emptyMap(), config.fieldSeparator)
            return MapDecoder(config, source, deserializer.descriptor).decodeSerializableValue(deserializer)
        }

        // For primitives - get value directly from array - by Claude
        val value = getValueAt("", index)
        val decoder = PrimitiveValueDecoder(value, config.serializersModule)
        return deserializer.deserialize(decoder)
    }

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?
    ): T? {
        // Check if all values at this index are null
        val firstValue = getValueAt(paths.firstOrNull() ?: "", index)
        return if (firstValue == null) null else decodeSerializableElement(descriptor, index, deserializer, previousValue)
    }

    override fun endStructure(descriptor: SerialDescriptor) {}
}

/**
 * Simple decoder for primitive values - by Claude
 */
@OptIn(ExperimentalSerializationApi::class)
private class PrimitiveValueDecoder(
    private val value: Any?,
    override val serializersModule: SerializersModule,
) : Decoder {
    override fun decodeBoolean(): Boolean = value as Boolean
    override fun decodeByte(): Byte = (value as Number).toByte()
    override fun decodeShort(): Short = (value as Number).toShort()
    override fun decodeInt(): Int = (value as Number).toInt()
    override fun decodeLong(): Long = (value as Number).toLong()
    override fun decodeFloat(): Float = (value as Number).toFloat()
    override fun decodeDouble(): Double = (value as Number).toDouble()
    override fun decodeChar(): Char = (value as String).first()
    override fun decodeString(): String = value as String
    override fun decodeNull(): Nothing? = null
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val name = value as String
        return enumDescriptor.getElementIndex(name)
    }
    override fun decodeInline(descriptor: SerialDescriptor): Decoder = this
    override fun decodeNotNullMark(): Boolean = value != null
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        throw UnsupportedOperationException("PrimitiveValueDecoder cannot decode structures")
}

/**
 * Decoder for list elements with value converter.
 */
private class PostgresListElementConverterDecoder(
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
        throw UnsupportedOperationException("Converter cannot decode structures")
}

/**
 * Encoder for maps using SOA pattern.
 * Keys and values are stored as parallel arrays, with values using "__value" suffix.
 */
@OptIn(ExperimentalSerializationApi::class)
private class PostgresMapEncoder(
    private val fieldPath: String,
    private val keyDescriptor: SerialDescriptor,
    private val valueDescriptor: SerialDescriptor,
    private val config: MapFormatConfig,
    private val output: WriteTarget,
    private val leafPathsResolver: (SerialDescriptor) -> List<String>,
) : CompositeEncoder {

    override val serializersModule: SerializersModule = config.serializersModule

    // Parallel arrays for keys
    private val keyArrays: Map<String, MutableList<Any?>>
    // Parallel arrays for values (with "__value" suffix)
    private val valueArrays: Map<String, MutableList<Any?>>
    private var isKey = true

    init {
        val keyPaths = leafPathsResolver(keyDescriptor)
        keyArrays = keyPaths.associateWith { path ->
            val fullPath = composePath(fieldPath, path)
            val list = ArrayList<Any?>()
            output.writeField(fullPath, list)
            list
        }

        val valuePaths = leafPathsResolver(valueDescriptor)
        valueArrays = valuePaths.associateWith { path ->
            val fullPath = composePath(composePath(fieldPath, path), "value")
            val list = ArrayList<Any?>()
            output.writeField(fullPath, list)
            list
        }
    }

    private fun composePath(parent: String, child: String): String =
        if (parent.isEmpty()) child
        else if (child.isEmpty()) parent
        else "$parent${config.fieldSeparator}$child"

    private fun currentArrays(): Map<String, MutableList<Any?>> =
        if (isKey) keyArrays else valueArrays

    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
        currentArrays()[""]?.add(value)
        isKey = !isKey
    }

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
        currentArrays()[""]?.add(value)
        isKey = !isKey
    }

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
        currentArrays()[""]?.add(value)
        isKey = !isKey
    }

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
        currentArrays()[""]?.add(value)
        isKey = !isKey
    }

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
        currentArrays()[""]?.add(value)
        isKey = !isKey
    }

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
        currentArrays()[""]?.add(value)
        isKey = !isKey
    }

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
        currentArrays()[""]?.add(value)
        isKey = !isKey
    }

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
        currentArrays()[""]?.add(value.toString())
        isKey = !isKey
    }

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
        currentArrays()[""]?.add(value)
        isKey = !isKey
    }

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
        val elemDesc = if (isKey) keyDescriptor else valueDescriptor
        val arrays = currentArrays()
        isKey = !isKey
        config.converters.get(elemDesc)?.let {
            return PostgresMapElementConverterEncoder(arrays, it)
        }
        return PostgresMapElementEncoder(arrays, config)
    }

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        val arrays = currentArrays()
        isKey = !isKey

        // Check for converter
        config.converters.get(serializer.descriptor)?.let {
            arrays[""]?.add(config.converters.toDatabase(serializer.descriptor, value as Any))
            return
        }

        // For complex types (including nested collections) - by Claude
        if (serializer.descriptor.kind in setOf(StructureKind.CLASS, StructureKind.LIST, StructureKind.MAP)) {
            val nestedTarget = SimpleWriteTarget()
            val nestedEncoder = MapEncoder(config, nestedTarget)
            serializer.serialize(nestedEncoder, value)
            val nestedMap = nestedTarget.result().mainRecord

            for ((path, arr) in arrays) {
                arr.add(nestedMap[path])
            }
        } else {
            val elementEncoder = PostgresMapElementEncoder(arrays, config)
            serializer.serialize(elementEncoder, value)
        }
    }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?
    ) {
        val arrays = currentArrays()
        isKey = !isKey

        if (value == null) {
            for (arr in arrays.values) {
                arr.add(null)
            }
        } else {
            isKey = !isKey // Undo the flip, encodeSerializableElement will flip again
            encodeSerializableElement(descriptor, index, serializer, value)
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        // Arrays were already written via output.writeField in init
    }
}

/**
 * Simple encoder for map elements.
 */
@OptIn(ExperimentalSerializationApi::class)
private class PostgresMapElementEncoder(
    private val arrays: Map<String, MutableList<Any?>>,
    private val config: MapFormatConfig,
) : Encoder {

    override val serializersModule: SerializersModule = config.serializersModule

    override fun encodeBoolean(value: Boolean) { arrays[""]?.add(value) }
    override fun encodeByte(value: Byte) { arrays[""]?.add(value) }
    override fun encodeShort(value: Short) { arrays[""]?.add(value) }
    override fun encodeInt(value: Int) { arrays[""]?.add(value) }
    override fun encodeLong(value: Long) { arrays[""]?.add(value) }
    override fun encodeFloat(value: Float) { arrays[""]?.add(value) }
    override fun encodeDouble(value: Double) { arrays[""]?.add(value) }
    override fun encodeChar(value: Char) { arrays[""]?.add(value.toString()) }
    override fun encodeString(value: String) { arrays[""]?.add(value) }
    override fun encodeNull() { arrays[""]?.add(null) }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        arrays[""]?.add(enumDescriptor.getElementName(index))
    }

    override fun encodeInline(descriptor: SerialDescriptor): Encoder {
        config.converters.get(descriptor)?.let {
            return PostgresMapElementConverterEncoder(arrays, it)
        }
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        throw UnsupportedOperationException("Nested structures should go through encodeSerializableElement")
}

/**
 * Encoder for map elements with value converter.
 */
private class PostgresMapElementConverterEncoder(
    private val arrays: Map<String, MutableList<Any?>>,
    private val converter: ValueConverter<*, *>,
) : Encoder {

    override val serializersModule: SerializersModule = EmptySerializersModule()

    @Suppress("UNCHECKED_CAST")
    private fun <T> write(value: T) {
        val conv = converter as ValueConverter<T, Any>
        arrays[""]?.add(conv.toDatabase(value))
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
    override fun encodeNull() { arrays[""]?.add(null) }
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
        write(enumDescriptor.getElementName(index))
    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    @OptIn(ExperimentalSerializationApi::class)
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        throw UnsupportedOperationException("Converter cannot encode structures")
}

/**
 * Decoder for maps using SOA pattern.
 */
@OptIn(ExperimentalSerializationApi::class)
private class PostgresMapDecoder(
    private val fieldPath: String,
    private val keyDescriptor: SerialDescriptor,
    private val valueDescriptor: SerialDescriptor,
    private val config: MapFormatConfig,
    private val input: ReadSource,
    private val leafPathsResolver: (SerialDescriptor) -> List<String>,
) : CompositeDecoder {

    override val serializersModule: SerializersModule = config.serializersModule

    private val keyPaths = leafPathsResolver(keyDescriptor)
    private val valuePaths = leafPathsResolver(valueDescriptor)
    private val size: Int
    private var currentIndex = 0 // Counts key-value pairs * 2

    init {
        val firstKeyPath = composePath(fieldPath, keyPaths.firstOrNull() ?: "")
        val firstArray = input.readField(firstKeyPath) as? List<*>
        size = (firstArray?.size ?: 0) * 2 // *2 because map encodes key/value pairs
    }

    private fun composePath(parent: String, child: String): String =
        if (parent.isEmpty()) child
        else if (child.isEmpty()) parent
        else "$parent${config.fieldSeparator}$child"

    private fun getKeyValueAt(path: String, pairIndex: Int): Any? {
        val fullPath = composePath(fieldPath, path)
        val array = input.readField(fullPath) as? List<*> ?: return null
        return array.getOrNull(pairIndex)
    }

    private fun getValueValueAt(path: String, pairIndex: Int): Any? {
        val fullPath = composePath(composePath(fieldPath, path), "value")
        val array = input.readField(fullPath) as? List<*> ?: return null
        return array.getOrNull(pairIndex)
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return if (currentIndex < size) currentIndex++ else CompositeDecoder.DECODE_DONE
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = size / 2

    private fun isKey(): Boolean = currentIndex % 2 == 1 // After increment, odd = was key
    private fun pairIndex(): Int = (currentIndex - 1) / 2

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean {
        val pairIdx = index / 2
        return if (index % 2 == 0) getKeyValueAt("", pairIdx) as Boolean
        else getValueValueAt("", pairIdx) as Boolean
    }

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte {
        val pairIdx = index / 2
        val v = if (index % 2 == 0) getKeyValueAt("", pairIdx) else getValueValueAt("", pairIdx)
        return (v as Number).toByte()
    }

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short {
        val pairIdx = index / 2
        val v = if (index % 2 == 0) getKeyValueAt("", pairIdx) else getValueValueAt("", pairIdx)
        return (v as Number).toShort()
    }

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int {
        val pairIdx = index / 2
        val v = if (index % 2 == 0) getKeyValueAt("", pairIdx) else getValueValueAt("", pairIdx)
        return (v as Number).toInt()
    }

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long {
        val pairIdx = index / 2
        val v = if (index % 2 == 0) getKeyValueAt("", pairIdx) else getValueValueAt("", pairIdx)
        return (v as Number).toLong()
    }

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float {
        val pairIdx = index / 2
        val v = if (index % 2 == 0) getKeyValueAt("", pairIdx) else getValueValueAt("", pairIdx)
        return (v as Number).toFloat()
    }

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double {
        val pairIdx = index / 2
        val v = if (index % 2 == 0) getKeyValueAt("", pairIdx) else getValueValueAt("", pairIdx)
        return (v as Number).toDouble()
    }

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char {
        val pairIdx = index / 2
        val v = if (index % 2 == 0) getKeyValueAt("", pairIdx) else getValueValueAt("", pairIdx)
        return (v as String).first()
    }

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String {
        val pairIdx = index / 2
        return if (index % 2 == 0) getKeyValueAt("", pairIdx) as String
        else getValueValueAt("", pairIdx) as String
    }

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        val pairIdx = index / 2
        val isKeyElement = index % 2 == 0
        val elemDesc = if (isKeyElement) keyDescriptor else valueDescriptor
        val value = if (isKeyElement) getKeyValueAt("", pairIdx) else getValueValueAt("", pairIdx)

        config.converters.get(elemDesc)?.let {
            return PostgresListElementConverterDecoder(value, it)
        }
        return PostgresMapElementDecoder(value, config)
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        val pairIdx = index / 2
        val isKeyElement = index % 2 == 0
        val paths = if (isKeyElement) keyPaths else valuePaths

        // Check for converter
        config.converters.get(deserializer.descriptor)?.let {
            val value = if (isKeyElement) getKeyValueAt("", pairIdx) else getValueValueAt("", pairIdx)
            @Suppress("UNCHECKED_CAST")
            return config.converters.fromDatabase(deserializer.descriptor, value as Any)
        }

        // For complex types (including nested collections) - by Claude
        if (deserializer.descriptor.kind in setOf(StructureKind.CLASS, StructureKind.LIST, StructureKind.MAP)) {
            val reconstructedMap = paths.associateWith { path ->
                if (isKeyElement) getKeyValueAt(path, pairIdx) else getValueValueAt(path, pairIdx)
            }
            val source = SimpleReadSource(reconstructedMap, emptyMap(), config.fieldSeparator)
            return MapDecoder(config, source, deserializer.descriptor).decodeSerializableValue(deserializer)
        }

        // For primitives
        val value = if (isKeyElement) getKeyValueAt("", pairIdx) else getValueValueAt("", pairIdx)
        val decoder = PostgresMapElementDecoder(value, config)
        return deserializer.deserialize(decoder)
    }

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?
    ): T? {
        val pairIdx = index / 2
        val isKeyElement = index % 2 == 0
        val paths = if (isKeyElement) keyPaths else valuePaths
        val firstValue = if (isKeyElement) getKeyValueAt(paths.firstOrNull() ?: "", pairIdx)
        else getValueValueAt(paths.firstOrNull() ?: "", pairIdx)
        return if (firstValue == null) null else decodeSerializableElement(descriptor, index, deserializer, previousValue)
    }

    override fun endStructure(descriptor: SerialDescriptor) {}
}

/**
 * Simple decoder for map elements.
 */
@OptIn(ExperimentalSerializationApi::class)
private class PostgresMapElementDecoder(
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
            return PostgresListElementConverterDecoder(element, it)
        }
        return this
    }

    override fun decodeNotNullMark(): Boolean = element != null

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        throw UnsupportedOperationException("Use parent decoder for structures")
}
