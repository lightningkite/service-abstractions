@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.JsonNames

interface MySealedClassSerializerInterface<T : Any> : KSerializer<T> {
    val options: List<MySealedClassSerializer.Option<T, out T>>
}

class MySealedClassSerializer<T : Any>(
    serialName: String,
    options: () -> List<Option<T, out T>>,
    val annotations: List<Annotation> = listOf(),
) : MySealedClassSerializerInterface<T> {

    class Option<Base, T : Base>(
        val serializer: KSerializer<T>,
        val alternativeNames: Set<String> = setOf(),
        val annotations: List<Annotation> = listOf(),
        val isInstance: (Base) -> Boolean,
    )

    override val options by lazy(options)

    private val nameToIndex by lazy {
        this.options.flatMapIndexed { index, it ->
            (listOf(it.serializer.descriptor.serialName) + it.alternativeNames)
                .map { n -> n to index }
        }.associate { it }
    }

    private fun getIndex(item: T): Int = options.indexOfFirst { it.isInstance(item) }
        .also {
            if (it == -1)
                throw IllegalStateException("No serializer inside ${descriptor.serialName} found for ${item::class}; options are ${options.joinToString { it.serializer.descriptor.serialName }}")
        }

    fun getOption(item: T): Option<T, *> = options.find { it.isInstance(item) }
        ?: throw IllegalStateException("No serializer inside ${descriptor.serialName} found for ${item::class}")

    override val descriptor: SerialDescriptor = defer(serialName, StructureKind.CLASS) {
        buildClassSerialDescriptor(serialName) {
            this.annotations = this@MySealedClassSerializer.annotations
            for ((index, s) in this@MySealedClassSerializer.options.withIndex()) {
                element(
                    s.serializer.descriptor.serialName,
                    s.serializer.descriptor,
                    isOptional = true,
                    annotations = listOfNotNull(
                        this@MySealedClassSerializer.options[index].alternativeNames
                            .let { JsonNames(*it.toTypedArray()) }) + s.annotations
                )
            }
        }
    }

    override fun deserialize(decoder: Decoder): T {
        if (decoder is DefaultDecoder) return options.first().serializer.default()
        return decoder.decodeStructure(descriptor) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) {
                throw SerializationException("Single key expected, but received none.")
            }
            if (index == CompositeDecoder.UNKNOWN_NAME) throw SerializationException("Unknown key received.")
            val serializer = options[index].serializer
            val result = if (serializer.descriptor.kind == StructureKind.OBJECT) {
                decodeBooleanElement(descriptor, index)
                serializer.default()
            } else decodeSerializableElement(descriptor, index, serializer)
            if (decodeElementIndex(descriptor) != CompositeDecoder.DECODE_DONE) throw SerializationException("Single key expected, but received multiple.")
            result
        }
    }

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeStructure(descriptor) {
            val index = getIndex(value)

            @Suppress("UNCHECKED_CAST")
            val serializer = options[index].serializer as KSerializer<Any?>
            if (serializer.descriptor.kind == StructureKind.OBJECT) {
                encodeBooleanElement(descriptor, index, true)
            } else {
                this.encodeSerializableElement<Any?>(
                    descriptor,
                    index,
                    serializer,
                    value
                )
            }
        }
    }
}
