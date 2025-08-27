package com.lightningkite.services.database.mongodb.bson

import com.lightningkite.services.database.DurationMsSerializer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import org.bson.BsonType
import org.bson.UuidRepresentation
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import java.math.BigDecimal
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.text.toLong
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
internal annotation class NonEncodeNull


internal object DateSerializer : KSerializer<Date> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DateSerializer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Date) {
        encoder as BsonEncoder
        encoder.encodeDateTime(value.time)
    }

    @InternalSerializationApi
    override fun deserialize(decoder: Decoder): Date {
        return when (decoder) {
            is FlexibleDecoder -> {
                Date(
                        when (decoder.reader.currentBsonType) {
                            BsonType.STRING -> decoder.decodeString().toLong()
                            BsonType.DATE_TIME -> decoder.reader.readDateTime()
                            else -> throw SerializationException("Unsupported ${decoder.reader.currentBsonType} reading date")
                        }
                )
            }
            else -> throw SerializationException("Unknown decoder type")
        }
    }
}


internal object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("BigDecimalSerializer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder as BsonEncoder
        encoder.encodeDecimal128(Decimal128(value))
    }

    @InternalSerializationApi
    override fun deserialize(decoder: Decoder): BigDecimal {
        return when (decoder) {
            is FlexibleDecoder -> {
                when (decoder.reader.currentBsonType) {
                    BsonType.STRING -> BigDecimal(decoder.decodeString())
                    BsonType.DECIMAL128 -> decoder.reader.readDecimal128().bigDecimalValue()
                    else -> throw SerializationException("Unsupported ${decoder.reader.currentBsonType} reading decimal128")
                }
            }
            else -> throw SerializationException("Unknown decoder type")
        }
    }
}


internal object ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ByteArraySerializer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder as BsonEncoder
        encoder.encodeByteArray(value)
    }

    @InternalSerializationApi
    override fun deserialize(decoder: Decoder): ByteArray {
        return when (decoder) {
            is FlexibleDecoder -> {
                decoder.reader.readBinaryData().data
            }
            else -> throw SerializationException("Unknown decoder type")
        }

    }
}


internal object ObjectIdSerializer : KSerializer<ObjectId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ObjectIdSerializer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ObjectId) {
        encoder as BsonEncoder
        encoder.encodeObjectId(value)
    }

    @InternalSerializationApi
    override fun deserialize(decoder: Decoder): ObjectId {
        return when (decoder) {
            is FlexibleDecoder -> {
                when (decoder.reader.currentBsonType) {
                    BsonType.STRING -> ObjectId(decoder.decodeString())
                    BsonType.OBJECT_ID -> decoder.reader.readObjectId()
                    else -> throw SerializationException("Unsupported ${decoder.reader.currentBsonType} reading object id")
                }
            }
            else -> throw SerializationException("Unknown decoder type")
        }

    }
}


internal object UuidSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UuidSerializer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uuid) {
        encoder as BsonEncoder
        encoder.encodeUUID(value.toJavaUuid(), UuidRepresentation.STANDARD)
    }

    override fun deserialize(decoder: Decoder): Uuid {
        return when (decoder) {
            is FlexibleDecoder -> {
                when (decoder.reader.currentBsonType) {
                    BsonType.STRING -> {
                        Uuid.parse(decoder.decodeString())
                    }
                    BsonType.BINARY -> {
                        decoder.reader.readBinaryData().asUuid(UuidRepresentation.STANDARD).toKotlinUuid()
                    }
                    else -> throw SerializationException("Unsupported ${decoder.reader.currentBsonType} reading object id")
                }
            }
            else -> throw SerializationException("Unknown decoder type")
        }
    }
}


public abstract class TemporalExtendedJsonSerializer<T> : KSerializer<T> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(javaClass.name + "/bson", PrimitiveKind.STRING)

    /**
     * Returns the number of milliseconds since January 1, 1970, 00:00:00 GMT
     * represented by this <tt>Temporal</tt> object.
     *
     * @return  the number of milliseconds since January 1, 1970, 00:00:00 GMT
     *          represented by this date.
     */
    public abstract fun epochMillis(temporal: T): Long

    public abstract fun instantiate(date: Long): T

    override fun serialize(encoder: Encoder, value: T) {
        encoder as BsonEncoder
        encoder.encodeDateTime(epochMillis(value))
    }

    override fun deserialize(decoder: Decoder): T {
        return when (decoder) {
            is FlexibleDecoder -> {
                instantiate(
                    when (decoder.reader.currentBsonType) {
                        BsonType.STRING -> decoder.decodeString().toLong()
                        BsonType.DATE_TIME -> decoder.reader.readDateTime()
                        BsonType.INT32 -> decoder.decodeInt().toLong()
                        BsonType.INT64 -> decoder.decodeLong()
                        BsonType.DOUBLE -> decoder.decodeDouble().toLong()
                        BsonType.DECIMAL128 -> decoder.reader.readDecimal128().toLong()
                        BsonType.TIMESTAMP -> TimeUnit.SECONDS.toMillis(decoder.reader.readTimestamp().time.toLong())
                        else -> throw SerializationException("Unsupported ${decoder.reader.currentBsonType} reading date")
                    }
                )
            }
            else -> throw SerializationException("Unknown decoder type")
        }
    }
}


public object MongoInstantSerializer : TemporalExtendedJsonSerializer<Instant>() {

    override fun epochMillis(temporal: Instant): Long = temporal.toEpochMilliseconds()

    override fun instantiate(date: Long): Instant = Instant.fromEpochMilliseconds(date)
}


public object MongoLocalDateSerializer : TemporalExtendedJsonSerializer<LocalDate>() {

    override fun epochMillis(temporal: LocalDate): Long =
        MongoInstantSerializer.epochMillis(temporal.atStartOfDayIn(TimeZone.UTC))

    override fun instantiate(date: Long): LocalDate =
        MongoLocalDateTimeSerializer.instantiate(date).date
}


public object MongoLocalDateTimeSerializer : TemporalExtendedJsonSerializer<LocalDateTime>() {

    override fun epochMillis(temporal: LocalDateTime): Long =
        MongoInstantSerializer.epochMillis(temporal.toInstant(TimeZone.UTC))

    override fun instantiate(date: Long): LocalDateTime =
        MongoInstantSerializer.instantiate(date).toLocalDateTime(TimeZone.UTC)
}


public object MongoLocalTimeSerializer : TemporalExtendedJsonSerializer<LocalTime>() {

    override fun epochMillis(temporal: LocalTime): Long =
        MongoLocalDateTimeSerializer.epochMillis(temporal.atDate(LocalDate.fromEpochDays(0)))

    override fun instantiate(date: Long): LocalTime =
        MongoLocalDateTimeSerializer.instantiate(date).time
}


public object MongoLocaleSerializer : KSerializer<Locale> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.util.Locale/bson", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Locale = Locale.forLanguageTag(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Locale) {
        encoder.encodeString(value.toLanguageTag())
    }
}


internal val DefaultModule = SerializersModule {
    contextual(ObjectId::class, ObjectIdSerializer)
    contextual(BigDecimal::class, BigDecimalSerializer)
    contextual(ByteArray::class, ByteArraySerializer)
    contextual(Date::class, DateSerializer)
    contextual(Uuid::class, UuidSerializer)

    contextual(Duration::class, DurationMsSerializer)
    contextual(Instant::class, MongoInstantSerializer)
    contextual(LocalDate::class, MongoLocalDateSerializer)
    contextual(LocalDateTime::class, MongoLocalDateTimeSerializer)
    contextual(LocalTime::class, MongoLocalTimeSerializer)
    contextual(Locale::class, MongoLocaleSerializer)
}

