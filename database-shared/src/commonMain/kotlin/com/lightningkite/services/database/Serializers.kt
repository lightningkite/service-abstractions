package com.lightningkite.services.database

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid


public object DurationMsSerializer : KSerializer<Duration> {
    override fun deserialize(decoder: Decoder): Duration =
        try {
            decoder.decodeLong().milliseconds
        } catch (e: Exception) {
            throw SerializationException(e.message)
        }

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlin.time.Duration/milliseconds", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Duration): Unit = encoder.encodeLong(value.inWholeMilliseconds)
}

public class ClosedRangeSerializer<T : Comparable<T>>(private val inner: KSerializer<T>) : KSerializer<ClosedRange<T>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("kotlin.ranges.ClosedRange") {
        element("start", inner.descriptor)
        element("endInclusive", inner.descriptor)
    }

    override fun deserialize(decoder: Decoder): ClosedRange<T> {
        lateinit var start: T
        lateinit var endInclusive: T
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    CompositeDecoder.UNKNOWN_NAME -> {}
                    0 -> start = decodeSerializableElement(descriptor, index, inner)
                    1 -> endInclusive = decodeSerializableElement(descriptor, index, inner)
                }
            }
        }
        return start..endInclusive
    }

    override fun serialize(encoder: Encoder, value: ClosedRange<T>) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, inner, value.start)
            encodeSerializableElement(descriptor, 1, inner, value.endInclusive)
        }
    }
}

