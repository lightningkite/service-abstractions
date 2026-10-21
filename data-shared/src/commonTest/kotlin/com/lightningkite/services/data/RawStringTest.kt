package com.lightningkite.services.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RawStringTest {
    private fun <T : IsRawString> roundTrips(serializer: KSerializer<T>, value: T) {
        val json = Json.encodeToString(serializer, value)
        assertEquals("\"${value.raw}\"", json)
        assertEquals(value, Json.decodeFromString(serializer, json))
    }

    @Test
    fun normalizedValuesEncodeAsPlainStrings() {
        roundTrips(TrimmedString.serializer(), "  a b  ".trimmed())
        roundTrips(CaselessString.serializer(), " A ".caseless())
        roundTrips(TrimmedCaselessString.serializer(), " A ".trimmedCaseless())
        roundTrips(EmailAddress.serializer(), " Bob@Example.com ".toEmailAddress())
        roundTrips(PhoneNumber.serializer(), "(801) 555-1234".toPhoneNumber())
    }

    @Test
    fun serialNamesAreUnchanged() {
        assertEquals(
            IsRawString.serialNames,
            setOf(
                TrimmedString.serializer(),
                CaselessString.serializer(),
                TrimmedCaselessString.serializer(),
                EmailAddress.serializer(),
                PhoneNumber.serializer(),
            ).map { it.descriptor.serialName }.toSet()
        )
    }

    @Test
    fun unnormalizedInputIsRejectedNotFixed() {
        assertFailsWith<SerializationException> { Json.decodeFromString(TrimmedString.serializer(), "\" a \"") }
        assertFailsWith<SerializationException> { Json.decodeFromString(CaselessString.serializer(), "\"A\"") }
        assertFailsWith<SerializationException> { Json.decodeFromString(TrimmedCaselessString.serializer(), "\" a\"") }
        assertFailsWith<SerializationException> { Json.decodeFromString(TrimmedCaselessString.serializer(), "\"A\"") }
        assertFailsWith<SerializationException> { Json.decodeFromString(EmailAddress.serializer(), "\"Bob@example.com\"") }
        assertFailsWith<SerializationException> { Json.decodeFromString(EmailAddress.serializer(), "\"not-an-email\"") }
        assertFailsWith<SerializationException> { Json.decodeFromString(PhoneNumber.serializer(), "\"(801) 555-1234\"") }
        assertFailsWith<SerializationException> { Json.decodeFromString(PhoneNumber.serializer(), "\"12345\"") }
    }

    @Suppress("DEPRECATION")
    @Test
    fun constructorRejectsUnnormalizedValues() {
        assertFailsWith<IllegalArgumentException> { TrimmedString(" a") }
        assertFailsWith<IllegalArgumentException> { EmailAddress("Bob@example.com") }
        assertEquals("bob@example.com", EmailAddress("bob@example.com").raw)
    }
}
