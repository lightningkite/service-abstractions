// by Claude
@file:Suppress("OPT_IN_USAGE")

package com.lightningkite.services.database.mapformat

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Encoder that writes values to a map-like structure.
 *
 * Handles value classes properly via encodeInline/encodeInlineElement,
 * flattens embedded structs, and delegates collection handling to CollectionHandler.
 *
 * by Claude - Uses seenStack to detect self-referential types (types that can contain
 * instances of themselves). When a type is already on the stack, it's serialized as JSON
 * instead of being flattened, since infinite nesting can't be represented as columns.
 */
@OptIn(ExperimentalSerializationApi::class)
public class MapEncoder(
    private val config: MapFormatConfig,
    private val output: WriteTarget,
    // by Claude - track types currently being serialized to detect self-referential types
    private val seenStack: MutableList<String> = mutableListOf(),
) : Encoder, CompositeEncoder {

    override val serializersModule: SerializersModule = config.serializersModule

    // Tag stack for managing field paths during encoding
    private val tagStack = mutableListOf<String>()

    private fun pushTag(tag: String) {
        tagStack.add(tag)
    }

    private fun popTag(): String = tagStack.removeLastOrNull() ?: ""

    private fun currentTagOrNull(): String? = tagStack.lastOrNull()

    private fun nested(childName: String): String {
        val parent = currentTagOrNull() ?: ""
        return if (parent.isEmpty()) childName else "$parent${config.fieldSeparator}$childName"
    }

    // ========== Value Class (Inline) Support ==========

    override fun encodeInline(descriptor: SerialDescriptor): Encoder {
        // Check for custom converter on the value class
        config.converters.get(descriptor)?.let {
            return ConverterEncoder(popTag(), it, output)
        }
        // For inline classes without converters, the tag stays on the stack
        // and the wrapped value will pop it
        return this
    }

    // ========== Primitives ==========

    override fun encodeBoolean(value: Boolean): Unit = output.writeField(popTag(), value)
    override fun encodeByte(value: Byte): Unit = output.writeField(popTag(), value)
    override fun encodeShort(value: Short): Unit = output.writeField(popTag(), value)
    override fun encodeInt(value: Int): Unit = output.writeField(popTag(), value)
    override fun encodeLong(value: Long): Unit = output.writeField(popTag(), value)
    override fun encodeFloat(value: Float): Unit = output.writeField(popTag(), value)
    override fun encodeDouble(value: Double): Unit = output.writeField(popTag(), value)
    override fun encodeChar(value: Char): Unit = output.writeField(popTag(), value.toString())
    override fun encodeString(value: String): Unit = output.writeField(popTag(), value)
    override fun encodeNull(): Unit = output.writeField(popTag(), null)

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        output.writeField(popTag(), enumDescriptor.getElementName(index))
    }

    // ========== Serializable Values ==========

    // by Claude - helper to serialize a value as JSON text (used for self-referential/polymorphic types)
    private fun <T> encodeAsJson(serializer: SerializationStrategy<T>, value: T) {
        val json = Json {
            serializersModule = config.serializersModule
            encodeDefaults = true
        }
        @Suppress("UNCHECKED_CAST")
        val jsonString = json.encodeToString(serializer as kotlinx.serialization.KSerializer<T>, value)
        output.writeField(popTag(), jsonString)
    }

    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        // Check for custom converter
        config.converters.get(serializer.descriptor)?.let { _ ->
            val converted = config.converters.toDatabase(serializer.descriptor, value as Any)
            output.writeField(popTag(), converted)
            return
        }

        val serialName = serializer.descriptor.serialName

        // by Claude - detect self-referential types (types that can contain instances of themselves)
        // If we've already seen this type on the stack, serialize as JSON to avoid infinite nesting
        if (serializer.descriptor.kind == StructureKind.CLASS && seenStack.contains(serialName)) {
            encodeAsJson(serializer, value)
            return
        }

        // by Claude - polymorphic types (sealed/open classes) should be serialized as JSON strings
        // because databases like Cassandra can't represent complex polymorphic type hierarchies
        if (serializer.descriptor.kind == PolymorphicKind.SEALED ||
            serializer.descriptor.kind == PolymorphicKind.OPEN
        ) {
            encodeAsJson(serializer, value)
            return
        }

        // by Claude - track CLASS types on the seenStack to detect self-referential nesting
        if (serializer.descriptor.kind == StructureKind.CLASS) {
            seenStack.add(serialName)
        }

        serializer.serialize(this, value)

        // by Claude - remove from seenStack after serializing
        if (serializer.descriptor.kind == StructureKind.CLASS) {
            seenStack.removeLastOrNull()
        }

        // For CLASS/OBJECT types, pop the tag that was pushed by encodeSerializableElement
        // (primitives and converters pop in their encode methods, but classes don't)
        if (serializer.descriptor.kind == StructureKind.CLASS || serializer.descriptor.kind == StructureKind.OBJECT) {
            popTag()
        }
    }

    override fun <T : Any> encodeNullableSerializableValue(serializer: SerializationStrategy<T>, value: T?) {
        val path = currentTagOrNull() ?: ""
        if (value == null) {
            popTag()
            when (serializer.descriptor.kind) {
                StructureKind.CLASS -> {
                    if (!serializer.descriptor.isInline) {
                        // Nullable embedded class needs "exists" marker
                        output.writeField("$path${config.fieldSeparator}exists", false)
                    } else {
                        output.writeField(path, null)
                    }
                }
                // For nullable maps, write null to both keys and values columns - by Claude
                StructureKind.MAP -> {
                    output.writeField(path, null)
                    output.writeField("$path${config.fieldSeparator}value", null)
                }
                // by Claude - polymorphic types are serialized as JSON strings (TEXT column)
                PolymorphicKind.SEALED, PolymorphicKind.OPEN -> {
                    output.writeField(path, null)
                }
                else -> {
                    output.writeField(path, null)
                }
            }
        } else {
            if (serializer.descriptor.kind == StructureKind.CLASS && !serializer.descriptor.isInline) {
                output.writeField("$path${config.fieldSeparator}exists", true)
            }
            encodeSerializableValue(serializer, value)
        }
    }

    // ========== Structures ==========

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        return when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                // For nested classes, we keep using this encoder but with the path context
                this
            }

            StructureKind.LIST -> {
                val path = popTag()
                val elementDesc = descriptor.getElementDescriptor(0)
                config.collectionHandler.createListEncoder(path, elementDesc, output)
            }

            StructureKind.MAP -> {
                val path = popTag()
                val keyDesc = descriptor.getElementDescriptor(0)
                val valueDesc = descriptor.getElementDescriptor(1)
                config.collectionHandler.createMapEncoder(path, keyDesc, valueDesc, output)
            }

            else -> this
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        // Nothing to do
    }

    // ========== Element Encoding (CompositeEncoder) ==========

    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
        output.writeField(nested(descriptor.getElementName(index)), value)
    }

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
        output.writeField(nested(descriptor.getElementName(index)), value)
    }

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
        output.writeField(nested(descriptor.getElementName(index)), value)
    }

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
        output.writeField(nested(descriptor.getElementName(index)), value)
    }

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
        output.writeField(nested(descriptor.getElementName(index)), value)
    }

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
        output.writeField(nested(descriptor.getElementName(index)), value)
    }

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
        output.writeField(nested(descriptor.getElementName(index)), value)
    }

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
        output.writeField(nested(descriptor.getElementName(index)), value.toString())
    }

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
        output.writeField(nested(descriptor.getElementName(index)), value)
    }

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
        val path = nested(descriptor.getElementName(index))
        val elementDesc = descriptor.getElementDescriptor(index)

        // Check for custom converter on the inline type
        config.converters.get(elementDesc)?.let {
            return ConverterEncoder(path, it, output)
        }

        // Push the path and return self - the inline's underlying value will use this path
        pushTag(path)
        return this
    }

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        pushTag(nested(descriptor.getElementName(index)))
        encodeSerializableValue(serializer, value)
    }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?
    ) {
        pushTag(nested(descriptor.getElementName(index)))
        encodeNullableSerializableValue(serializer, value)
    }
}

/**
 * Encoder that applies a value converter for custom types.
 */
internal class ConverterEncoder(
    private val path: String,
    private val converter: ValueConverter<*, *>,
    private val output: WriteTarget,
) : Encoder {

    override val serializersModule: SerializersModule = EmptySerializersModule()

    @Suppress("UNCHECKED_CAST")
    private fun <T> write(value: T) {
        val conv = converter as ValueConverter<T, Any>
        output.writeField(path, conv.toDatabase(value))
    }

    override fun encodeBoolean(value: Boolean): Unit = write(value)
    override fun encodeByte(value: Byte): Unit = write(value)
    override fun encodeShort(value: Short): Unit = write(value)
    override fun encodeInt(value: Int): Unit = write(value)
    override fun encodeLong(value: Long): Unit = write(value)
    override fun encodeFloat(value: Float): Unit = write(value)
    override fun encodeDouble(value: Double): Unit = write(value)
    override fun encodeChar(value: Char): Unit = write(value)
    override fun encodeString(value: String): Unit = write(value)
    override fun encodeNull(): Unit = output.writeField(path, null)

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int): Unit =
        write(enumDescriptor.getElementName(index))

    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    @OptIn(ExperimentalSerializationApi::class)
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        throw SerializationException("ConverterEncoder cannot encode structures")
}
