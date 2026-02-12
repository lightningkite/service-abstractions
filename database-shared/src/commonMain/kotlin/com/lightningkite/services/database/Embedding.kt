package com.lightningkite.services.database

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlin.jvm.JvmInline

/**
 * Represents a dense vector embedding for similarity search.
 *
 * Dense embeddings have a value for every dimension. Typically produced by
 * models like OpenAI text-embedding-3-small, Ollama nomic-embed-text, etc.
 *
 * @property values The embedding vector values (one per dimension)
 */
@Serializable(EmbeddingSerializer::class)
public class Embedding(public val values: FloatArray) {
    public val dimensions: Int get() = values.size

    public companion object {
        public fun of(vararg values: Float): Embedding = Embedding(values)
        public fun fromDoubles(values: DoubleArray): Embedding =
            Embedding(FloatArray(values.size) { values[it].toFloat() })
        public fun fromList(values: List<Number>): Embedding =
            Embedding(FloatArray(values.size) { values[it].toFloat() })
    }

    override fun toString(): String = "Embedding(${values.size} dims)"

    override fun equals(other: Any?): Boolean = other is Embedding && values.contentEquals(other.values)
    override fun hashCode(): Int = values.contentHashCode()
}

// equals/hashCode for value class with array need special handling via serializer

@OptIn(InternalSerializationApi::class)
public class EmbeddingSerializer: GeneratedSerializer<Embedding> {
    private val defer = FloatArraySerializer()
    @OptIn(SealedSerializationApi::class)
    override val descriptor: SerialDescriptor = object : SerialDescriptor {
        override val serialName: String get() = "com.lightningkite.services.files.ServerFile"
        override val kind: SerialKind get() = StructureKind.CLASS
        override val elementsCount: Int = 1
        override fun getElementName(index: Int): String = "values"
        override fun getElementIndex(name: String): Int = if (name == "values") 0 else -1
        override fun getElementAnnotations(index: Int): List<Annotation> = listOf()
        override fun getElementDescriptor(index: Int): SerialDescriptor = defer.descriptor
        override fun isElementOptional(index: Int): Boolean = false
        override val isInline: Boolean get() = true
    }

    override fun serialize(
        encoder: Encoder,
        value: Embedding
    ): Unit = encoder.encodeSerializableValue(defer, value.values)

    override fun deserialize(decoder: Decoder): Embedding = decoder.decodeSerializableValue(defer).let { Embedding(it) }
    private val c = arrayOf<KSerializer<*>>(defer)
    override fun childSerializers(): Array<KSerializer<*>> = c
}