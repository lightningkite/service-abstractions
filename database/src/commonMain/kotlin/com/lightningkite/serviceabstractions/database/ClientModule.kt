package com.lightningkite.serialization

import com.lightningkite.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid


val ClientModule = SerializersModule {
    contextual(Uuid::class, UUIDSerializer)
    contextual(Instant::class, InstantIso8601Serializer)
    contextual(LocalDate::class, LocalDateIso8601Serializer)
    contextual(LocalTime::class, LocalTimeIso8601Serializer)
    contextual(LocalDateTime::class, LocalDateTimeIso8601Serializer)
    contextual(OffsetDateTime::class, OffsetDateTimeIso8601Serializer)
    contextual(ZonedDateTime::class, ZonedDateTimeIso8601Serializer)
    contextual(Duration::class, DurationSerializer)
//    contextual(ServerFile::class, ServerFileSerializer)
    contextual(GeoCoordinate::class, GeoCoordinateArraySerializer)
    contextual(ClosedRange::class, {
        @Suppress("UNCHECKED_CAST")
        ClosedRangeSerializer(it[0] as KSerializer<Comparable<Comparable<*>>>)
    })
}
