package com.lightningkite.services.database

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.text.get

interface SerializableProperty<A, B> {
    val name: String
    fun get(receiver: A): B
    fun setCopy(receiver: A, value: B): A
    val serializer: KSerializer<B>
    val annotations: List<Annotation> get() = listOf()
    val default: B? get() = null
    val defaultCode: String? get() = null
    val serializableAnnotations: List<SerializableAnnotation>
        get() = annotations.mapNotNull {
            SerializableAnnotation.Companion.parseOrNull(
                it
            )
        }

    @Suppress("UNCHECKED_CAST")
    @OptIn(InternalSerializationApi::class)
    class Generated<A, B>(
        val parent: GeneratedSerializer<A>,
        val index: Int,
    ) : SerializableProperty<A, B> {
        override val name: String by lazy { parent.descriptor.getElementName(index) }
        override val serializer: KSerializer<B> by lazy { parent.childSerializers()[index] as KSerializer<B> }
        override fun setCopy(receiver: A, value: B): A = parent.set(receiver, index, serializer, value)
        override fun get(receiver: A): B = (parent as KSerializer<A>).get(receiver, index, serializer)
        override val serializableAnnotations: List<SerializableAnnotation> by lazy {
            serializer.descriptor.getElementAnnotations(
                index
            ).mapNotNull { SerializableAnnotation.parseOrNull(it) }
        }
    }

    companion object {
    }

    class FromVirtualField(
        val source: VirtualField,
        val registry: SerializationRegistry,
        val context: Map<String, KSerializer<*>>
    ) : SerializableProperty<VirtualInstance, Any?> {
        override val name: String get() = source.name
        override val serializer: KSerializer<Any?> by lazy { source.type.serializer(registry, context) }
        override val annotations: List<Annotation> get() = listOf()
        override val serializableAnnotations: List<SerializableAnnotation> get() = source.annotations
        override fun get(receiver: VirtualInstance): Any? = receiver.values[source.index]
        override fun setCopy(receiver: VirtualInstance, value: Any?): VirtualInstance =
            VirtualInstance(receiver.type, receiver.values.toMutableList().also {
                it[source.index] = value
            })
    }
}

@OptIn(ExperimentalSerializationApi::class)
fun <T> KSerializer<T>.tryFindAnnotations(propertyName: String): List<Annotation> {
    val i = descriptor.getElementIndex(propertyName)
    if (i < 0) return listOf()
    else return descriptor.getElementAnnotations(i)
}

private val serNameToProperties = HashMap<String, Array<SerializableProperty<*, *>>>()

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
@Suppress("UNCHECKED_CAST")
val <T> KSerializer<T>.serializableProperties: Array<SerializableProperty<T, *>>?
    get() {
        return if (this !is GeneratedSerializer<*>) null
        else if (this.typeParametersSerializers().isEmpty()) serNameToProperties.getOrPut(this.descriptor.serialName) {
            (0..<descriptor.elementsCount).map<Int, SerializableProperty<T, *>> { index ->
                SerializableProperty.Generated<T, Any?>(
                    this as GeneratedSerializer<T>,
                    index
                )
            }.toTypedArray()
        } as Array<SerializableProperty<T, *>> else (0..<descriptor.elementsCount).map<Int, SerializableProperty<T, *>> { index ->
            SerializableProperty.Generated<T, Any?>(
                this as GeneratedSerializer<T>,
                index
            )
        }.toTypedArray()
    }
