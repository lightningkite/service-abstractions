package com.lightningkite.services.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * Malformed input must fail as a [SerializationException] whatever the serializer throws to reject
 * it, so that callers holding untrusted input can tell a bad request from a server fault.
 */
class MalformedInputTest {
    private val format = StringArrayFormat(EmptySerializersModule())

    @Test
    fun `our own validating serializers reject as serialization failures`() {
        assertFailsWith<SerializationException> { format.decodeFromString(EmailAddressSerializer, "not-an-email") }
        assertFailsWith<SerializationException> { format.decodeFromString(PhoneNumberSerializer, "12345") }
        assertFailsWith<SerializationException> { format.decodeFromString(RatioSerializer, "half") }
        assertFailsWith<SerializationException> { format.decodeFromString(RatioSerializer, "1/0") }
        assertFailsWith<SerializationException> {
            format.decodeFromString(ZonedDateTimeIso8601Serializer, "yesterday")
        }
        assertFailsWith<SerializationException> {
            format.decodeFromString(OffsetDateTimeIso8601Serializer, "yesterday")
        }
    }

    /** These serializers are kotlin's and kotlinx's, so the format is the only place to make them behave. */
    @Test
    fun `third party validating serializers reject as serialization failures`() {
        assertFailsWith<SerializationException> { format.decodeFromString(Uuid.serializer(), "nope") }
        assertFailsWith<SerializationException> { format.decodeFromString(LocalDate.serializer(), "nope") }
    }

    @Test
    fun `too few values is a serialization failure`() {
        assertFailsWith<SerializationException> { format.decodeFromStringList(GeoCoordinateArraySerializer, listOf()) }
    }

    @Test
    fun `well formed input still decodes`() {
        assertEquals("a@b.com", format.decodeFromString(EmailAddressSerializer, "a@b.com").raw)
        assertEquals(LocalDate(2020, 1, 1), format.decodeFromString(LocalDate.serializer(), "2020-01-01"))
        assertEquals(12, format.decodeFromString(Int.serializer(), "12"))
    }
}
