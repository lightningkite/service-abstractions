@file:Suppress("PackageDirectoryMismatch")
@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

@Serializable
public data class VirtualEnum(
    override val serialName: String,
    override val annotations: List<SerializableAnnotation>,
    public val options: List<VirtualEnumOption>,
) : VirtualType, KSerializer<VirtualEnumValue> {
    @Transient
    private val entries = options.map { VirtualEnumValue(this, it.index) }

    @Transient
    private val map = options.associateBy { it.name }
    override fun serializer(registry: SerializationRegistry, arguments: Array<KSerializer<*>>): KSerializer<*> = this
    override fun toString(): String = "Virtual $serialName { ${options.joinToString()} }"

    @OptIn(SealedSerializationApi::class)
    private inner class VirtualEnumDescriptor : SerialDescriptor {
        @ExperimentalSerializationApi
        override val elementsCount: Int get() = options.size

        @ExperimentalSerializationApi
        override val kind: SerialKind = SerialKind.ENUM

        @ExperimentalSerializationApi
        override val serialName: String = this@VirtualEnum.serialName

        @ExperimentalSerializationApi
        override fun getElementAnnotations(index: Int): List<Annotation> = listOf()

        @ExperimentalSerializationApi
        override fun getElementDescriptor(index: Int): SerialDescriptor = this

        @ExperimentalSerializationApi
        override fun getElementIndex(name: String): Int = map[name]?.index ?: CompositeDecoder.UNKNOWN_NAME

        @ExperimentalSerializationApi
        override fun getElementName(index: Int): String = options[index].name

        @ExperimentalSerializationApi
        override fun isElementOptional(index: Int): Boolean = false

        override fun equals(other: Any?): Boolean = other is VirtualEnumDescriptor && other.serialName == serialName
        override fun hashCode(): Int = serialName.hashCode()
    }
    @Transient
    override val descriptor: SerialDescriptor = VirtualEnumDescriptor()

    override fun deserialize(decoder: Decoder): VirtualEnumValue {
        return entries[decoder.decodeEnum(descriptor)]
    }

    override fun serialize(encoder: Encoder, value: VirtualEnumValue) {
        encoder.encodeEnum(descriptor, value.index)
    }

    public companion object {
        public val cache: HashMap<String, VirtualEnum> = HashMap()
    }
}

@Serializable
public data class VirtualEnumOption(
    val name: String,
    val annotations: List<SerializableAnnotation>,
    val index: Int,
)

public class VirtualEnumValue(
    public val enum: VirtualEnum,
    public val index: Int,
) : Comparable<VirtualEnumValue> {
    override fun compareTo(other: VirtualEnumValue): Int = index.compareTo(other.index)
    override fun toString(): String = enum.options[index].name
}
