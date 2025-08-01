package com.lightningkite.services.database

import com.lightningkite.*
import kotlinx.datetime.Instant
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
import kotlin.uuid.Uuid


object UUIDSerializer : KSerializer<Uuid> {
    override fun deserialize(decoder: Decoder): Uuid = try {
        Uuid.parse(decoder.decodeString())
    } catch (e: IllegalArgumentException) {
        throw SerializationException(e.message)
    }

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlin.uuid.Uuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uuid) = encoder.encodeString(value.toString())
}

object DurationMsSerializer : KSerializer<Duration> {
    override fun deserialize(decoder: Decoder): Duration =
        try {
            decoder.decodeLong().milliseconds
        } catch (e: Exception) {
            throw SerializationException(e.message)
        }

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlin.time.Duration/milliseconds", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeLong(value.inWholeMilliseconds)
}

object DurationSerializer : KSerializer<Duration> {
    override fun deserialize(decoder: Decoder): Duration =
        try {
            Duration.parse(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message)
        }

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlin.time.Duration/loose", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeString(value.toString())
}

object InstantIso8601Serializer : KSerializer<Instant> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlinx.datetime.Instant/loose", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant =
        try {
            Instant.parse(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message)
        }

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

}

object LocalDateIso8601Serializer : KSerializer<LocalDate> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlinx.datetime.LocalDate/loose", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalDate =
        try {
            LocalDate.parse(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message)
        }

    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())

}

object LocalTimeIso8601Serializer : KSerializer<LocalTime> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlinx.datetime.LocalTime/loose", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalTime =
        try {
            LocalTime.parse(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message)
        }

    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())

}

object LocalDateTimeIso8601Serializer : KSerializer<LocalDateTime> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlinx.datetime.LocalDateTime/loose", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalDateTime =
        try {
            LocalDateTime.parse(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message)
        }

    override fun serialize(encoder: Encoder, value: LocalDateTime) =
        encoder.encodeString(value.toString())

}

class ClosedRangeSerializer<T : Comparable<T>>(val inner: KSerializer<T>) : KSerializer<ClosedRange<T>> {
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

