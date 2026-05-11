package com.lightningkite.services.ai

/**
 * Detects sensitive data within text. Used by [SanitizingLlmAccess] to find
 * values that should be replaced with placeholders before sending to an LLM.
 */
public sealed interface SensitiveDataDetector {
    /**
     * Find all ranges in [text] that contain sensitive data.
     * Ranges may overlap; the caller deduplicates by extracted string value.
     */
    public fun findAll(text: String): List<IntRange>
}

/**
 * Detects sensitive data matching a [Regex] pattern.
 *
 * @param name Human-readable label for logging/debugging (e.g. "ssn", "email").
 * @param pattern Regex whose matches are treated as sensitive.
 */
public class RegexDetector(
    public val name: String,
    public val pattern: Regex,
) : SensitiveDataDetector {
    override fun findAll(text: String): List<IntRange> =
        pattern.findAll(text).map { it.range }.toList()
}

/**
 * Detects exact occurrences of explicitly provided sensitive strings.
 *
 * Use this when you know the specific values to redact (e.g. API keys loaded
 * from configuration) rather than relying on pattern matching.
 */
public class ExplicitValueDetector(
    public val values: Set<String>,
) : SensitiveDataDetector {
    override fun findAll(text: String): List<IntRange> = buildList {
        for (value in values) {
            if (value.isEmpty()) continue
            var start = 0
            while (true) {
                val idx = text.indexOf(value, start)
                if (idx < 0) break
                add(idx until idx + value.length)
                start = idx + 1
            }
        }
    }
}

/** Common regex patterns for sensitive data. */
public object CommonPatterns {
    /** US Social Security Numbers: 123-45-6789 */
    public val SSN: RegexDetector = RegexDetector("ssn", Regex("""\b\d{3}-\d{2}-\d{4}\b"""))

    /** Credit/debit card numbers (13-19 digits, optionally separated by spaces or dashes). */
    public val CREDIT_CARD: RegexDetector = RegexDetector(
        "credit_card",
        Regex("""\b\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{1,7}\b""")
    )

    /** Email addresses. */
    public val EMAIL: RegexDetector = RegexDetector(
        "email",
        Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
    )

    /** US phone numbers: (555) 123-4567, 555-123-4567, +1 555 123 4567, etc. */
    public val PHONE_US: RegexDetector = RegexDetector(
        "phone_us",
        Regex("""\b(?:\+1[\s-]?)?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}\b""")
    )

    /** API keys with common prefixes: sk-..., pk_..., api-..., token_..., key-... */
    public val API_KEY: RegexDetector = RegexDetector(
        "api_key",
        Regex("""\b(?:sk|pk|api|key|token)[-_][A-Za-z0-9]{20,}\b""")
    )

    /** All built-in patterns. */
    public val ALL: List<RegexDetector> = listOf(SSN, CREDIT_CARD, EMAIL, PHONE_US, API_KEY)
}
