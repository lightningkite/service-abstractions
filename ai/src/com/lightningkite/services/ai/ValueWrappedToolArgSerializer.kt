@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.services.ai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Wraps a non-object tool-argument serializer (primitive, list, map, enum, value class, …)
 * into a synthetic single-field object `{ "value": <inner> }`.
 *
 * LLM function-calling APIs (Anthropic, OpenAI, Bedrock) require every tool's top-level
 * argument schema to be a JSON object. A tool whose argument is, say, a bare `String` or
 * `List<String>` cannot be expressed directly, so we present it to the model as an object
 * with a single `value` property and transparently unwrap on the way back in.
 *
 * Both the generated JSON schema and the decode path go through this serializer, so the
 * object/unwrap behaviour stays consistent. Object/sealed argument types never need this
 * wrapper and are passed through unchanged by [toJsonSchema].
 */
public class ValueWrappedToolArgSerializer<T>(
    public val inner: KSerializer<T>,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(
            "com.lightningkite.services.ai.ValueWrapped",
            inner.descriptor
        ) {
            element("value", inner.descriptor)
        }

    override fun serialize(encoder: Encoder, value: T): Unit =
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, inner, value)
        }

    override fun deserialize(decoder: Decoder): T =
        decoder.decodeStructure(descriptor) {
            var result: T? = null
            var seen = false
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        result = decodeSerializableElement(descriptor, 0, inner)
                        seen = true
                    }
                    else -> throw IllegalArgumentException("Unexpected index $index decoding value-wrapped tool argument")
                }
            }
            if (!seen) throw IllegalArgumentException("Missing required 'value' field in tool argument")
            @Suppress("UNCHECKED_CAST")
            result as T
        }
}
