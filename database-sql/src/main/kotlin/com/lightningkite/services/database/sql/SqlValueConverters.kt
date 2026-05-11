package com.lightningkite.services.database.sql

import com.lightningkite.services.database.mapformat.ValueConverter
import com.lightningkite.services.database.mapformat.ValueConverterRegistry
import kotlinx.datetime.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaDuration
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinDuration
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

@OptIn(ExperimentalSerializationApi::class)
internal val sqlValueConverters: List<ValueConverter<*, *>> = listOf(
    object : ValueConverter<Uuid, java.util.UUID> {
        override val descriptor: SerialDescriptor = Uuid.serializer().descriptor
        override fun toDatabase(value: Uuid): java.util.UUID = value.toJavaUuid()
        override fun fromDatabase(value: java.util.UUID): Uuid = value.toKotlinUuid()
    },
    object : ValueConverter<Instant, java.time.Instant> {
        override val descriptor: SerialDescriptor = Instant.serializer().descriptor
        override fun toDatabase(value: Instant): java.time.Instant = value.toJavaInstant()
        override fun fromDatabase(value: java.time.Instant): Instant = value.toKotlinInstant()
    },
    object : ValueConverter<Duration, java.time.Duration> {
        override val descriptor: SerialDescriptor = Duration.serializer().descriptor
        override fun toDatabase(value: Duration): java.time.Duration = value.toJavaDuration()
        override fun fromDatabase(value: java.time.Duration): Duration = value.toKotlinDuration()
    },
    object : ValueConverter<LocalDate, java.time.LocalDate> {
        override val descriptor: SerialDescriptor = LocalDate.serializer().descriptor
        override fun toDatabase(value: LocalDate): java.time.LocalDate = value.toJavaLocalDate()
        override fun fromDatabase(value: java.time.LocalDate): LocalDate = value.toKotlinLocalDate()
    },
    object : ValueConverter<LocalDateTime, java.time.LocalDateTime> {
        override val descriptor: SerialDescriptor = LocalDateTime.serializer().descriptor
        override fun toDatabase(value: LocalDateTime): java.time.LocalDateTime = value.toJavaLocalDateTime()
        override fun fromDatabase(value: java.time.LocalDateTime): LocalDateTime = value.toKotlinLocalDateTime()
    },
    object : ValueConverter<LocalTime, java.time.LocalTime> {
        override val descriptor: SerialDescriptor = LocalTime.serializer().descriptor
        override fun toDatabase(value: LocalTime): java.time.LocalTime = value.toJavaLocalTime()
        override fun fromDatabase(value: java.time.LocalTime): LocalTime = value.toKotlinLocalTime()
    },
    object : ValueConverter<ByteArray, ByteArray> {
        override val descriptor: SerialDescriptor = ByteArraySerializer().descriptor
        override fun toDatabase(value: ByteArray): ByteArray = value
        override fun fromDatabase(value: ByteArray): ByteArray = value
    },
)

internal val sqlConverterRegistry = ValueConverterRegistry(sqlValueConverters)
