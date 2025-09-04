package com.lightningkite.services.database.mongodb.bson

import com.github.jershell.kbson.ByteArraySerializer
import com.lightningkite.ZonedDateTime
import com.lightningkite.atZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import org.bson.BsonBinary
import org.bson.BsonDateTime
import org.bson.BsonDouble
import org.bson.BsonInt64
import org.bson.BsonSerializationException
import org.bson.BsonType
import org.bson.BsonValue
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

@OptIn(ExperimentalSerializationApi::class)
private val serializationOverrides = mapOf<SerialDescriptor, BsonConversion<*>>(
    Uuid.serializer().descriptor to object: BsonConversion<Uuid> {
        override fun invoke(value: Uuid): BsonBinary = BsonBinary(value.toJavaUuid())
        override fun invoke(value: BsonValue): Uuid = when(value.bsonType) {
            BsonType.STRING -> value.asString().value.let { Uuid.parse(it) }
            BsonType.DECIMAL128 -> value.asDecimal128().value.let { Uuid.fromLongs(it.high, it.low) }
            BsonType.BINARY -> value.asBinary().asUuid().toKotlinUuid()
            else -> throw BsonSerializationException("Cannot convert $value to Uuid")
        }
    },
    Instant.serializer().descriptor to object: BsonConversion<Instant> {
        override fun invoke(value: Instant): BsonDateTime = BsonDateTime(value.toEpochMilliseconds())
        override fun invoke(value: BsonValue): Instant = when(value.bsonType) {
            BsonType.DATE_TIME -> Instant.fromEpochMilliseconds(value.asDateTime().value)
            BsonType.STRING -> Instant.parse(value.asString().value)
            BsonType.INT64 -> Instant.fromEpochMilliseconds(value.asInt64().value)
            else -> throw BsonSerializationException("Cannot convert $value to Instant")
        }
    },
    LocalDate.serializer().descriptor to object: BsonConversion<LocalDate> {
        override fun invoke(value: LocalDate): BsonDateTime = BsonDateTime(value.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
        override fun invoke(value: BsonValue): LocalDate = when(value.bsonType) {
            BsonType.DATE_TIME -> Instant.fromEpochMilliseconds(value.asDateTime().value).atZone(TimeZone.UTC).date
            BsonType.STRING -> LocalDate.parse(value.asString().value)
            BsonType.INT64 -> Instant.fromEpochMilliseconds(value.asInt64().value).atZone(TimeZone.UTC).date
            else -> throw BsonSerializationException("Cannot convert $value to Instant")
        }
    },
    LocalDateTime.serializer().descriptor to object: BsonConversion<LocalDateTime> {
        override fun invoke(value: LocalDateTime): BsonDateTime = BsonDateTime(value.toInstant(TimeZone.UTC).toEpochMilliseconds())
        override fun invoke(value: BsonValue): LocalDateTime = when(value.bsonType) {
            BsonType.DATE_TIME -> Instant.fromEpochMilliseconds(value.asDateTime().value).toLocalDateTime(TimeZone.UTC)
            BsonType.STRING -> LocalDateTime.parse(value.asString().value)
            BsonType.INT64 -> Instant.fromEpochMilliseconds(value.asInt64().value).toLocalDateTime(TimeZone.UTC)
            else -> throw BsonSerializationException("Cannot convert $value to Instant")
        }
    },
    LocalTime.serializer().descriptor to object: BsonConversion<LocalTime> {
        override fun invoke(value: LocalTime): BsonDateTime = BsonDateTime(value.atDate(LocalDate.fromEpochDays(0)).toInstant(TimeZone.UTC).toEpochMilliseconds())
        override fun invoke(value: BsonValue): LocalTime = when(value.bsonType) {
            BsonType.DATE_TIME -> Instant.fromEpochMilliseconds(value.asDateTime().value).toLocalDateTime(TimeZone.UTC).time
            BsonType.STRING -> LocalTime.parse(value.asString().value)
            BsonType.INT64 -> Instant.fromEpochMilliseconds(value.asInt64().value).toLocalDateTime(TimeZone.UTC).time
            else -> throw BsonSerializationException("Cannot convert $value to Instant")
        }
    },
    ByteArraySerializer().descriptor to object: BsonConversion<ByteArray> {
        override fun invoke(value: ByteArray): BsonBinary = BsonBinary(value)
        override fun invoke(value: BsonValue): ByteArray = when(value.bsonType) {
            BsonType.STRING -> Base64.decode(value.asString().value)
            BsonType.ARRAY -> value.asArray().values.map { it.asInt32().value.toByte() }.toByteArray()
            BsonType.BINARY -> value.asBinary().data
            else -> throw BsonSerializationException("Cannot convert $value to ByteArray")
        }
    },
    Duration.serializer().descriptor to object: BsonConversion<Duration> {
        override fun invoke(value: Duration): BsonDouble = BsonDouble(value.toDouble(DurationUnit.MILLISECONDS))
        override fun invoke(value: BsonValue): Duration = when(value.bsonType) {
            BsonType.STRING -> Duration.parse(value.asString().value)
            BsonType.INT64 -> value.asInt64().value.milliseconds
            BsonType.INT32 -> value.asInt32().value.milliseconds
            BsonType.DOUBLE -> value.asDouble().value.milliseconds
            else -> throw BsonSerializationException("Cannot convert $value to ByteArray")
        }
    },
)
internal fun <T> serializationOverride(strategy: SerializationStrategy<T>): BsonConversion<T>? = serializationOverrides[strategy.descriptor] as? BsonConversion<T>
internal fun <T> serializationOverride(strategy: DeserializationStrategy<T>): BsonConversion<T>? = serializationOverrides[strategy.descriptor] as? BsonConversion<T>

internal interface BsonConversion<KOTLIN> {
    operator fun invoke(value: KOTLIN): BsonValue
    operator fun invoke(value: BsonValue): KOTLIN
}