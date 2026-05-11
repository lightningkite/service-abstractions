package com.lightningkite.services.database.sql

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toKotlinLocalTime
import org.jetbrains.exposed.sql.BasicBinaryColumnType
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.javatime.JavaDurationColumnType
import org.jetbrains.exposed.sql.javatime.JavaInstantColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalDateTimeColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalTimeColumnType
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Column type info for schema generation
internal data class ColumnTypeInfo(val key: List<String>, val type: org.jetbrains.exposed.sql.ColumnType<*>, val descriptorPath: List<Int>)

internal interface JdbcConversion<KOTLIN, JAVA> {
    val columnTypeInfo: ColumnTypeInfo
    val columnTypeInfoNullable: ColumnTypeInfo
    fun columnTypeInfo(nullable: Boolean): ColumnTypeInfo = if (nullable) columnTypeInfoNullable else columnTypeInfo
    fun toJava(value: KOTLIN): JAVA
    fun toKotlin(value: JAVA): KOTLIN
}

@OptIn(ExperimentalSerializationApi::class)
private val serializationOverrides = mapOf<SerialDescriptor, JdbcConversion<*, *>>(
    Uuid.serializer().descriptor to object : JdbcConversion<Uuid, java.util.UUID> {
        override val columnTypeInfo = ColumnTypeInfo(listOf(), UUIDColumnType(), listOf())
        override val columnTypeInfoNullable = ColumnTypeInfo(listOf(), UUIDColumnType().also { it.nullable = true }, listOf())
        override fun toJava(value: Uuid) = value.toJavaUuid()
        override fun toKotlin(value: java.util.UUID) = value.toKotlinUuid()
    },
    LocalDate.serializer().descriptor to object : JdbcConversion<LocalDate, java.time.LocalDate> {
        override val columnTypeInfo = ColumnTypeInfo(listOf(), JavaLocalDateColumnType(), listOf())
        override val columnTypeInfoNullable = ColumnTypeInfo(listOf(), JavaLocalDateColumnType().also { it.nullable = true }, listOf())
        override fun toJava(value: LocalDate) = value.toJavaLocalDate()
        override fun toKotlin(value: java.time.LocalDate) = value.toKotlinLocalDate()
    },
    Instant.serializer().descriptor to object : JdbcConversion<Instant, java.time.Instant> {
        override val columnTypeInfo = ColumnTypeInfo(listOf(), JavaInstantColumnType(), listOf())
        override val columnTypeInfoNullable = ColumnTypeInfo(listOf(), JavaInstantColumnType().also { it.nullable = true }, listOf())
        override fun toJava(value: Instant) = value.toJavaInstant()
        override fun toKotlin(value: java.time.Instant) = value.toKotlinInstant()
    },
    Duration.serializer().descriptor to object : JdbcConversion<Duration, java.time.Duration> {
        override val columnTypeInfo = ColumnTypeInfo(listOf(), JavaDurationColumnType(), listOf())
        override val columnTypeInfoNullable = ColumnTypeInfo(listOf(), JavaDurationColumnType().also { it.nullable = true }, listOf())
        override fun toJava(value: Duration) = value.toJavaDuration()
        override fun toKotlin(value: java.time.Duration) = value.toKotlinDuration()
    },
    LocalDateTime.serializer().descriptor to object : JdbcConversion<LocalDateTime, java.time.LocalDateTime> {
        override val columnTypeInfo = ColumnTypeInfo(listOf(), JavaLocalDateTimeColumnType(), listOf())
        override val columnTypeInfoNullable = ColumnTypeInfo(listOf(), JavaLocalDateTimeColumnType().also { it.nullable = true }, listOf())
        override fun toJava(value: LocalDateTime) = value.toJavaLocalDateTime()
        override fun toKotlin(value: java.time.LocalDateTime) = value.toKotlinLocalDateTime()
    },
    LocalTime.serializer().descriptor to object : JdbcConversion<LocalTime, java.time.LocalTime> {
        override val columnTypeInfo = ColumnTypeInfo(listOf(), JavaLocalTimeColumnType(), listOf())
        override val columnTypeInfoNullable = ColumnTypeInfo(listOf(), JavaLocalTimeColumnType().also { it.nullable = true }, listOf())
        override fun toJava(value: LocalTime) = value.toJavaLocalTime()
        override fun toKotlin(value: java.time.LocalTime) = value.toKotlinLocalTime()
    },
    ByteArraySerializer().descriptor to object : JdbcConversion<ByteArray, ByteArray> {
        override val columnTypeInfo = ColumnTypeInfo(listOf(), BasicBinaryColumnType(), listOf())
        override val columnTypeInfoNullable = ColumnTypeInfo(listOf(), BasicBinaryColumnType().also { it.nullable = true }, listOf())
        override fun toJava(value: ByteArray) = value
        override fun toKotlin(value: ByteArray) = value
    },
)

@Suppress("UNCHECKED_CAST")
internal fun serializationOverride(descriptor: SerialDescriptor): JdbcConversion<*, *>? =
    serializationOverrides[descriptor]
