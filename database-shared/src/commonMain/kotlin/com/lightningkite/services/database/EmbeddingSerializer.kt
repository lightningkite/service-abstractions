package com.lightningkite.services.database

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializer for [Embedding] that serializes as a float array.
 */
public object EmbeddingSerializer : KSerializer<Embedding> {
    private val delegateSerializer = FloatArraySerializer()

    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Embedding) {
        delegateSerializer.serialize(encoder, value.values)
    }

    override fun deserialize(decoder: Decoder): Embedding {
        return Embedding(delegateSerializer.deserialize(decoder))
    }
}
