package com.lightningkite.services.database

/**
 * Provides efficient, low-overhead field access and mutation for objects using `kotlinx.serialization`.
 *
 * This file contains implementations of custom minimal encoders and decoders suited for extracting or setting
 * individual fields from serialized objects without a full round-trip serialization. This is useful for use cases
 * such as patching, partial updates, or performing field reads/writes based on serialization descriptors and indices,
 * enabling more advanced reflective or dynamic database usage.
 *
 * The primary components are:
 * - [MinEncoder]/[MinEncoderSI]: Lightweight encoders for capturing minimal amounts of serialization output.
 * - [MinDecoder]/[MinDecoderB]: Minimal decoders for interpreting serialized field structures or values.
 * - Extension functions ([get], [set], [serializationCast]) for field value extraction, mutation, and casting using serializers.
 *
 * Intended for internal use where advanced reflection-like abilities are required using serialization modules.
 */

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * A minimal implementation of [AbstractEncoder] that encodes either a single value or a structure into a list of values.
 *
 * Used for extracting or capturing the serialization of a specific structure or value
 * without the overhead of a full serialization process.
 */
@OptIn(ExperimentalSerializationApi::class)
private class MinEncoder(
    override val serializersModule: SerializersModule,
) : AbstractEncoder() {
    var output: Any? = null
    val stack = ArrayList<ArrayList<Any?>>(4)
    override fun encodeValue(value: Any) {
        stack.lastOrNull()?.add(value) ?: run {
            output = value
        }
    }
    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        encodeValue(value as Any)
    }
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        stack.add(ArrayList(descriptor.elementsCount))
        return this
    }
    override fun endStructure(descriptor: SerialDescriptor) {
        encodeValue(stack.removeAt(stack.lastIndex))
//        encodeValue(stack.removeLast())  // TODO: we can't do this because Java 21 compilation is being stupid
    }
    override fun encodeNull() {
        stack.lastOrNull()?.add(null) ?: run {
            output = null
        }
    }
}

/**
 * A minimal [AbstractDecoder] for single values or top-level elements.
 *
 * The reverse of [MinEncoder].
 */
@OptIn(ExperimentalSerializationApi::class)
private class MinDecoder(override val serializersModule: SerializersModule, var item: Any?) : AbstractDecoder() {
    override fun decodeNotNullMark(): Boolean = item != null
    override fun decodeValue(): Any = item!!
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T = decodeValue() as T
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = throw IllegalStateException("Use MinDecoderB")
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = when(descriptor.kind) {
        StructureKind.CLASS, StructureKind.OBJECT -> MinDecoderB(serializersModule, item as List<*>)
        StructureKind.MAP -> this
        StructureKind.LIST -> this
        else -> TODO()
    }
}

/**
 * Decoder for structs.
 *
 * Advances through the list sequentially as elements are decoded.
 */
@OptIn(ExperimentalSerializationApi::class)
private class MinDecoderB(override val serializersModule: SerializersModule, var items: List<Any?>) : AbstractDecoder() {
    private var elementIndex = 0
    override fun decodeNotNullMark(): Boolean = items[elementIndex] != null
    override fun decodeNull(): Nothing? {
        elementIndex++
        return null
    }
    fun decode() = items[elementIndex++]
    override fun decodeValue(): Any {
        return decode()!!
    }
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T = decode() as T
    override fun decodeSequentially(): Boolean = true
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (elementIndex >= (items.size)) return CompositeDecoder.DECODE_DONE
        return elementIndex
    }
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = when(descriptor.kind) {
        StructureKind.CLASS, StructureKind.OBJECT -> MinDecoderB(serializersModule, decode() as List<*>)
        StructureKind.MAP -> MinDecoder(serializersModule, decode())
        StructureKind.LIST -> MinDecoder(serializersModule, decode())
        else -> TODO()
    }
}


/**
 * A minimal encoder used to extract a field value at a specific index from a structured object.
 *
 * This encoder initiates a structure and only encodes the field at [resultIndex], streaming it
 * into [output] for later decoding. Useful for efficient single-field extraction via serialization.
 */
@OptIn(ExperimentalSerializationApi::class)
private class MinEncoderSI(
    override val serializersModule: SerializersModule,
    val resultIndex: Int,
) : AbstractEncoder() {
    var output: Any? = null
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        return MinEncoderSI2(this, serializersModule, resultIndex)
    }

    override fun encodeValue(value: Any) {
        output = value
    }

    override fun encodeNull() {
        output = null
    }

    override fun endStructure(descriptor: SerialDescriptor) {}
}

/**
 * Secondary structure encoder for [MinEncoderSI], handling selection of the correct element by index.
 *
 * Only the field at the desired [resultIndex] is actually encoded. All others are skipped or no-oped.
 */
@OptIn(ExperimentalSerializationApi::class)
private class MinEncoderSI2(
    val parent: MinEncoderSI,
    override val serializersModule: SerializersModule,
    val resultIndex: Int,
) : AbstractEncoder() {
    val stack = ArrayList<ArrayList<Any?>>(4)
    var reallyEncode: Boolean = true
    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        return (index == resultIndex).also { reallyEncode = it }
    }
    override fun encodeValue(value: Any) {
        if(reallyEncode) {
            stack.lastOrNull()?.add(value) ?: run {
                parent.output = value
            }
        }
    }
    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        encodeValue(value as Any)
    }
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        stack.add(ArrayList(descriptor.elementsCount))
        return NoopEncoder
    }
    override fun endStructure(descriptor: SerialDescriptor) {
        stack.removeLastOrNull()?.let { encodeValue(it) }
    }
    override fun encodeNull() {
        val value = null
        if(reallyEncode) {
            stack.lastOrNull()?.add(value) ?: run {
                parent.output = value
            }
        }
    }
}

/**
 * A no-op encoder that ignores all encoding requests.
 *
 * Used for fields that should not be encoded by [MinEncoderSI2].
 */
@OptIn(ExperimentalSerializationApi::class)
private object NoopEncoder: AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()
    override fun encodeNull() {}
    override fun encodeValue(value: Any) {}
    override fun encodeNotNullMark() {}
    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {}
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = this
    override fun endStructure(descriptor: SerialDescriptor) {}
}

private val empty = EmptySerializersModule()

/**
 * Extracts the value of a single field by index from [instance] using its serializer.
 *
 * Internally, uses a minimal encoder/decoder approach to serialize just the desired field, then
 * deserialize it as [V] using [childSerializer]. Optionally, a [SerializersModule] may be given.
 *
 * @param instance The object instance to pull a field from.
 * @param index The declared property index of the field to extract.
 * @param childSerializer Serializer for the extracted field type.
 * @param module Custom SerializersModule if needed.
 * @return The extracted field value of type [V].
 */
internal fun <T, V> KSerializer<T>.get(instance: T, index: Int, childSerializer: KSerializer<V>, module: SerializersModule = empty ): V {
    val e = MinEncoderSI(module, index)
    this.serialize(e, instance)
//    println("Child: ${childSerializer.descriptor.serialName} (${childSerializer.descriptor.kind})")
    return e.output as V
//    val d = MinDecoder(module, e.output)
//    return childSerializer.deserialize(d)
}

/**
 * Produces a new instance of [T] by mutating the field at [index] to [value], efficiently via serialization.
 *
 * Serializes the current [instance], replaces the indexed field value with the provided [value], and deserializes
 * the new state back into an instance of [T]. Optionally, supplies a [SerializersModule] for more control.
 *
 * @param instance Original object to update.
 * @param index The property index to set.
 * @param childSerializer Serializer for the field value.
 * @param value The new value to set.
 * @param module Optional serialization module.
 * @return A new object with the updated field.
 */
internal fun <T, V> KSerializer<T>.set(instance: T, index: Int, childSerializer: KSerializer<V>, value: V, module: SerializersModule = empty): T {
    if (descriptor.isInline) return deserialize(MinDecoder(module, value))

    val e = MinEncoder(module)
    this.serialize(e, instance)
    val eo = e.output as ArrayList<Any?>
//    println("Before: $eo")
//    println("Before Types: ${eo.joinToString(", ", "[", "]") { it?.let { it::class.simpleName } ?: "null"}}")
    eo[index] = value
//    println("After : $eo")
//    println("After  Types: ${eo.joinToString(", ", "[", "]") { it?.let { it::class.simpleName } ?: "null"}}")
    @Suppress("UNCHECKED_CAST") val d = MinDecoder(module, e.output)
    return deserialize(d)
}

/**
 * Casts an instance's serialization representation from its serializer to another serializer of a (potentially) different type.
 *
 * Serializes the [instance] with the current serializer and then deserializes it with [otherSerializer], allowing for
 * reinterpretation between compatible types.
 *
 * @param instance The object to reinterpret.
 * @param otherSerializer The target serializer.
 * @param module Optional serialization module for context.
 * @return The instance re-interpreted as [V].
 */
internal fun <T, V> KSerializer<T>.serializationCast(instance: T, otherSerializer: KSerializer<V>, module: SerializersModule = empty ): V {
    val e = MinEncoder(module, )
    this.serialize(e, instance)
    val d = MinDecoder(module, e.output)
    return otherSerializer.deserialize(d)
}