package com.lightningkite.services.database.postgres

import com.lightningkite.atZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toKotlinLocalTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import org.jetbrains.exposed.sql.BasicBinaryColumnType
import org.jetbrains.exposed.sql.BinaryColumnType
import org.jetbrains.exposed.sql.BlobColumnType
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.javatime.JavaDurationColumnType
import org.jetbrains.exposed.sql.javatime.JavaInstantColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalDateTimeColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalTimeColumnType
import java.sql.Blob
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toJavaDuration
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinDuration
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid


@OptIn(ExperimentalSerializationApi::class)
private val serializationOverrides = mapOf<SerialDescriptor, JdbcConversion<*, *>>(
    Uuid.serializer().descriptor to object : JdbcConversion<Uuid, java.util.UUID> {
        override val columnTypeInfo: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), UUIDColumnType(), listOf())
        override val columnTypeInfoNullable: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), UUIDColumnType().also { it.nullable = true }, listOf())
        override fun toJava(value: Uuid): UUID = value.toJavaUuid()
        override fun toKotlin(value: UUID): Uuid = value.toKotlinUuid()
    },
    LocalDate.serializer().descriptor to object : JdbcConversion<LocalDate, java.time.LocalDate> {
        override val columnTypeInfo: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), JavaLocalDateColumnType(), listOf())
        override val columnTypeInfoNullable: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), JavaLocalDateColumnType().also { it.nullable = true }, listOf())

        override fun toJava(value: LocalDate): java.time.LocalDate = value.toJavaLocalDate()
        override fun toKotlin(value: java.time.LocalDate): LocalDate = value.toKotlinLocalDate()
    },
    Instant.serializer().descriptor to object : JdbcConversion<Instant, java.time.Instant> {
        override val columnTypeInfo: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), JavaInstantColumnType(), listOf())
        override val columnTypeInfoNullable: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), JavaInstantColumnType().also { it.nullable = true }, listOf())

        override fun toJava(value: Instant): java.time.Instant = value.toJavaInstant()
        override fun toKotlin(value: java.time.Instant): Instant = value.toKotlinInstant()
    },
    Duration.serializer().descriptor to object : JdbcConversion<Duration, java.time.Duration> {
        override val columnTypeInfo: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), JavaDurationColumnType(), listOf())
        override val columnTypeInfoNullable: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), JavaDurationColumnType().also { it.nullable = true }, listOf())

        override fun toJava(value: Duration): java.time.Duration = value.toJavaDuration()
        override fun toKotlin(value: java.time.Duration): Duration = value.toKotlinDuration()
    },
    LocalDateTime.serializer().descriptor to object : JdbcConversion<LocalDateTime, java.time.LocalDateTime> {
        override val columnTypeInfo: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), JavaLocalDateTimeColumnType(), listOf())
        override val columnTypeInfoNullable: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), JavaLocalDateTimeColumnType().also { it.nullable = true }, listOf())

        override fun toJava(value: LocalDateTime): java.time.LocalDateTime = value.toJavaLocalDateTime()
        override fun toKotlin(value: java.time.LocalDateTime): LocalDateTime = value.toKotlinLocalDateTime()
    },
    LocalTime.serializer().descriptor to object : JdbcConversion<LocalTime, java.time.LocalTime> {
        override val columnTypeInfo: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), JavaLocalTimeColumnType(), listOf())
        override val columnTypeInfoNullable: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), JavaLocalTimeColumnType().also { it.nullable = true }, listOf())

        override fun toJava(value: LocalTime): java.time.LocalTime = value.toJavaLocalTime()
        override fun toKotlin(value: java.time.LocalTime): LocalTime = value.toKotlinLocalTime()
    },
    ByteArraySerializer().descriptor to object : JdbcConversion<ByteArray, ByteArray> {
        override val columnTypeInfo: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), BasicBinaryColumnType(), listOf())
        override val columnTypeInfoNullable: ColumnTypeInfo =
            ColumnTypeInfo(listOf<String>(), BasicBinaryColumnType().also { it.nullable = true }, listOf())

        override fun toJava(value: ByteArray): ByteArray = value
        override fun toKotlin(value: ByteArray): ByteArray = value
    },
)

internal fun <T> serializationOverride(strategy: SerializationStrategy<T>): JdbcConversion<T, *>? =
    serializationOverrides[strategy.descriptor] as? JdbcConversion<T, *>

internal fun <T> serializationOverride(strategy: DeserializationStrategy<T>): JdbcConversion<T, *>? =
    serializationOverrides[strategy.descriptor] as? JdbcConversion<T, *>

internal fun <T> serializationOverride(strategy: KSerializer<T>): JdbcConversion<T, *>? =
    serializationOverrides[strategy.descriptor] as? JdbcConversion<T, *>

internal fun serializationOverride(descriptor: SerialDescriptor): JdbcConversion<*, *>? =
    serializationOverrides[descriptor]

internal interface JdbcConversion<KOTLIN, JAVA> {
    val columnTypeInfo: ColumnTypeInfo
    val columnTypeInfoNullable: ColumnTypeInfo
    fun columnTypeInfo(nullable: Boolean): ColumnTypeInfo = if(nullable) columnTypeInfoNullable else columnTypeInfo
    fun toJava(value: KOTLIN): JAVA
    fun toKotlin(value: JAVA): KOTLIN
}