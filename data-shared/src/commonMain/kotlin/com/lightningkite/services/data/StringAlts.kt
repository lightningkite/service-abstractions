package com.lightningkite.services.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

/**
 * Base interface for string wrapper types that normalize their content.
 *
 * Provides a common interface for value classes that wrap strings with specific
 * normalization rules (trimming, case-folding, email/phone formatting).
 *
 * All implementations are comparable based on their [raw] string value.
 */
public interface IsRawString : Comparable<IsRawString> {
    /**
     * The normalized string value.
     *
     * This is the canonical representation after normalization rules have been applied.
     */
    public val raw: String

    /**
     * Transforms the raw string and returns a new instance.
     *
     * @param action Transformation to apply to the raw string
     * @return New instance with transformed value
     */
    public fun mapRaw(action: (String) -> String): IsRawString

    override fun compareTo(other: IsRawString): Int = raw.compareTo(other.raw)

    public companion object {
        /**
         * Serial names of all [IsRawString] implementations.
         *
         * Used for polymorphic serialization and type identification.
         */
        public val serialNames: Set<String> = setOf(
            "com.lightningkite.services.data.TrimmedString",
            "com.lightningkite.services.data.CaselessString",
            "com.lightningkite.services.data.TrimmedCaselessString",
            "com.lightningkite.services.data.EmailAddress",
            "com.lightningkite.services.data.PhoneNumber",
        )
    }
}

/**
 * Serializer that automatically trims strings on deserialization.
 *
 * Use this to normalize user input by removing leading/trailing whitespace.
 *
 * ## Usage
 * ```kotlin
 * @Serializable
 * data class User(
 *     @Serializable(with = TrimOnSerialize::class)
 *     val name: String
 * )
 * ```
 */
public object TrimOnSerialize : KSerializer<String> {
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().trim()
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlin.String/TrimOnSerialize", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String): Unit = encoder.encodeString(value)
}

/**
 * Serializer that automatically converts strings to lowercase on deserialization.
 *
 * Use this for case-insensitive matching (e.g., usernames, tags).
 *
 * ## Usage
 * ```kotlin
 * @Serializable
 * data class User(
 *     @Serializable(with = LowercaseOnSerialize::class)
 *     val username: String
 * )
 * ```
 */
public object LowercaseOnSerialize : KSerializer<String> {
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().lowercase()
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlin.String/LowercaseOnSerialize", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String): Unit = encoder.encodeString(value)
}

/**
 * Serializer that automatically trims and lowercases strings on deserialization.
 *
 * Combines [TrimOnSerialize] and [LowercaseOnSerialize].
 *
 * ## Usage
 * ```kotlin
 * @Serializable
 * data class User(
 *     @Serializable(with = TrimLowercaseOnSerialize::class)
 *     val username: String
 * )
 * ```
 */
public object TrimLowercaseOnSerialize : KSerializer<String> {
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().trim().lowercase()
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlin.String/TrimLowercaseOnSerialize", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String): Unit = encoder.encodeString(value)
}

// Decoding goes straight through the constructor, so its check is what rejects untrusted input.
// SerializationException is an IllegalArgumentException that also reads as a bad request rather than a server fault.
private inline fun requireNormalized(isNormalized: Boolean, message: () -> String) {
    if (!isNormalized) throw SerializationException(message())
}

/**
 * String value class that guarantees no leading or trailing whitespace.
 *
 * Use this type when you want to ensure strings are always trimmed (e.g., user names, titles).
 * The constructor is deprecated - use [String.trimmed] instead.
 *
 * ## Usage
 * ```kotlin
 * @Serializable
 * data class User(
 *     val name: TrimmedString
 * )
 *
 * val user = User(name = "  John  ".trimmed())  // Stored as "John"
 * ```
 */
@Serializable
@JvmInline
public value class TrimmedString @Deprecated("Use String.trimmed()") constructor(override val raw: String) :
    IsRawString {
    init {
        requireNormalized(raw == raw.trim()) { "TrimmedString has leading or trailing whitespace: '$raw'" }
    }

    override fun toString(): String = raw
    override fun mapRaw(action: (String) -> String): TrimmedString = raw.let(action).trimmed()
}

/**
 * Converts this string to a [TrimmedString] by removing leading and trailing whitespace.
 *
 * @return TrimmedString with whitespace removed
 */
@Suppress("DEPRECATION", "NOTHING_TO_INLINE")
public inline fun String.trimmed(): TrimmedString = TrimmedString(this.trim())


@Serializable
@JvmInline
public value class CaselessString @Deprecated("Use String.caseless()") constructor(override val raw: String) :
    IsRawString {
    init {
        requireNormalized(raw == raw.lowercase()) { "CaselessString has uppercase characters: '$raw'" }
    }

    override fun toString(): String = raw
    override fun mapRaw(action: (String) -> String): CaselessString = raw.let(action).caseless()
}

@Suppress("DEPRECATION", "NOTHING_TO_INLINE")
public inline fun String.caseless(): CaselessString = CaselessString(this.lowercase())


@Serializable
@JvmInline
public value class TrimmedCaselessString @Deprecated("Use String.trimmedCaseless()") constructor(override val raw: String) :
    IsRawString {
    init {
        requireNormalized(raw == raw.trim().lowercase()) {
            "TrimmedCaselessString has surrounding whitespace or uppercase characters: '$raw'"
        }
    }

    override fun toString(): String = raw
    override fun mapRaw(action: (String) -> String): TrimmedCaselessString = raw.let(action).trimmedCaseless()
}

@Suppress("DEPRECATION", "NOTHING_TO_INLINE")
public inline fun String.trimmedCaseless(): TrimmedCaselessString = TrimmedCaselessString(this.trim().lowercase())


/**
 * String value class representing a validated email address.
 *
 * Automatically normalizes email addresses to lowercase and trimmed format.
 * Validates against RFC 5322 email format on creation. The one exception is the empty string ([EMPTY]),
 * which means "not provided yet"; only the primary constructor produces it, and [toEmailAddress] still throws on blank input.
 *
 * ## Features
 *
 * - **Sub-addressing detection**: Identifies plus-addressing (gmail+tag@gmail.com) and Yahoo dash-addressing
 * - **Gmail normalization**: Removes dots from Gmail local parts (jo.hn@gmail.com == john@gmail.com)
 * - **Comment stripping**: Removes RFC 5322 comments from local part
 * - **mailto: URL generation**: Easy conversion to mailto links
 *
 * ## Usage
 * ```kotlin
 * val email = "User@Example.COM".toEmailAddress()  // Normalized to "user@example.com"
 * val domain = email.domain  // "example.com"
 * val localPart = email.localPart  // "user"
 *
 * // Sub-addressing
 * val tagged = "user+newsletters@example.com".toEmailAddress()
 * val baseAccount = tagged.probableAccount  // "user"
 * val tag = tagged.subAddress  // "newsletters"
 * ```
 *
 * @throws IllegalArgumentException if the string is not a valid email address
 */
@Serializable
@JvmInline
public value class EmailAddress @Deprecated("Use String.toEmailAddress()") constructor(override val raw: String) : IsRawString {
    init {
        requireNormalized(raw.isEmpty() || (raw == raw.trim().lowercase() && emailRegex.matches(raw))) {
            "$raw is not a lowercase, trimmed email address."
        }
    }

    public companion object {
        /** The empty placeholder for "not provided yet", for use as a default in draft models. */
        @Suppress("DEPRECATION")
        public val EMPTY: EmailAddress = EmailAddress("")

        private val commentRegex = Regex("\\([^)]+\\)")

        /** RFC 5322-compliant email validation regex. */
        public val emailRegex: Regex =
            Regex("(?:[a-z0-9!#\$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#\$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])")
    }

    /** Whether this is [EMPTY], meaning "not provided yet". */
    public fun isEmpty(): Boolean = raw.isEmpty()

    /** Whether this holds a real value, i.e. is not [EMPTY]. */
    public fun isNotEmpty(): Boolean = raw.isNotEmpty()

    override fun toString(): String = raw

    /**
     * The sub-address delimiter character for this email's domain.
     *
     * Returns '-' for yahoo.com, '+' for all other domains (including Gmail).
     */
    public val subAddressChar: Char
        get() = when (domain) {
            "yahoo.com" -> '-'
            else -> '+'
        }

    /** The domain part after the @ symbol (e.g., "example.com"). */
    public val domain: String get() = raw.substringAfter('@')

    /** The local part before the @ symbol (e.g., "user+tag"). */
    public val localPart: String get() = raw.substringBefore('@')

    /**
     * The probable base account name without sub-addressing or Gmail dot-ignoring.
     *
     * - Strips sub-addressing (user+tag → user)
     * - Removes dots for Gmail (u.s.e.r@gmail.com → user@gmail.com)
     * - Strips RFC 5322 comments
     */
    public val probableAccount: String
        get() = raw.substringBefore('@').substringBefore(subAddressChar).let {
            if (domain == "gmail.com") it.replace(".", "")
            else it
        }.replace(commentRegex, "")

    /** The sub-address tag (e.g., "newsletters" from "user+newsletters@example.com"), or empty string if none. */
    public val subAddress: String get() = raw.substringBefore('@').substringAfter(subAddressChar, "")

    /**
     * Returns the probable base email address without sub-addressing.
     *
     * Useful for detecting duplicate accounts or grouping emails by user.
     */
    public fun toProbableBaseEmailAddress(): EmailAddress =
        if (isEmpty()) this else "$probableAccount@$domain".toEmailAddress()

    /** Converts to a mailto: URL. */
    public val url: String get() = "mailto:$raw"

    override fun mapRaw(action: (String) -> String): EmailAddress = raw.let(action).toEmailAddress()
}

/**
 * Converts this string to an [EmailAddress], returning null if invalid.
 *
 * Automatically trims whitespace and converts to lowercase before validation.
 *
 * @return Validated and normalized EmailAddress, or null if invalid
 */
@Suppress("DEPRECATION")
public fun String.toEmailAddressOrNull(): EmailAddress? {
    val fixed = this.trim().lowercase()
    return if (EmailAddress.emailRegex.matches(fixed)) EmailAddress(fixed) else null
}

/**
 * Converts this string to an [EmailAddress], normalizing and validating it.
 *
 * Automatically trims whitespace and converts to lowercase before validation.
 *
 * @return Validated and normalized EmailAddress
 * @throws IllegalArgumentException if the string is not a valid email address
 */
@Suppress("DEPRECATION")
public fun String.toEmailAddress(): EmailAddress =
    toEmailAddressOrNull() ?: throw IllegalArgumentException("$this is not an email address.")

/**
 * String value class representing a phone number in E.164 format.
 *
 * Stores phone numbers as: `+` + country code + subscriber number (no formatting).
 * Follows RFC 2806 for tel: URL format. The one exception is the empty string ([EMPTY]), which means
 * "not provided yet"; only the primary constructor produces it, and [toPhoneNumber] still throws on blank input.
 *
 * ## Features
 *
 * - **E.164 storage**: All numbers stored as +{country}{subscriber} (e.g., +18015551234)
 * - **US default**: numbers without a + are US: 10 digits get +1, 11 digits starting with 1 get +
 * - **Formatting**: toString() formats US numbers; toString([Region]) formats the other supported regions
 * - **tel: URL generation**: Easy conversion to tel links
 *
 * ## Usage
 * ```kotlin
 * val phone = "(801) 555-1234".toPhoneNumber()  // Stored as "+18015551234"
 * val intl = "+44 20 7946 0958".toPhoneNumber()  // Stored as "+442079460958"
 *
 * println(phone)  // Displays: "+1 (801) 555-1234"
 * println(intl.toString(PhoneNumber.Region.UK))  // Displays: "+44 20 7946 0958"
 * val link = phone.url  // "tel:+18015551234"
 * ```
 *
 * ## Limitations
 *
 * - Numbers outside the supported [Region]s display as [raw]
 * - Assumes US for 10-digit numbers without country code
 *
 * @throws IllegalArgumentException if the string is not a valid phone number format
 */
@Serializable
@JvmInline
public value class PhoneNumber @Deprecated("Use String.toPhoneNumber()") constructor(override val raw: String) :
    IsRawString {
    init {
        requireNormalized(raw.isEmpty() || e164.matches(raw)) {
            "$raw is not a phone number in '+<country code><number>' form."
        }
    }

    public companion object {
        /** The empty placeholder for "not provided yet", for use as a default in draft models. */
        @Suppress("DEPRECATION")
        public val EMPTY: PhoneNumber = PhoneNumber("")

        internal val e164 = Regex("""\+\d{8,15}""")
    }

    /** Whether this is [EMPTY], meaning "not provided yet". */
    public fun isEmpty(): Boolean = raw.isEmpty()

    /** Whether this holds a real value, i.e. is not [EMPTY]. */
    public fun isNotEmpty(): Boolean = raw.isNotEmpty()

    /**
     * A country whose numbers [toString] can lay out in that country's usual digit grouping.
     *
     * @property trunkPrefix what callers within the country dial in place of the country code.
     */
    public enum class Region(public val countryCode: String, public val trunkPrefix: String) {
        /** United States, and the rest of the North American Numbering Plan: `+1 (801) 555-1234` or `(801) 555-1234`. */
        US("1", ""),

        /** United Kingdom: `+44 20 7946 0958` or `020 7946 0958` for London-style numbers, `+44 7911 123456` or `07911 123456` otherwise. */
        UK("44", "0"),

        /** France: `+33 1 23 45 67 89` or `01 23 45 67 89`. */
        FR("33", "0"),

        /** Spain: `+34 912 345 678` or `912 345 678`. */
        ES("34", ""),

        /** Australia: `+61 412 345 678` or `0412 345 678` for mobiles, `+61 2 1234 5678` or `02 1234 5678` for landlines. */
        AU("61", "0"),

        /** India: `+91 98765 43210` or `098765 43210`. */
        IN("91", "0"),
    }

    /** Returns this number formatted as a [Region.US] number with its country code, or [raw] if it isn't one. */
    override fun toString(): String = toString(Region.US, includeCountryCode = true)

    /**
     * Returns this number laid out in [region]'s digit grouping, e.g. `+44 20 7946 0958`.
     *
     * When [includeCountryCode] is `false`, returns the form dialed from within [region] instead,
     * e.g. `020 7946 0958`. Returns [raw] unchanged if this number has a different country code than
     * [region], or doesn't have the digit count numbers in [region] have.
     */
    public fun toString(region: Region, includeCountryCode: Boolean = true): String {
        val prefix = "+${region.countryCode}"
        if (!raw.startsWith(prefix)) return raw
        val n = raw.removePrefix(prefix)
        val grouped = when (region) {
            Region.US -> if (n.length == 10) "(${n.take(3)}) ${n.substring(3, 6)}-${n.substring(6)}" else null
            Region.UK -> if (n.startsWith('2')) n.grouped(2, 4, 4) else n.grouped(4, 6)
            Region.FR -> n.grouped(1, 2, 2, 2, 2)
            Region.ES -> n.grouped(3, 3, 3)
            Region.AU -> if (n.startsWith('4')) n.grouped(3, 3, 3) else n.grouped(1, 4, 4)
            Region.IN -> n.grouped(5, 5)
        }
        return when {
            grouped == null -> raw
            includeCountryCode -> "$prefix $grouped"
            else -> region.trunkPrefix + grouped
        }
    }

    /**
     * Returns the bare digits dialed from within [region], e.g. `8015551234` or `02079460958`.
     *
     * Returns [raw] unchanged if this number has a different country code than [region].
     */
    public fun digits(region: Region): String {
        val prefix = "+${region.countryCode}"
        if (!raw.startsWith(prefix)) return raw
        return region.trunkPrefix + raw.removePrefix(prefix)
    }

    /** Converts to a tel: URL. */
    public val url: String get() = "tel:$raw"

    override fun mapRaw(action: (String) -> String): PhoneNumber = raw.let(action).toPhoneNumber()
}

/** Characters to strip from phone number input. */
private val punctuation = setOf(' ', '-', '.', ',', '(', ')', '[', ']', '<', '>')

/** Splits this string into space-separated groups of [sizes], or returns null if they don't add up to its length. */
private fun String.grouped(vararg sizes: Int): String? {
    if (sizes.sum() != length) return null
    var start = 0
    return sizes.joinToString(" ") { size -> substring(start, start + size).also { start += size } }
}


/**
 * Converts this string to a [PhoneNumber], returning null if invalid.
 *
 * Follows the same rules as [toPhoneNumber].
 *
 * @return Normalized PhoneNumber in E.164 format, or null if invalid
 */
@Suppress("DEPRECATION")
public fun String.toPhoneNumberOrNull(): PhoneNumber? {
    val fixed = this.filter { it !in punctuation }
    val candidate = when {
        fixed.startsWith('+') -> fixed
        fixed.length == 10 -> "+1$fixed"
        fixed.length == 11 && fixed.startsWith('1') -> "+$fixed"
        else -> return null
    }
    return if (PhoneNumber.e164.matches(candidate)) PhoneNumber(candidate) else null
}

/**
 * Converts this string to a [PhoneNumber] in E.164 format.
 *
 * - Strips common punctuation and spaces; what remains must be digits, optionally after a leading +
 * - With +: kept as-is, and must have 8 to 15 digits (E.164)
 * - Without +: 10 digits get the +1 (US) prefix, 11 digits starting with 1 get +; anything else is invalid
 *
 * @return Normalized PhoneNumber in E.164 format
 * @throws IllegalArgumentException if format is invalid
 */
public fun String.toPhoneNumber(): PhoneNumber =
    toPhoneNumberOrNull() ?: throw IllegalArgumentException("Phone numbers should begin with a '+' and your country code.")