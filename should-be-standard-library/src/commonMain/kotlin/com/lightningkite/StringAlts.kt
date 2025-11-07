package com.lightningkite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
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
            "com.lightningkite.TrimmedString",
            "com.lightningkite.CaselessString",
            "com.lightningkite.TrimmedCaselessString",
            "com.lightningkite.EmailAddress",
            "com.lightningkite.PhoneNumber",
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
public object TrimOnSerialize: KSerializer<String> {
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().trim()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kotlin.String/TrimOnSerialize", PrimitiveKind.STRING)
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
public object LowercaseOnSerialize: KSerializer<String> {
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().lowercase()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kotlin.String/LowercaseOnSerialize", PrimitiveKind.STRING)
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
public object TrimLowercaseOnSerialize: KSerializer<String> {
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().trim().lowercase()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kotlin.String/TrimLowercaseOnSerialize", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String): Unit = encoder.encodeString(value)
}

/** Serializer for [TrimmedString]. */
public object TrimmedStringSerializer : KSerializer<TrimmedString> {
    override fun deserialize(decoder: Decoder): TrimmedString = decoder.decodeString().trimmed()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.TrimmedString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: TrimmedString): Unit = encoder.encodeString(value.raw)
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
@Serializable(TrimmedStringSerializer::class)
@JvmInline
public value class TrimmedString @Deprecated("Use String.trimmed()") constructor(override val raw: String) : IsRawString {
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



public object CaselessStringSerializer : KSerializer<CaselessString> {
    override fun deserialize(decoder: Decoder): CaselessString = decoder.decodeString().caseless()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.CaselessString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: CaselessString): Unit = encoder.encodeString(value.raw)
}

@Serializable(CaselessStringSerializer::class)
@JvmInline
public value class CaselessString @Deprecated("Use String.caseless()") constructor(override val raw: String) : IsRawString {
    override fun toString(): String = raw
    override fun mapRaw(action: (String) -> String): CaselessString = raw.let(action).caseless()
}

@Suppress("DEPRECATION", "NOTHING_TO_INLINE")
public inline fun String.caseless(): CaselessString = CaselessString(this.lowercase())




public object TrimmedCaselessStringSerializer : KSerializer<TrimmedCaselessString> {
    override fun deserialize(decoder: Decoder): TrimmedCaselessString = decoder.decodeString().trimmedCaseless()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.TrimmedCaselessString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: TrimmedCaselessString): Unit = encoder.encodeString(value.raw)
}

@Serializable(TrimmedCaselessStringSerializer::class)
@JvmInline
public value class TrimmedCaselessString @Deprecated("Use String.trimmedCaseless()") constructor(override val raw: String) : IsRawString {
    override fun toString(): String = raw
    override fun mapRaw(action: (String) -> String): TrimmedCaselessString = raw.let(action).trimmedCaseless()
}

@Suppress("DEPRECATION", "NOTHING_TO_INLINE")
public inline fun String.trimmedCaseless(): TrimmedCaselessString = TrimmedCaselessString(this.trim().lowercase())





/** Serializer for [EmailAddress]. */
public object EmailAddressSerializer : KSerializer<EmailAddress> {
    override fun deserialize(decoder: Decoder): EmailAddress = decoder.decodeString().toEmailAddress()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.EmailAddress", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: EmailAddress): Unit = encoder.encodeString(value.raw)
}

/**
 * String value class representing a validated email address.
 *
 * Automatically normalizes email addresses to lowercase and trimmed format.
 * Validates against RFC 5322 email format on creation.
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
@Serializable(EmailAddressSerializer::class)
@JvmInline
public value class EmailAddress @Deprecated("Use String.toEmailAddress()") constructor(override val raw: String) : IsRawString {
    public companion object {
        private val commentRegex = Regex("\\([^)]+\\)")
    }
    override fun toString(): String = raw

    /**
     * The sub-address delimiter character for this email's domain.
     *
     * Returns '-' for yahoo.com, '+' for all other domains (including Gmail).
     */
    public val subAddressChar: Char get() = when(domain) {
        "yahoo.com", -> '-'
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
    public val probableAccount: String get() = raw.substringBefore('@').substringBefore(subAddressChar).let {
        if(domain == "gmail.com") it.replace(".", "")
        else it
    }.replace(commentRegex, "")

    /** The sub-address tag (e.g., "newsletters" from "user+newsletters@example.com"), or empty string if none. */
    public val subAddress: String get() = raw.substringBefore('@').substringAfter(subAddressChar, "")

    /**
     * Returns the probable base email address without sub-addressing.
     *
     * Useful for detecting duplicate accounts or grouping emails by user.
     */
    public fun toProbableBaseEmailAddress(): EmailAddress = "$probableAccount@$domain".toEmailAddress()

    /** Converts to a mailto: URL. */
    public val url: String get() = "mailto:$raw"

    override fun mapRaw(action: (String) -> String): EmailAddress = raw.let(action).toEmailAddress()
}

/** RFC 5322-compliant email validation regex. */
private val emailRegex = Regex("(?:[a-z0-9!#\$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#\$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])")

/**
 * Converts this string to an [EmailAddress], normalizing and validating it.
 *
 * Automatically trims whitespace and converts to lowercase before validation.
 *
 * @return Validated and normalized EmailAddress
 * @throws IllegalArgumentException if the string is not a valid email address
 */
@Suppress("DEPRECATION")
public fun String.toEmailAddress(): EmailAddress {
    val fixed = this.trim().lowercase()
    if (emailRegex.matches(fixed)) return EmailAddress(fixed)
    else throw IllegalArgumentException("$fixed is not an email address.")
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
    return if (emailRegex.matches(fixed)) EmailAddress(fixed) else null
}


/** Serializer for [PhoneNumber]. */
public object PhoneNumberSerializer : KSerializer<PhoneNumber> {
    override fun deserialize(decoder: Decoder): PhoneNumber = decoder.decodeString().toPhoneNumber()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.PhoneNumber", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: PhoneNumber): Unit = encoder.encodeString(value.raw)
}

/**
 * String value class representing a phone number in E.164 format.
 *
 * Stores phone numbers as: `+` + country code + subscriber number (no formatting).
 * Follows RFC 2806 for tel: URL format.
 *
 * ## Features
 *
 * - **E.164 storage**: All numbers stored as +{country}{subscriber} (e.g., +18015551234)
 * - **US default**: 10-digit numbers automatically get +1 prefix
 * - **Formatting**: toString() provides human-readable format for US numbers
 * - **tel: URL generation**: Easy conversion to tel links
 *
 * ## Usage
 * ```kotlin
 * val phone = "(801) 555-1234".toPhoneNumber()  // Stored as "+18015551234"
 * val intl = "+44 20 7946 0958".toPhoneNumber()  // Stored as "+442079460958"
 *
 * println(phone)  // Displays: "+1 (801) 555-1234"
 * val link = phone.url  // "tel:+18015551234"
 * ```
 *
 * ## Limitations
 *
 * - Only US (+1) numbers get formatted toString()
 * - Assumes US for 10-digit numbers without country code
 * - More localized formats could be added in the future (see TODO in code)
 *
 * @throws IllegalArgumentException if the string is not a valid phone number format
 */
@Serializable(PhoneNumberSerializer::class)
@JvmInline
public value class PhoneNumber @Deprecated("Use String.toPhoneNumber()") constructor(override val raw: String) : IsRawString {
    // TODO: API Recommendation - Add support for more regional formatting (UK, EU, etc.)
    //  Currently only US +1 numbers get formatted in toString()
    override fun toString(): String = when {
        //+18013693729
        //012345678901
        raw.startsWith("+1") -> "+1 (${raw.substring(2, 5)}) ${raw.substring(5, 8)}-${raw.substring(8)}"
        else -> raw
    }

    /** Converts to a tel: URL. */
    public val url: String get() = "tel:$raw"

    override fun mapRaw(action: (String) -> String): PhoneNumber = raw.let(action).toPhoneNumber()
}

/** Characters to strip from phone number input. */
private val punctuation = setOf(' ', '-', '.', ',', '(', ')', '[', ']', '<', '>')

/**
 * Converts this string to a [PhoneNumber] in E.164 format.
 *
 * - Strips common punctuation and spaces
 * - Numbers starting with + are kept as-is
 * - 10-digit numbers get +1 (US) prefix automatically
 *
 * @return Normalized PhoneNumber in E.164 format
 * @throws IllegalArgumentException if format is invalid
 */
@Suppress("DEPRECATION")
public fun String.toPhoneNumber(): PhoneNumber {
    val fixed = this.lowercase().filter { it !in punctuation }

    return if (fixed.startsWith('+')) PhoneNumber(fixed)
    else if (fixed.length == 10) PhoneNumber("+1$fixed")
    else throw IllegalArgumentException("Phone numbers should begin with a '+' and your country code.")
}

/**
 * Converts this string to a [PhoneNumber], returning null if invalid.
 *
 * @return Normalized PhoneNumber in E.164 format, or null if invalid
 */
@Suppress("DEPRECATION")
public fun String.toPhoneNumberOrNull(): PhoneNumber? {
    val fixed = this.lowercase().filter { it !in punctuation }

    return if (fixed.startsWith('+')) PhoneNumber(fixed)
    else if (fixed.length == 10) PhoneNumber("+1$fixed")
    else null
}
