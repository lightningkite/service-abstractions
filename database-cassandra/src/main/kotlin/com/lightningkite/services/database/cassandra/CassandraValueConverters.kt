// by Claude
package com.lightningkite.services.database.cassandra

import com.lightningkite.services.database.mapformat.ValueConverter
import com.lightningkite.services.database.mapformat.ValueConverterRegistry
import kotlinx.datetime.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Value converters for Cassandra-specific type mappings.
 *
 * These convert Kotlin types to Java types that the Cassandra driver understands.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val cassandraValueConverters: List<ValueConverter<*, *>> = listOf(
    // UUID - Kotlin uuid to Java UUID
    object : ValueConverter<Uuid, java.util.UUID> {
        override val descriptor: SerialDescriptor = Uuid.serializer().descriptor
        override fun toDatabase(value: Uuid): java.util.UUID = value.toJavaUuid()
        override fun fromDatabase(value: java.util.UUID): Uuid = value.toKotlinUuid()
    },

    // Instant - kotlinx.datetime.Instant to java.time.Instant
    object : ValueConverter<kotlinx.datetime.Instant, java.time.Instant> {
        override val descriptor: SerialDescriptor = kotlinx.datetime.Instant.serializer().descriptor
        override fun toDatabase(value: kotlinx.datetime.Instant): java.time.Instant =
            java.time.Instant.ofEpochMilli(value.toEpochMilliseconds())
        override fun fromDatabase(value: java.time.Instant): kotlinx.datetime.Instant =
            kotlinx.datetime.Instant.fromEpochMilliseconds(value.toEpochMilli())
    },

    // Duration - kotlin.time.Duration to java.time.Duration
    object : ValueConverter<Duration, java.time.Duration> {
        override val descriptor: SerialDescriptor = Duration.serializer().descriptor
        override fun toDatabase(value: Duration): java.time.Duration = value.toJavaDuration()
        override fun fromDatabase(value: java.time.Duration): Duration = value.toKotlinDuration()
    },

    // LocalDate - kotlinx.datetime.LocalDate to java.time.LocalDate
    object : ValueConverter<LocalDate, java.time.LocalDate> {
        override val descriptor: SerialDescriptor = LocalDate.serializer().descriptor
        override fun toDatabase(value: LocalDate): java.time.LocalDate = value.toJavaLocalDate()
        override fun fromDatabase(value: java.time.LocalDate): LocalDate = value.toKotlinLocalDate()
    },

    // LocalDateTime - kotlinx.datetime.LocalDateTime to java.time.LocalDateTime
    object : ValueConverter<LocalDateTime, java.time.LocalDateTime> {
        override val descriptor: SerialDescriptor = LocalDateTime.serializer().descriptor
        override fun toDatabase(value: LocalDateTime): java.time.LocalDateTime = value.toJavaLocalDateTime()
        override fun fromDatabase(value: java.time.LocalDateTime): LocalDateTime = value.toKotlinLocalDateTime()
    },

    // LocalTime - kotlinx.datetime.LocalTime to java.time.LocalTime
    object : ValueConverter<LocalTime, java.time.LocalTime> {
        override val descriptor: SerialDescriptor = LocalTime.serializer().descriptor
        override fun toDatabase(value: LocalTime): java.time.LocalTime = value.toJavaLocalTime()
        override fun fromDatabase(value: java.time.LocalTime): LocalTime = value.toKotlinLocalTime()
    },

    // ByteArray to ByteBuffer for Cassandra blob type
    object : ValueConverter<ByteArray, java.nio.ByteBuffer> {
        override val descriptor: SerialDescriptor = ByteArraySerializer().descriptor
        override fun toDatabase(value: ByteArray): java.nio.ByteBuffer = java.nio.ByteBuffer.wrap(value)
        override fun fromDatabase(value: java.nio.ByteBuffer): ByteArray {
            val bytes = ByteArray(value.remaining())
            value.get(bytes)
            return bytes
        }
    },
)

/**
 * Registry of all Cassandra value converters.
 */
internal val cassandraConverterRegistry = ValueConverterRegistry(cassandraValueConverters)
