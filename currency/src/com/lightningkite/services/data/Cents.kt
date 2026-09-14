package com.lightningkite.services.data

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.math.absoluteValue
import kotlin.math.roundToLong


private val SHORT_SUFFIXES = listOf("K", "M", "B", "T", "Q")

@Deprecated("You're looking for 'Cents'.  It's named as such to make clear the accuracy level.", ReplaceWith("Cents", "com.lightningkite.services.data.Cents"))
public typealias Dollars = Cents

/**
 * An exact amount of dollar-based currency, stored as a whole number of cents.
 *
 * Use this rather than a [Double] for money: [Double] cannot represent most cent amounts exactly, so sums
 * of them drift.  Construct amounts with the extensions in the companion, e.g. `12.dollars`, `50.cents`.
 */
@Serializable
@JvmInline
@GenerateDataClassPaths
public value class Cents(public val inCents: Long) : Comparable<Cents> {
    public inline fun <T> toDollarsAndCents(action: (dollars: Long, cents: Long, negative: Boolean) -> T): T =
        action(inCents.absoluteValue / 100, inCents.absoluteValue % 100, inCents < 0)

    public inline fun <T> toDollarsAndCents(action: (dollars: Long, cents: Long) -> T): T =
        action(inCents.absoluteValue / 100, inCents.absoluteValue.rem(100))

    override fun compareTo(other: Cents): Int = inCents.compareTo(other.inCents)

    /**
     * Formats as currency, e.g. `"-$1,234.56"`.  Cents are omitted when the amount is whole unless
     * [forceCents] is set, and the sign is omitted when [absolute] is set.
     */
    public fun toString(absolute: Boolean = false, forceCents: Boolean = false, thousandsSeparator: Char = ',', decimalSeparator: Char = '.'): String = toDollarsAndCents { d, c, negative ->
        val dollars = d.toString().reversed().chunked(3) { it.reversed() }.reversed().joinToString(thousandsSeparator.toString())
        val str =
            if (c == 0L && !forceCents) "$$dollars"
            else {
                val cents = c.toString().padStart(2, '0')
                "$$dollars$decimalSeparator$cents"
            }
        if (negative && !absolute) "-$str" else str
    }
    /**
     * Formats abbreviated to a whole number of scaled dollars, e.g. $1,200,000 as `"$1M"`; cents are dropped.
     * Suffixes run K, M, B, T, Q, lowercased when [capital] is false.
     */
    public fun toAbbreviatedString(absolute: Boolean = false, capital: Boolean = true): String = toDollarsAndCents { d, _, negative ->
        // Scale by thousands until the mantissa is under 1,000.  Rounding is applied after scaling and can
        // itself push the mantissa back to 1,000 (999,999 dollars rounds to 1000K), so it gets one more step.
        var mantissa = d.toDouble()
        var suffix = -1
        while (mantissa >= 1_000.0 && suffix < SHORT_SUFFIXES.lastIndex) {
            mantissa /= 1_000.0
            suffix++
        }
        if (mantissa.roundToLong() >= 1_000L && suffix < SHORT_SUFFIXES.lastIndex) {
            mantissa /= 1_000.0
            suffix++
        }
        val str = mantissa.roundToLong().toString() +
                (if (suffix < 0) "" else SHORT_SUFFIXES[suffix].let { if (capital) it else it.lowercase() })
        if (negative && !absolute) "-\$$str" else "\$$str"
    }

    override fun toString(): String = toString(absolute = false, forceCents = false)

    public fun toDouble(): Double = inCents / 100.0
    public val absoluteValue: Cents get() = Cents(inCents.absoluteValue)

    public operator fun plus(other: Cents): Cents = Cents(inCents + other.inCents)
    public operator fun minus(other: Cents): Cents = Cents(inCents - other.inCents)

    public operator fun times(other: Long): Cents = Cents(inCents * other)
    public operator fun times(other: Int): Cents = Cents(inCents * other)
    // Long * Float evaluates as a Float, whose 24-bit mantissa cannot hold amounts over ~$167,772.16,
    // so these widen to Double before doing the math.
    public operator fun times(other: Float): Cents = Cents((inCents * other.toDouble()).roundToLong())
    public operator fun times(other: Double): Cents = Cents((inCents * other).roundToLong())

    public operator fun div(other: Long): Cents = Cents(inCents / other)
    public operator fun div(other: Int): Cents = Cents(inCents / other)
    public operator fun div(other: Float): Cents = Cents((inCents / other.toDouble()).roundToLong())
    public operator fun div(other: Double): Cents = Cents((inCents / other).roundToLong())

    public operator fun unaryMinus(): Cents = Cents(-inCents)

    public operator fun div(other: Cents): Double = inCents.toDouble() / other.inCents

    public operator fun rangeTo(other: Cents): CentsRange = CentsRange(this, other)
    public operator fun rangeUntil(other: Cents): CentsRange = CentsRange(this, other - 1.cents)

    public companion object {
        public val Long.cents: Cents get() = Cents(this)
        public val Int.cents: Cents get() = Cents(this.toLong())
        public val Float.cents: Cents get() = Cents(this.roundToLong())
        public val Double.cents: Cents get() = Cents(this.roundToLong())
        public val Long.dollars: Cents get() = Cents(this * 100)
        public val Int.dollars: Cents get() = Cents(this.toLong() * 100)
        public val Float.dollars: Cents get() = Cents(this.toDouble().times(100).roundToLong())
        public val Double.dollars: Cents get() = Cents(this.times(100).roundToLong())

        /**
         * Reads a US currency amount, ignoring any currency symbol and grouping separators, e.g. `"$1,234.56"`.
         * A missing whole part is zero (`".50"` is fifty cents) and decimal digits past the second are truncated.
         * Returns `null` if [string] holds no digits, has more than one [decimalSeparator], or names an amount
         * too large to hold in a [Long] of cents.
         */
        public fun parseOrNull(string: String, decimalSeparator: Char = '.'): Cents? {
            val trimmed = string.trim()
            // The sign may sit on either side of a currency symbol ("-$5" and "$-5" are both seen in the wild),
            // so anything ahead of the first digit counts as sign position.
            val firstDigit = trimmed.indexOfFirst { it.isDigit() }
            if (firstDigit < 0) return null
            val negative = trimmed.take(firstDigit).contains('-')

            val units = trimmed.filter { it.isDigit() || it == decimalSeparator }.split(decimalSeparator)
            if (units.size !in 1..2) return null
            val dollars = units[0].ifEmpty { "0" }.toLongOrNull() ?: return null
            // Only digits survive the filter above, so a non-blank fractional part always parses.
            val cents = units.getOrNull(1)?.takeUnless { it.isBlank() }?.let {
                val value = it.take(2).toLong()
                if (it.length == 1) value * 10 else value
            } ?: 0L

            // Reject what cannot be represented rather than silently wrapping around.
            if (dollars > Long.MAX_VALUE / 100) return null
            val total = dollars * 100
            if (total > Long.MAX_VALUE - cents) return null
            return Cents(if (negative) -(total + cents) else total + cents)
        }

        /** As [parseOrNull], but throws [IllegalArgumentException] instead of returning `null`. */
        public fun parse(string: String, decimalSeparator: Char = '.'): Cents =
            parseOrNull(string, decimalSeparator = decimalSeparator) ?: throw IllegalArgumentException("$string is not a valid US currency format.")
    }
}

@JvmInline
public value class CentsRange(public val rangeInUsCents: LongRange) : ClosedRange<Cents>, OpenEndRange<Cents>,
    Iterable<Cents> {
    public constructor(start: Cents, endInclusive: Cents) : this(
        LongRange(
            start.inCents,
            endInclusive.inCents
        )
    )

    override val start: Cents get() = Cents(rangeInUsCents.first)
    override val endInclusive: Cents get() = Cents(rangeInUsCents.last)
    @Deprecated("Can throw an exception when it's impossible to represent the value with Long type, for example, when the range includes MAX_VALUE. It's recommended to use 'endInclusive' property that doesn't throw.")
    @Suppress("DEPRECATION")
    override val endExclusive: Cents get() = Cents(rangeInUsCents.endExclusive)

    override fun contains(value: Cents): Boolean = rangeInUsCents.contains(value.inCents)
    override fun isEmpty(): Boolean = rangeInUsCents.isEmpty()

    override fun iterator(): Iterator<Cents> = object : Iterator<Cents> {
        val inner = rangeInUsCents.iterator()
        override fun hasNext(): Boolean = inner.hasNext()
        override fun next(): Cents = Cents(inner.next())
    }

    override fun toString(): String = "$start..$endInclusive"
}
