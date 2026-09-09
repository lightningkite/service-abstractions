package com.lightningkite.services.data

import com.lightningkite.services.data.Cents.Companion.cents
import com.lightningkite.services.data.Cents.Companion.dollars
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CentsTest {

    @Test
    fun constructorsAndConversions() {
        assertEquals(Cents(5), 5.cents)
        assertEquals(Cents(5), 5L.cents)
        assertEquals(Cents(5), 4.6f.cents)
        assertEquals(Cents(5), 4.6.cents)
        assertEquals(Cents(500), 5.dollars)
        assertEquals(Cents(500), 5L.dollars)
        assertEquals(Cents(1234), 12.34.dollars)
        assertEquals(Cents(1234), 12.34f.dollars)
        assertEquals(12.34, Cents(1234).toDouble())
        assertEquals(-12.34, Cents(-1234).toDouble())
    }

    @Test
    fun dollarsAndCentsDecomposition() {
        Cents(123456).toDollarsAndCents { d, c, negative ->
            assertEquals(1234L, d)
            assertEquals(56L, c)
            assertFalse(negative)
        }
        Cents(-123456).toDollarsAndCents { d, c, negative ->
            assertEquals(1234L, d, "the dollar part is the magnitude; the sign is reported separately")
            assertEquals(56L, c)
            assertTrue(negative)
        }
        Cents(-5).toDollarsAndCents { d, c -> assertEquals(0L to 5L, d to c) }
    }

    @Test
    fun toStringFormatsCurrency() {
        assertEquals("$0", Cents(0).toString())
        assertEquals("$0.05", Cents(5).toString())
        assertEquals("$0.50", Cents(50).toString())
        assertEquals("$1", Cents(100).toString())
        assertEquals("$1.01", Cents(101).toString())
        assertEquals("$1,234,567.89", Cents(123456789).toString())
        assertEquals("-$12.50", Cents(-1250).toString())
        assertEquals("-$0.05", Cents(-5).toString())
    }

    @Test
    fun toStringOptions() {
        assertEquals("$12.50", Cents(-1250).toString(absolute = true))
        assertEquals("$1.00", Cents(100).toString(forceCents = true))
        assertEquals("$0.00", Cents(0).toString(forceCents = true))
        assertEquals(
            "$1.234.567,89",
            Cents(123456789).toString(thousandsSeparator = '.', decimalSeparator = ','),
            "European separators"
        )
    }

    @Test
    fun toShortStringAbbreviates() {
        assertEquals("$0", Cents(0).toAbbreviatedString())
        assertEquals("$999", 999.dollars.toAbbreviatedString())
        assertEquals("$1K", 1_000.dollars.toAbbreviatedString())
        assertEquals("$2K", 1_500.dollars.toAbbreviatedString())
        assertEquals("$1K", 1_499.dollars.toAbbreviatedString())
        assertEquals("$1M", 1_000_000.dollars.toAbbreviatedString())
        assertEquals("$1B", 1_000_000_000L.dollars.toAbbreviatedString())
        assertEquals("$1T", 1_000_000_000_000L.dollars.toAbbreviatedString())
        assertEquals("$1Q", 1_000_000_000_000_000L.dollars.toAbbreviatedString())
    }

    @Test
    fun toShortStringRollsOverInsteadOfPrintingAFourDigitMantissa() {
        assertEquals("$1M", 999_999.dollars.toAbbreviatedString())
        assertEquals("$1B", 999_999_999L.dollars.toAbbreviatedString())
        assertEquals("$999K", 999_499.dollars.toAbbreviatedString())
    }

    @Test
    fun toShortStringOptions() {
        assertEquals("-$5K", (-5_000).dollars.toAbbreviatedString())
        assertEquals("$5K", (-5_000).dollars.toAbbreviatedString(absolute = true))
        assertEquals("$5k", 5_000.dollars.toAbbreviatedString(capital = false))
        assertEquals("$1m", 1_000_000.dollars.toAbbreviatedString(capital = false))
    }

    @Test
    fun toShortStringHandlesTheLargestRepresentableAmount() {
        // The largest Long of cents is ~92.2 quadrillion dollars, so the largest suffix in play is Q.
        assertEquals("$92Q", Cents(Long.MAX_VALUE).toAbbreviatedString())
    }

    @Test
    fun arithmetic() {
        assertEquals(Cents(300), Cents(100) + Cents(200))
        assertEquals(Cents(-100), Cents(100) - Cents(200))
        assertEquals(Cents(-100), -Cents(100))
        assertEquals(Cents(100), Cents(-100).absoluteValue)
        assertEquals(Cents(600), Cents(200) * 3)
        assertEquals(Cents(600), Cents(200) * 3L)
        assertEquals(Cents(50), Cents(100) * 0.5)
        assertEquals(Cents(50), Cents(100) * 0.5f)
        assertEquals(Cents(50), Cents(100) / 2)
        assertEquals(Cents(50), Cents(100) / 2L)
        assertEquals(Cents(200), Cents(100) / 0.5)
        assertEquals(Cents(200), Cents(100) / 0.5f)
        assertEquals(2.0, Cents(100) / Cents(50))
    }

    @Test
    fun floatArithmeticKeepsFullPrecision() {
        // Long * Float in Kotlin yields a Float, whose 24-bit mantissa silently mangles anything
        // over ~$167,772.16.  These amounts are entirely ordinary, so the math must widen to Double.
        assertEquals(Cents(123_456_789), Cents(123_456_789) * 1.0f)
        assertEquals(Cents(123_456_789), Cents(123_456_789) / 1.0f)
        // 1,234,567.5 is exact as a Float; scaling it up by 100 inside Float arithmetic is not.
        assertEquals(Cents(123_456_750), 1_234_567.5f.dollars)
    }

    @Test
    fun roundingIsHalfUp() {
        assertEquals(Cents(1), Cents(1) * 1.4)
        assertEquals(Cents(2), Cents(1) * 1.5)
        assertEquals(Cents(3), Cents(10) / 3.0)
    }

    @Test
    fun comparison() {
        assertTrue(Cents(100) > Cents(50))
        assertTrue(Cents(-100) < Cents(50))
        assertEquals(Cents(100), maxOf(Cents(100), Cents(50)))
        assertEquals(listOf(Cents(-5), Cents(0), Cents(5)), listOf(Cents(5), Cents(-5), Cents(0)).sorted())
    }

    @Test
    fun parseAcceptsCommonFormats() {
        assertEquals(Cents(1234), Cents.parse("12.34"))
        assertEquals(Cents(1234), Cents.parse("$12.34"))
        assertEquals(Cents(123456789), Cents.parse("$1,234,567.89"))
        assertEquals(Cents(1200), Cents.parse("12"))
        assertEquals(Cents(1200), Cents.parse("12."))
        assertEquals(Cents(1250), Cents.parse("12.5"), "a lone decimal digit is tenths, not hundredths")
        assertEquals(Cents(1234), Cents.parse("12.3456"), "extra decimal digits are truncated")
        assertEquals(Cents(0), Cents.parse("0"))
        assertEquals(Cents(50), Cents.parse(".50"), "a missing whole part is zero dollars")
        assertEquals(Cents(50), Cents.parse("$.5"))
    }

    @Test
    fun parseHandlesNegatives() {
        assertEquals(Cents(-1234), Cents.parse("-12.34"))
        assertEquals(Cents(-1234), Cents.parse("-$12.34"))
        assertEquals(Cents(-1234), Cents.parse("$-12.34"))
        assertEquals(Cents(-1234), Cents.parse("  -12.34  "))
        assertEquals(Cents(-5), Cents.parse("-0.05"))
        assertEquals(Cents(-50), Cents.parse("-.50"))
    }

    @Test
    fun parseHonorsTheDecimalSeparator() {
        assertEquals(Cents(123456789), Cents.parse("$1.234.567,89", decimalSeparator = ','))
        assertEquals(Cents(1250), Cents.parse("12,5", decimalSeparator = ','))
    }

    @Test
    fun parseOrNullRejectsGarbage() {
        assertNull(Cents.parseOrNull(""))
        assertNull(Cents.parseOrNull("   "))
        assertNull(Cents.parseOrNull("abc"))
        assertNull(Cents.parseOrNull("-"))
        assertNull(Cents.parseOrNull("."))
        assertNull(Cents.parseOrNull("1.2.3"), "more than one decimal separator")
    }

    @Test
    fun parseOrNullRejectsAmountsTooLargeToRepresent() {
        assertNull(Cents.parseOrNull("99999999999999999999"), "does not fit in a Long")
        assertNull(
            Cents.parseOrNull("92233720368547759"),
            "fits in a Long as dollars but overflows once converted to cents"
        )
        assertNull(
            Cents.parseOrNull("92233720368547758.99"),
            "the dollar part fits, but the cents push it over the top"
        )
        assertEquals(Cents(9223372036854775800), Cents.parseOrNull("92233720368547758"), "the largest whole dollar amount")
    }

    @Test
    fun parseThrowsOnGarbage() {
        assertFailsWith<IllegalArgumentException> { Cents.parse("nonsense") }
    }

    @Test
    fun serializesAsItsCentValue() {
        assertEquals("1234", Json.encodeToString(Cents.serializer(), Cents(1234)))
        assertEquals(Cents(-1234), Json.decodeFromString(Cents.serializer(), "-1234"))
    }
}

class CentsRangeTest {

    @Test
    fun rangeToIsInclusive() {
        val range = Cents(1)..Cents(3)
        assertEquals(Cents(1), range.start)
        assertEquals(Cents(3), range.endInclusive)
        assertTrue(Cents(3) in range)
        assertFalse(Cents(4) in range)
        assertFalse(range.isEmpty())
    }

    @Test
    fun rangeUntilIsExclusive() {
        val range = Cents(1)..<Cents(3)
        assertEquals(Cents(1), range.start)
        assertEquals(Cents(2), range.endInclusive)
        assertFalse(Cents(3) in range)
    }

    @Test
    fun emptyRange() {
        assertTrue((Cents(3)..Cents(1)).isEmpty())
        assertTrue((Cents(1)..<Cents(1)).isEmpty())
        assertEquals(emptyList(), (Cents(3)..Cents(1)).toList())
    }

    @Test
    fun iterates() {
        assertEquals(listOf(Cents(1), Cents(2), Cents(3)), (Cents(1)..Cents(3)).toList())
        assertEquals(listOf(Cents(1), Cents(2)), (Cents(1)..<Cents(3)).toList())
    }

    @Test
    fun toStringUsesCurrencyFormatting() {
        assertEquals("$0.01..$0.03", (Cents(1)..Cents(3)).toString())
    }
}
