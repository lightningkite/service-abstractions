// by Claude
@file:Suppress("OPT_IN_USAGE")

package com.lightningkite.services.database.mapformat

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Decoder that reads values from a map-like structure.
 *
 * Handles value classes properly via decodeInline/decodeInlineElement,
 * reads flattened embedded structs, and delegates collection handling to CollectionHandler.
 *
 * by Claude - Uses seenStack to detect self-referential types. When a type is already on
 * the stack, the field value is expected to be a JSON string and will be decoded as such.
 */
@OptIn(ExperimentalSerializationApi::class)
public class MapDecoder(
    private val config: MapFormatConfig,
    private val input: ReadSource,
    private val descriptor: SerialDescriptor,
    // by Claude - track types currently being deserialized to detect self-referential types
    private val seenStack: MutableList<String> = mutableListOf(),
) : Decoder, CompositeDecoder {

    override val serializersModule: SerializersModule = config.serializersModule

    // Tag stack for managing field paths during decoding
    private val tagStack = mutableListOf<String>()
    private var currentIndex = 0

    private fun pushTag(tag: String) {
        tagStack.add(tag)
    }

    private fun popTag(): String = tagStack.removeLastOrNull() ?: ""

    private fun currentTagOrNull(): String? = tagStack.lastOrNull()

    private fun nested(childName: String): String {
        val parent = currentTagOrNull() ?: ""
        return if (parent.isEmpty()) childName else "$parent${config.fieldSeparator}$childName"
    }

    internal fun copyTagsFrom(other: MapDecoder) {
        tagStack.addAll(other.tagStack)
        // by Claude - also copy seenStack to propagate self-referential type tracking
        seenStack.addAll(other.seenStack)
    }

    // ========== Value Class (Inline) Support ==========

    override fun decodeInline(descriptor: SerialDescriptor): Decoder {
        // Check for custom converter on the value class
        config.converters.get(descriptor)?.let {
            return ConverterDecoder(popTag(), it, input)
        }
        // For inline classes, the tag stays on the stack for the underlying value
        return this
    }

    // ========== Primitives ==========

    override fun decodeBoolean(): Boolean = input.readField(popTag()) as Boolean
    override fun decodeByte(): Byte = (input.readField(popTag()) as Number).toByte()
    override fun decodeShort(): Short = (input.readField(popTag()) as Number).toShort()
    override fun decodeInt(): Int = (input.readField(popTag()) as Number).toInt()
    override fun decodeLong(): Long = (input.readField(popTag()) as Number).toLong()
    override fun decodeFloat(): Float = (input.readField(popTag()) as Number).toFloat()
    override fun decodeDouble(): Double = (input.readField(popTag()) as Number).toDouble()
    override fun decodeChar(): Char = (input.readField(popTag()) as String).first()
    override fun decodeString(): String = input.readField(popTag()) as String

    override fun decodeNull(): Nothing? {
        popTag()
        return null
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val name = input.readField(popTag()) as String
        return enumDescriptor.getElementIndex(name)
    }

    override fun decodeNotNullMark(): Boolean {
        val path = currentTagOrNull() ?: ""
        val existsPath = "$path${config.fieldSeparator}exists"
        return when {
            // Explicit exists marker (for nullable embedded classes)
            input.hasField(existsPath) -> input.readField(existsPath) as Boolean
            // Direct value is non-null
            input.readField(path) != null -> true
            // No direct value, but has nested fields (embedded class without exists marker)
            input.hasNestedFields(path) -> true
            else -> false
        }
    }

    // ========== Serializable Values ==========

    // by Claude - helper to decode a value from JSON text (used for self-referential/polymorphic types)
    private fun <T> decodeFromJson(deserializer: DeserializationStrategy<T>): T {
        val jsonString = input.readField(popTag()) as String
        val json = Json {
            serializersModule = config.serializersModule
            ignoreUnknownKeys = true
        }
        @Suppress("UNCHECKED_CAST")
        return json.decodeFromString(deserializer as kotlinx.serialization.KSerializer<T>, jsonString)
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        // Check for custom converter
        config.converters.get(deserializer.descriptor)?.let { _ ->
            val dbValue = input.readField(popTag())
            @Suppress("UNCHECKED_CAST")
            return config.converters.fromDatabase(deserializer.descriptor, dbValue as Any)
        }

        val serialName = deserializer.descriptor.serialName

        // by Claude - detect self-referential types (types that contain instances of themselves)
        // If we've already seen this type on the stack, it was encoded as JSON
        if (deserializer.descriptor.kind == StructureKind.CLASS && seenStack.contains(serialName)) {
            return decodeFromJson(deserializer)
        }

        // by Claude - polymorphic types (sealed/open classes) are stored as JSON strings
        if (deserializer.descriptor.kind == PolymorphicKind.SEALED ||
            deserializer.descriptor.kind == PolymorphicKind.OPEN
        ) {
            return decodeFromJson(deserializer)
        }

        // by Claude - track CLASS types on the seenStack to detect self-referential nesting
        if (deserializer.descriptor.kind == StructureKind.CLASS) {
            seenStack.add(serialName)
        }

        val result = deserializer.deserialize(this)

        // by Claude - remove from seenStack after deserializing
        if (deserializer.descriptor.kind == StructureKind.CLASS) {
            seenStack.removeLastOrNull()
        }

        return result
    }

    // ========== Structures ==========

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                // Create a new decoder for nested structures, copying the tag stack
                MapDecoder(config, input, descriptor).also { it.copyTagsFrom(this) }
            }

            StructureKind.LIST -> {
                val path = popTag()
                val elementDesc = descriptor.getElementDescriptor(0)
                config.collectionHandler.createListDecoder(path, elementDesc, input)
            }

            StructureKind.MAP -> {
                val path = popTag()
                val keyDesc = descriptor.getElementDescriptor(0)
                val valueDesc = descriptor.getElementDescriptor(1)
                config.collectionHandler.createMapDecoder(path, keyDesc, valueDesc, input)
            }

            else -> this
        }
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (currentIndex < descriptor.elementsCount) {
            val index = currentIndex++
            val name = nested(descriptor.getElementName(index))
            val elemDesc = descriptor.getElementDescriptor(index)

            // Check if we have data for this field
            val hasData = when {
                // Always try to decode non-optional fields
                !descriptor.isElementOptional(index) -> true
                // Check for embedded class "exists" marker
                elemDesc.kind == StructureKind.CLASS && !elemDesc.isInline -> {
                    input.hasField("$name${config.fieldSeparator}exists") || input.hasField(name)
                }
                // For collections, check if the field exists
                elemDesc.kind == StructureKind.LIST || elemDesc.kind == StructureKind.MAP -> {
                    input.hasField(name)
                }
                // For other fields, check if data exists
                else -> input.hasField(name)
            }

            if (hasData) {
                return index
            }
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        // Nothing to do
    }

    // ========== Element Decoding (CompositeDecoder) ==========

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean =
        input.readField(nested(descriptor.getElementName(index))) as Boolean

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte =
        (input.readField(nested(descriptor.getElementName(index))) as Number).toByte()

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short =
        (input.readField(nested(descriptor.getElementName(index))) as Number).toShort()

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int =
        (input.readField(nested(descriptor.getElementName(index))) as Number).toInt()

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long =
        (input.readField(nested(descriptor.getElementName(index))) as Number).toLong()

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float =
        (input.readField(nested(descriptor.getElementName(index))) as Number).toFloat()

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double =
        (input.readField(nested(descriptor.getElementName(index))) as Number).toDouble()

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char =
        (input.readField(nested(descriptor.getElementName(index))) as String).first()

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String =
        input.readField(nested(descriptor.getElementName(index))) as String

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        val path = nested(descriptor.getElementName(index))
        val elementDesc = descriptor.getElementDescriptor(index)

        // Check for custom converter on the inline type
        config.converters.get(elementDesc)?.let {
            return ConverterDecoder(path, it, input)
        }

        // Push the path and return self - the inline's underlying value will use this path
        pushTag(path)
        return this
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        val elemDesc = descriptor.getElementDescriptor(index)
        pushTag(nested(descriptor.getElementName(index)))
        val result = decodeSerializableValue(deserializer)
        // For CLASS/OBJECT types, the tag was used by the child decoder but not popped from parent
        // For primitives/converters, the tag was already popped
        if (elemDesc.kind == StructureKind.CLASS || elemDesc.kind == StructureKind.OBJECT ||
            elemDesc.kind == StructureKind.LIST || elemDesc.kind == StructureKind.MAP) {
            tagStack.removeLastOrNull()
        }
        return result
    }

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?
    ): T? {
        val elemDesc = descriptor.getElementDescriptor(index)
        pushTag(nested(descriptor.getElementName(index)))
        val result = if (decodeNotNullMark()) decodeSerializableValue(deserializer) else decodeNull()
        // For CLASS/OBJECT types and collections, pop the unused tag
        if (elemDesc.kind == StructureKind.CLASS || elemDesc.kind == StructureKind.OBJECT ||
            elemDesc.kind == StructureKind.LIST || elemDesc.kind == StructureKind.MAP) {
            tagStack.removeLastOrNull()
        }
        return result
    }
}

/**
 * Decoder that applies a value converter for custom types.
 */
internal class ConverterDecoder(
    private val path: String,
    private val converter: ValueConverter<*, *>,
    private val input: ReadSource,
) : Decoder {

    override val serializersModule: SerializersModule = EmptySerializersModule()

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(): T {
        val dbValue = input.readField(path)
        val conv = converter as ValueConverter<T, Any>
        return conv.fromDatabase(dbValue as Any)
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
    @ExperimentalSerializationApi
    override fun decodeNull(): Nothing? = null

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val name: String = read()
        return enumDescriptor.getElementIndex(name)
    }

    override fun decodeInline(descriptor: SerialDescriptor): Decoder = this
    @ExperimentalSerializationApi
    override fun decodeNotNullMark(): Boolean = input.readField(path) != null

    @OptIn(ExperimentalSerializationApi::class)
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        throw SerializationException("ConverterDecoder cannot decode structures")
}
