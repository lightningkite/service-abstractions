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

    @Test
    fun phoneNumbersNormalizeToE164WithUsDefault() {
        assertEquals("+18015550123", "+18015550123".toPhoneNumberOrNull()?.raw)
        assertEquals("+18015550123", "18015550123".toPhoneNumberOrNull()?.raw)
        assertEquals("+18015550123", "8015550123".toPhoneNumberOrNull()?.raw)
        assertEquals("+18015550123", "(801) 555-0123".toPhoneNumberOrNull()?.raw)
        assertEquals("+442079460958", "+44 20 7946 0958".toPhoneNumberOrNull()?.raw)
        assertEquals("+12345678", "+12345678".toPhoneNumberOrNull()?.raw)
        assertEquals("+123456789012345", "+123456789012345".toPhoneNumberOrNull()?.raw)
    }

    @Test
    fun phoneNumberGarbageIsRejected() {
        assertEquals(null, "+abc".toPhoneNumberOrNull())
        assertEquals(null, "+1".toPhoneNumberOrNull())
        assertEquals(null, "+1234567".toPhoneNumberOrNull())
        assertEquals(null, "801555012".toPhoneNumberOrNull())
        assertEquals(null, "+1234567890123456".toPhoneNumberOrNull())
        assertEquals(null, "801555o123".toPhoneNumberOrNull())
        assertEquals(null, "+1801555o123".toPhoneNumberOrNull())
        assertEquals(null, "28015550123".toPhoneNumberOrNull())
        assertEquals(null, "".toPhoneNumberOrNull())
        assertFailsWith<IllegalArgumentException> { "+abc".toPhoneNumber() }
    }

    @Test
    fun phoneNumbersParseForRegion() {
        val uk = PhoneNumber.Region.UK
        assertEquals("+447911123456", "07911 123456".toPhoneNumberOrNull(uk)?.raw)
        assertEquals("+447911123456", "7911 123456".toPhoneNumberOrNull(uk)?.raw)
        assertEquals("+447911123456", "44 7911 123456".toPhoneNumberOrNull(uk)?.raw)
        assertEquals("+18015550123", "+1 801 555 0123".toPhoneNumberOrNull(uk)?.raw)
        assertEquals(null, "801 555 0123".toPhoneNumberOrNull(PhoneNumber.Region.FR))
        assertEquals("+33123456789", "01 23 45 67 89".toPhoneNumberOrNull(PhoneNumber.Region.FR)?.raw)
        assertEquals("+34912345678", "912 345 678".toPhoneNumberOrNull(PhoneNumber.Region.ES)?.raw)
        assertEquals("+61412345678", "0412 345 678".toPhoneNumberOrNull(PhoneNumber.Region.AU)?.raw)
        assertEquals("+919876543210", "098765 43210".toPhoneNumberOrNull(PhoneNumber.Region.IN)?.raw)

        assertEquals("+18015550123", "+18015550123".toPhoneNumberOrNull(region = null)?.raw)
        assertEquals(null, "8015550123".toPhoneNumberOrNull(region = null))

        for (region in PhoneNumber.Region.entries) {
            val number = "+${region.countryCode}${"2".repeat(region.nationalLength)}".toPhoneNumber()
            assertEquals(number, number.digits(region).toPhoneNumber(region))
        }
    }

    @Test
    fun phoneNumbersFormatByRegion() {
        assertEquals("+1 (801) 555-0123", "+18015550123".toPhoneNumber().toString())
        assertEquals("+12345678", "+12345678".toPhoneNumber().toString())
        assertEquals("+442079460958", "+442079460958".toPhoneNumber().toString())
        assertEquals("", PhoneNumber.EMPTY.toString())

        assertEquals("+44 20 7946 0958", "+442079460958".toPhoneNumber().toString(PhoneNumber.Region.UK))
        assertEquals("+44 7911 123456", "+447911123456".toPhoneNumber().toString(PhoneNumber.Region.UK))
        assertEquals("+33 1 23 45 67 89", "+33123456789".toPhoneNumber().toString(PhoneNumber.Region.FR))
        assertEquals("+34 912 345 678", "+34912345678".toPhoneNumber().toString(PhoneNumber.Region.ES))
        assertEquals("+61 412 345 678", "+61412345678".toPhoneNumber().toString(PhoneNumber.Region.AU))
        assertEquals("+61 2 1234 5678", "+61212345678".toPhoneNumber().toString(PhoneNumber.Region.AU))
        assertEquals("+91 98765 43210", "+919876543210".toPhoneNumber().toString(PhoneNumber.Region.IN))

        assertEquals("(801) 555-0123", "+18015550123".toPhoneNumber().toString(PhoneNumber.Region.US, includeCountryCode = false))
        assertEquals("020 7946 0958", "+442079460958".toPhoneNumber().toString(PhoneNumber.Region.UK, includeCountryCode = false))
        assertEquals("07911 123456", "+447911123456".toPhoneNumber().toString(PhoneNumber.Region.UK, includeCountryCode = false))
        assertEquals("01 23 45 67 89", "+33123456789".toPhoneNumber().toString(PhoneNumber.Region.FR, includeCountryCode = false))
        assertEquals("912 345 678", "+34912345678".toPhoneNumber().toString(PhoneNumber.Region.ES, includeCountryCode = false))
        assertEquals("0412 345 678", "+61412345678".toPhoneNumber().toString(PhoneNumber.Region.AU, includeCountryCode = false))
        assertEquals("098765 43210", "+919876543210".toPhoneNumber().toString(PhoneNumber.Region.IN, includeCountryCode = false))

        assertEquals("+18015550123", "+18015550123".toPhoneNumber().toString(PhoneNumber.Region.UK))
        assertEquals("+18015550123", "+18015550123".toPhoneNumber().toString(PhoneNumber.Region.UK, includeCountryCode = false))

        assertEquals("8015550123", "+18015550123".toPhoneNumber().digits(PhoneNumber.Region.US))
        assertEquals("02079460958", "+442079460958".toPhoneNumber().digits(PhoneNumber.Region.UK))
        assertEquals("912345678", "+34912345678".toPhoneNumber().digits(PhoneNumber.Region.ES))
        assertEquals("+18015550123", "+18015550123".toPhoneNumber().digits(PhoneNumber.Region.UK))
        assertEquals("+18015550123", "+18015550123".toPhoneNumber().digits(PhoneNumber.Region.US).toPhoneNumber().raw)
        assertEquals("+4420794609", "+4420794609".toPhoneNumber().toString(PhoneNumber.Region.UK))
    }

    @Test
    fun phoneNumberDecodingEnforcesE164() {
        assertFailsWith<SerializationException> { Json.decodeFromString(PhoneNumber.serializer(), "\"+abc\"") }
        assertFailsWith<SerializationException> { Json.decodeFromString(PhoneNumber.serializer(), "\"+1\"") }
        assertEquals("+1 (801) 555-0123", Json.decodeFromString(PhoneNumber.serializer(), "\"+18015550123\"").toString())
    }

    @OptIn(NormalizedRawStringConstructor::class)
    @Test
    fun constructorRejectsUnnormalizedValues() {
        assertFailsWith<IllegalArgumentException> { TrimmedString(" a") }
        assertFailsWith<IllegalArgumentException> { EmailAddress("Bob@example.com") }
        assertEquals("bob@example.com", EmailAddress("bob@example.com").raw)
    }
}
