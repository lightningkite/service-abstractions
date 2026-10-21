@file:Suppress("PackageDirectoryMismatch")
@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.data.ExperimentalLightningServer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

public class PartialSerializer<T>(public val source: KSerializer<T>) : KSerializer<Partial<T>> {
    // Claude: each field's serializer before nullable composites are wrapped in `.nullable`. The descriptor is built
    // from this, because `.nullable` reads the wrapped descriptor, which for a recursive type is still being built.
    private val unwrappedChildSerializers: List<KSerializer<*>> by lazy {
        this.source.fields?.map {
            val nonNull = if (it.serializer.descriptor.isNullable) it.serializer.nullElement()!! else it.serializer
            if (nonNull.fields != null && !nonNull.descriptor.isInline) {
                if (nonNull == source) this
                else PartialSerializer(nonNull)
            } else it.serializer
        }
            // A Partial of an unknown type carries no fields. This happens for Partial<Nothing> and, during
            // metadata/KSchema generation, for Partial<A> where A is an unsubstituted generic placeholder. In
            // both cases the partial is metadata-only (never actually serialized), so an empty field set is correct.
            ?: if (source.descriptor.serialName == "kotlin.Nothing" || source is SerializationRegistry.GenericPlaceholderSerializer) listOf()
            else throw IllegalArgumentException("Failed to make partial serializer: ${source.descriptor.serialName} has no fields")
    }
    private val childSerializers by lazy {
        unwrappedChildSerializers.mapIndexed { index, it ->
            if (it is PartialSerializer<*> && source.descriptor.getElementDescriptor(index).isNullable) it.nullable else it
        }
    }
    private val innerDescriptor: SerialDescriptor by lazy {
        try {
            val sourceDescriptor = source.descriptor
            buildClassSerialDescriptor("com.lightningkite.services.database.Partial", sourceDescriptor) {
                if (sourceDescriptor.elementsCount != unwrappedChildSerializers.size) {
                    throw IllegalStateException("Mismatch in child serializer count; ${sourceDescriptor.elementsCount} vs ${unwrappedChildSerializers.size}; ${sourceDescriptor.elementNames.joinToString()} vs ${unwrappedChildSerializers.joinToString { it.descriptor.serialName }}")
                }
                for (index in 0 until sourceDescriptor.elementsCount) {
                    val s = unwrappedChildSerializers[index]
                    if (s is PartialSerializer<*> && !sourceDescriptor.getElementDescriptor(index).isNullable) {
                        element(
                            elementName = sourceDescriptor.getElementName(index),
                            descriptor = s.descriptor,
                            annotations = sourceDescriptor.getElementAnnotations(index),
                            isOptional = true
                        )
                    } else {
                        element(
                            elementName = sourceDescriptor.getElementName(index),
                            descriptor = sourceDescriptor.getElementDescriptor(index),
                            annotations = sourceDescriptor.getElementAnnotations(index),
                            isOptional = true
                        )
                    }
                }
            }
        } catch (e: Exception) {
            throw Exception("Failed to make partial descriptor for ${source.descriptor.serialName}", e)
        }
    }
    @OptIn(ExperimentalLightningServer::class)
    override val descriptor: SerialDescriptor =
        LazySerialDescriptor("com.lightningkite.services.database.Partial", StructureKind.CLASS) { innerDescriptor }

    override fun deserialize(decoder: Decoder): Partial<T> = decoder.decodeStructure(descriptor) {
        val out = HashMap<Field<T, *>, Any?>()
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                CompositeDecoder.UNKNOWN_NAME -> continue
                else -> out[source.fields!!.find { it.name == descriptor.getElementName(index) }!!] =
                    decodeSerializableElement(descriptor, index, childSerializers[index])
            }
        }
        Partial(out)
    }

    override fun serialize(encoder: Encoder, value: Partial<T>): Unit = encoder.encodeStructure(descriptor) {
        for ((key, v) in value.parts) {
            val index = descriptor.getElementIndex(key.name)
            @Suppress("UNCHECKED_CAST")
            encodeSerializableElement(descriptor, index, childSerializers[index] as KSerializer<Any?>, v)
        }
    }
}

public fun <K> AnyOptic<K>.setMap(key: K, out: Partial<K>) {
    if (fields.isEmpty()) throw IllegalStateException("Path $this cannot be set for partial")
    @Suppress("UNCHECKED_CAST")
    var current = out as Partial<Any?>
    var value: Any? = key
    for (prop in fields.dropLast(1)) {
        @Suppress("UNCHECKED_CAST")
        value = (prop as Field<Any?, Any?>).get(value)
        if (value == null) {
            current.parts[prop] = null
            return
        }
        @Suppress("UNCHECKED_CAST")
        current = current.parts.getOrPut(prop as Field<Any?, *>) { Partial<Any?>() } as Partial<Any?>
    }
    run {
        @Suppress("UNCHECKED_CAST")
        val prop = fields.last() as Field<Any?, Any?>
        current.parts[prop] = prop.toPartialEntry(get(key))
    }
}
