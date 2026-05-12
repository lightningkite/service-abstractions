package com.lightningkite.services.database.postgres

import kotlinx.datetime.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import org.jetbrains.exposed.sql.BasicBinaryColumnType
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.javatime.*
import java.util.*
import kotlin.time.*
import kotlin.time.Instant
import kotlin.uuid.*


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

@Suppress("Unchecked_cast")
internal fun <T> serializationOverride(strategy: SerializationStrategy<T>): JdbcConversion<T, *>? =
    serializationOverrides[strategy.descriptor] as? JdbcConversion<T, *>

@Suppress("Unchecked_cast")
internal fun <T> serializationOverride(strategy: DeserializationStrategy<T>): JdbcConversion<T, *>? =
    serializationOverrides[strategy.descriptor] as? JdbcConversion<T, *>

@Suppress("Unchecked_cast")
internal fun <T> serializationOverride(strategy: KSerializer<T>): JdbcConversion<T, *>? =
    serializationOverrides[strategy.descriptor] as? JdbcConversion<T, *>

internal fun serializationOverride(descriptor: SerialDescriptor): JdbcConversion<*, *>? =
    serializationOverrides[descriptor]

internal interface JdbcConversion<KOTLIN, JAVA> {
    val columnTypeInfo: ColumnTypeInfo
    val columnTypeInfoNullable: ColumnTypeInfo
    fun columnTypeInfo(nullable: Boolean): ColumnTypeInfo = if (nullable) columnTypeInfoNullable else columnTypeInfo
    fun toJava(value: KOTLIN): JAVA
    fun toKotlin(value: JAVA): KOTLIN
}