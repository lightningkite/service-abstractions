package com.lightningkite.services.database

import kotlinx.serialization.*
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer

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
public class EmbeddingSerializer : GeneratedSerializer<Embedding> {
    private val defer = FloatArraySerializer()

    override val descriptor: SerialDescriptor =
        InliningSerialDescriptor("com.lightningkite.services.database.Embedding", defer.descriptor)

    override fun serialize(
        encoder: Encoder,
        value: Embedding,
    ): Unit = encoder.encodeSerializableValue(defer, value.values)

    override fun deserialize(decoder: Decoder): Embedding = decoder.decodeSerializableValue(defer).let { Embedding(it) }
    private val c = arrayOf<KSerializer<*>>(defer)
    override fun childSerializers(): Array<KSerializer<*>> = c
}