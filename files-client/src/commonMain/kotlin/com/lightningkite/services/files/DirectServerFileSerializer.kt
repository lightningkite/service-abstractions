package com.lightningkite.services.files

import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

public object DirectServerFileSerializer : KSerializer<ServerFile> {
    //Description("A URL referencing a file that the server owns.")
    @OptIn(ExperimentalSerializationApi::class, SealedSerializationApi::class)
    override val descriptor: SerialDescriptor = object : SerialDescriptor {
        override val kind: SerialKind = PrimitiveKind.STRING
        override val serialName: String = "com.lightningkite.services.files.ServerFile"
        override val elementsCount: Int get() = 0
        override fun getElementName(index: Int): String = error()
        override fun getElementIndex(name: String): Int = error()
        override fun isElementOptional(index: Int): Boolean = error()
        override fun getElementDescriptor(index: Int): SerialDescriptor = error()
        override fun getElementAnnotations(index: Int): List<Annotation> = error()
        override fun toString(): String = "PrimitiveDescriptor($serialName)"
        private fun error(): Nothing = throw IllegalStateException("Primitive descriptor does not have elements")
        override val annotations: List<Annotation> = listOf()
    }

    override fun serialize(encoder: Encoder, value: ServerFile) {
        encoder.encodeString(value.location)
    }

    override fun deserialize(decoder: Decoder): ServerFile {
        return ServerFile(decoder.decodeString())
    }
}

@OptIn(ExperimentalSerializationApi::class)
public object DeferToContextualServerFileSerializer : KSerializer<ServerFile> {
    private val c = ContextualSerializer<ServerFile>(ServerFile::class, DirectServerFileSerializer, arrayOf())

    //    override val descriptor: SerialDescriptor = SerialDescriptor("com.lightningkite.services.files.ServerFile", c.descriptor)
    @OptIn(SealedSerializationApi::class)
    override val descriptor: SerialDescriptor = object : SerialDescriptor {
        override val serialName: String get() = "com.lightningkite.services.files.ServerFile"
        override val kind: SerialKind get() = StructureKind.CLASS
        override val elementsCount: Int = 1
        override fun getElementName(index: Int): String = "contextual"
        override fun getElementIndex(name: String): Int = if (name == "contextual") 0 else -1
        override fun getElementAnnotations(index: Int): List<Annotation> = listOf()
        override fun getElementDescriptor(index: Int): SerialDescriptor = c.descriptor
        override fun isElementOptional(index: Int): Boolean = false
        override val isInline: Boolean get() = true
    }

    override fun deserialize(decoder: Decoder): ServerFile = decoder.decodeInline(descriptor).decodeSerializableValue(c)
    override fun serialize(
        encoder: Encoder,
        value: ServerFile
    ): Unit = encoder.encodeInline(descriptor).encodeSerializableValue(c, value)
}
