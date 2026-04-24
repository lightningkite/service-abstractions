package com.lightningkite.services.database.mongodb.bson

import com.lightningkite.services.data.atZone
import kotlinx.datetime.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import org.bson.*
import kotlin.io.encoding.Base64
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.*

@OptIn(ExperimentalSerializationApi::class)
private val serializationOverrides = mapOf<SerialDescriptor, BsonConversion<*>>(
    Uuid.serializer().descriptor to object : BsonConversion<Uuid> {
        override fun toBson(value: Uuid): BsonBinary = BsonBinary(value.toJavaUuid())
        override fun toKotlin(value: BsonValue): Uuid = when (value.bsonType) {
            BsonType.STRING -> value.asString().value.let { Uuid.parse(it) }
            BsonType.DECIMAL128 -> value.asDecimal128().value.let { Uuid.fromLongs(it.high, it.low) }
            BsonType.BINARY -> value.asBinary().asUuid().toKotlinUuid()
            else -> throw BsonSerializationException("Cannot convert $value to Uuid")
        }
    },
    Instant.serializer().descriptor to object : BsonConversion<Instant> {
        override fun toBson(value: Instant): BsonDateTime = BsonDateTime(value.toEpochMilliseconds())
        override fun toKotlin(value: BsonValue): Instant = when (value.bsonType) {
            BsonType.DATE_TIME -> Instant.fromEpochMilliseconds(value.asDateTime().value)
            BsonType.STRING -> Instant.parse(value.asString().value)
            BsonType.INT64 -> Instant.fromEpochMilliseconds(value.asInt64().value)
            else -> throw BsonSerializationException("Cannot convert $value to Instant")
        }
    },
    LocalDate.serializer().descriptor to object : BsonConversion<LocalDate> {
        override fun toBson(value: LocalDate): BsonDateTime =
            BsonDateTime(value.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())

        override fun toKotlin(value: BsonValue): LocalDate = when (value.bsonType) {
            BsonType.DATE_TIME -> Instant.fromEpochMilliseconds(value.asDateTime().value).atZone(TimeZone.UTC).date
            BsonType.STRING -> LocalDate.parse(value.asString().value)
            BsonType.INT64 -> Instant.fromEpochMilliseconds(value.asInt64().value).atZone(TimeZone.UTC).date
            else -> throw BsonSerializationException("Cannot convert $value to Instant")
        }
    },
    LocalDateTime.serializer().descriptor to object : BsonConversion<LocalDateTime> {
        override fun toBson(value: LocalDateTime): BsonDateTime =
            BsonDateTime(value.toInstant(TimeZone.UTC).toEpochMilliseconds())

        override fun toKotlin(value: BsonValue): LocalDateTime = when (value.bsonType) {
            BsonType.DATE_TIME -> Instant.fromEpochMilliseconds(value.asDateTime().value).toLocalDateTime(TimeZone.UTC)
            BsonType.STRING -> LocalDateTime.parse(value.asString().value)
            BsonType.INT64 -> Instant.fromEpochMilliseconds(value.asInt64().value).toLocalDateTime(TimeZone.UTC)
            else -> throw BsonSerializationException("Cannot convert $value to Instant")
        }
    },
    LocalTime.serializer().descriptor to object : BsonConversion<LocalTime> {
        override fun toBson(value: LocalTime): BsonDateTime =
            BsonDateTime(value.atDate(LocalDate.fromEpochDays(0)).toInstant(TimeZone.UTC).toEpochMilliseconds())

        override fun toKotlin(value: BsonValue): LocalTime = when (value.bsonType) {
            BsonType.DATE_TIME -> Instant.fromEpochMilliseconds(value.asDateTime().value)
                .toLocalDateTime(TimeZone.UTC).time

            BsonType.STRING -> LocalTime.parse(value.asString().value)
            BsonType.INT64 -> Instant.fromEpochMilliseconds(value.asInt64().value).toLocalDateTime(TimeZone.UTC).time
            else -> throw BsonSerializationException("Cannot convert $value to Instant")
        }
    },
    ByteArraySerializer().descriptor to object : BsonConversion<ByteArray> {
        override fun toBson(value: ByteArray): BsonBinary = BsonBinary(value)
        override fun toKotlin(value: BsonValue): ByteArray = when (value.bsonType) {
            BsonType.STRING -> Base64.decode(value.asString().value)
            BsonType.ARRAY -> value.asArray().values.map { it.asInt32().value.toByte() }.toByteArray()
            BsonType.BINARY -> value.asBinary().data
            else -> throw BsonSerializationException("Cannot convert $value to ByteArray")
        }
    },
    Duration.serializer().descriptor to object : BsonConversion<Duration> {
        override fun toBson(value: Duration): BsonDouble = BsonDouble(value.toDouble(DurationUnit.MILLISECONDS))
        override fun toKotlin(value: BsonValue): Duration = when (value.bsonType) {
            BsonType.STRING -> Duration.parse(value.asString().value)
            BsonType.INT64 -> value.asInt64().value.milliseconds
            BsonType.INT32 -> value.asInt32().value.milliseconds
            BsonType.DOUBLE -> value.asDouble().value.milliseconds
            else -> throw BsonSerializationException("Cannot convert $value to ByteArray")
        }
    },
)

@Suppress("Unchecked_cast")
internal fun <T> serializationOverride(strategy: SerializationStrategy<T>): BsonConversion<T>? =
    serializationOverrides[strategy.descriptor] as? BsonConversion<T>

@Suppress("Unchecked_cast")
internal fun <T> serializationOverride(strategy: DeserializationStrategy<T>): BsonConversion<T>? =
    serializationOverrides[strategy.descriptor] as? BsonConversion<T>

internal interface BsonConversion<KOTLIN> {
    fun toBson(value: KOTLIN): BsonValue
    fun toKotlin(value: BsonValue): KOTLIN
}