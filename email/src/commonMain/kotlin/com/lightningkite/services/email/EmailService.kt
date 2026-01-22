package com.lightningkite.services.email

import com.lightningkite.EmailAddress
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.data.TypedData
import com.lightningkite.toEmailAddress
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.appendHTML
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.jvm.JvmName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Service abstraction for sending transactional and bulk emails.
 *
 * EmailService provides a unified interface for sending emails across different providers
 * (SMTP, SendGrid, AWS SES, etc.). Applications can switch email providers via configuration
 * without code changes.
 *
 * ## Available Implementations
 *
 * - **ConsoleEmailService** (`console`) - Prints emails to console (development/testing)
 * - **TestEmailService** (`test`) - Collects emails in memory for testing
 * - **JavaSmtpEmailService** (`smtp://`) - SMTP implementation (Gmail, SendGrid, Office 365, etc.)
 *
 * ## Configuration
 *
 * Configure via [Settings] using URL strings:
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val email: EmailService.Settings = EmailService.Settings("smtp://user:pass@smtp.gmail.com:587")
 * )
 *
 * val context = SettingContext(...)
 * val emailService: EmailService = settings.email("mailer", context)
 * ```
 *
 * ## Basic Usage
 *
 * ```kotlin
 * // Send single email
 * emailService.send(Email(
 *     subject = "Welcome!",
 *     to = listOf(EmailAddressWithName("user@example.com", "John Doe")),
 *     html = "<h1>Welcome to our service!</h1>",
 *     plainText = "Welcome to our service!"
 * ))
 * ```
 *
 * ## Bulk Email
 *
 * For sending personalized emails to multiple recipients:
 *
 * ```kotlin
 * val template = Email(
 *     subject = "Hello {{name}}!",
 *     to = listOf(),  // Will be filled by personalizations
 *     html = "<p>Hi {{name}}, your order {{orderId}} is ready!</p>"
 * )
 *
 * val personalizations = listOf(
 *     EmailPersonalization(
 *         to = listOf(EmailAddressWithName("alice@example.com")),
 *         substitutions = mapOf("name" to "Alice", "orderId" to "12345")
 *     ),
 *     EmailPersonalization(
 *         to = listOf(EmailAddressWithName("bob@example.com")),
 *         substitutions = mapOf("name" to "Bob", "orderId" to "67890")
 *     )
 * )
 *
 * emailService.sendBulk(template, personalizations)
 * ```
 *
 * ## Features
 *
 * - **HTML and plain text**: All emails include both formats
 * - **Attachments**: Support for inline and regular attachments
 * - **Custom headers**: Add provider-specific headers
 * - **CC/BCC**: Standard email features
 * - **Personalization**: Template substitution for bulk sends
 *
 * ## Health Checks
 *
 * Health checks send a test email to verify connectivity. The default implementation
 * sends to "health-check@example.com", which providers may reject. Override [healthCheck]
 * to use a valid recipient address.
 *
 * ## Important Gotchas
 *
 * - **From address**: Many providers require verified sender addresses
 * - **Rate limits**: Most email providers have rate limits (check provider docs)
 * - **Plain text fallback**: Always provide both HTML and plain text
 * - **Attachment size**: Most providers limit total email size to 10-25MB
 * - **Spam filters**: Avoid spam trigger words, use verified domains
 * - **Health check**: Default health check uses fake address, override for production
 * - **Bulk sending**: [sendBulk] is sequential, not parallel - consider provider rate limits
 *
 * @see Email
 * @see EmailPersonalization
 */
public interface EmailService : Service {
    /**
     * Configuration for instantiating an EmailService.
     *
     * The URL scheme determines the email provider:
     * - `console` - Print emails to console (default)
     * - `test` - Collect emails in memory for testing
     * - `smtp://user:pass@host:port` - SMTP provider (requires email-javasmtp module)
     *
     * @property url Connection string defining the email provider and credentials
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "console"
    ) : Setting<EmailService> {
        public companion object : UrlSettingParser<EmailService>() {
            init {
                register("console") { name, _, context -> ConsoleEmailService(name, context) }
                register("test") { name, _, context -> TestEmailService(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): EmailService {
            return parse(name, url, context)
        }
    }

    /**
     * Sends a single email.
     */
    public suspend fun send(email: Email)

    /**
     * Sends multiple personalized emails based on a template.
     */
    public suspend fun sendBulk(template: Email, personalizations: List<EmailPersonalization>): Unit =
        personalizations.forEach {
            send(it(template))
        }

    /**
     * Sends multiple emails.
     */
    public suspend fun sendBulk(emails: Collection<Email>): Unit = emails.forEach {
        send(it)
    }

    /**
     * The frequency at which health checks should be performed.
     */
    public override val healthCheckFrequency: Duration
        get() = 6.hours

    /**
     * Checks the health of the email service by sending a test email.
     */
    public override suspend fun healthCheck(): HealthStatus {
        return try {
            send(
                Email(
                    subject = "Health Check",
                    to = listOf(EmailAddressWithName("health-check@example.com")),
                    plainText = "This is a test message to verify the email service is working."
                )
            )
            HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}

/**
 * Represents an email message with recipients, content, and attachments.
 *
 * ## Usage
 *
 * ```kotlin
 * // HTML email
 * val email = Email(
 *     subject = "Welcome!",
 *     to = listOf(EmailAddressWithName("user@example.com", "John Doe")),
 *     html = "<h1>Welcome</h1><p>Thanks for signing up!</p>"
 * )
 *
 * // Plain text email
 * val email = Email(
 *     subject = "Welcome!",
 *     to = listOf(EmailAddressWithName("user@example.com")),
 *     plainText = "Welcome! Thanks for signing up!"
 * )
 *
 * // With attachments
 * val email = Email(
 *     subject = "Invoice",
 *     to = listOf(EmailAddressWithName("customer@example.com")),
 *     html = "<p>Your invoice is attached.</p>",
 *     attachments = listOf(
 *         Email.Attachment(
 *             inline = false,
 *             filename = "invoice.pdf",
 *             typedData = TypedData(pdfData, MediaType.Application.Pdf)
 *         )
 *     )
 * )
 * ```
 *
 * @property subject Email subject line
 * @property from Sender address (if null, provider default is used)
 * @property to Primary recipients (required, at least one)
 * @property cc Carbon copy recipients
 * @property bcc Blind carbon copy recipients (hidden from other recipients)
 * @property html HTML body content
 * @property plainText Plain text body (auto-generated from HTML if not provided)
 * @property attachments File attachments
 * @property customHeaders Provider-specific headers (e.g., tracking, priority)
 */
public data class Email @Deprecated("Use KotlinX HTML to build HTML emails.") constructor(
    public val subject: String,
    public val from: EmailAddressWithName? = null,
    public val to: List<EmailAddressWithName>,
    public val cc: List<EmailAddressWithName> = listOf(),
    public val bcc: List<EmailAddressWithName> = listOf(),
    public val html: String,
    public val plainText: String = html.emailApproximatePlainText(),
    public val attachments: List<Attachment> = listOf(),
    public val customHeaders: Map<String, List<String>> = mapOf(),
) {
    @Suppress("DEPRECATION")
    public constructor(
        subject: String,
        from: EmailAddressWithName? = null,
        to: List<EmailAddressWithName>,
        cc: List<EmailAddressWithName> = listOf(),
        bcc: List<EmailAddressWithName> = listOf(),
        plainText: String,
        attachments: List<Attachment> = listOf(),
        customHeaders: Map<String, List<String>> = mapOf(),
    ) : this(
        subject = subject,
        from = from,
        to = to,
        cc = cc,
        bcc = bcc,
        html = plainText.emailPlainTextToHtml(),
        plainText = plainText,
        attachments = attachments,
        customHeaders = customHeaders,
    )
    @Suppress("DEPRECATION")
    public constructor(
        subject: String,
        from: EmailAddressWithName? = null,
        to: List<EmailAddressWithName>,
        cc: List<EmailAddressWithName> = listOf(),
        bcc: List<EmailAddressWithName> = listOf(),
        html: HTML.()->Unit,
        plainText: String = buildString {
            appendHTML(true).html(block = html)
        }.emailApproximatePlainText(),
        attachments: List<Attachment> = listOf(),
        customHeaders: Map<String, List<String>> = mapOf(),
    ) : this(
        subject = subject,
        from = from,
        to = to,
        cc = cc,
        bcc = bcc,
        html = buildString {
            appendLine("<!doctype html>")
            appendHTML(true).html(block = html)
        },
        plainText = plainText,
        attachments = attachments,
        customHeaders = customHeaders,
    )

    /**
     * Represents a file attachment in an email.
     *
     * @property inline If true, attachment is embedded in HTML (for images via cid:). If false, appears as downloadable attachment.
     * @property filename Name shown to recipients (e.g., "invoice.pdf")
     * @property typedData File content with media type
     */
    public data class Attachment(
        public val inline: Boolean,
        public val filename: String,
        public val typedData: TypedData
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Attachment

            if (inline != other.inline) return false
            if (filename != other.filename) return false
            if (typedData.mediaType != other.typedData.mediaType) return false
            if (typedData.data != (other.typedData.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = inline.hashCode()
            result = 31 * result + filename.hashCode()
            result = 31 * result + typedData.hashCode()
            return result
        }
    }
}

/**
 * Email address with optional display name.
 *
 * Used for from/to/cc/bcc fields in [Email].
 *
 * ## Examples
 *
 * ```kotlin
 * // Just email address
 * EmailAddressWithName("user@example.com")
 *
 * // With display name
 * EmailAddressWithName("user@example.com".toEmailAddress(), "John Doe")
 *
 * // Using helper
 * EmailAddressWithName("user@example.com", "John Doe")
 * ```
 *
 * @property value The email address
 * @property label Display name shown to recipients (e.g., "John Doe <john@example.com>")
 */
public data class EmailAddressWithName(
    public val value: EmailAddress,
    public val label: String? = null
) {
    public constructor(value: String) : this(value.toEmailAddress())
}

@JvmName("EmailAddressWithNameString")
public fun EmailAddressWithName(value: String, label: String): EmailAddressWithName =
    EmailAddressWithName(value.toEmailAddress(), label)

/**
 * Personalization data for bulk email template substitution.
 *
 * Used with [EmailService.sendBulk] to send customized emails to multiple recipients
 * from a single template.
 *
 * ## Template Syntax
 *
 * Use `{{variableName}}` in the template for substitution:
 *
 * ```kotlin
 * val template = Email(
 *     subject = "Order {{orderId}} Update",
 *     to = listOf(),  // Overridden per personalization
 *     html = "<p>Hi {{name}}, order {{orderId}} status: {{status}}</p>"
 * )
 *
 * val personalization = EmailPersonalization(
 *     to = listOf(EmailAddressWithName("customer@example.com")),
 *     substitutions = mapOf(
 *         "name" to "Alice",
 *         "orderId" to "12345",
 *         "status" to "shipped"
 *     )
 * )
 *
 * // Resulting email:
 * // Subject: "Order 12345 Update"
 * // Body: "<p>Hi Alice, order 12345 status: shipped</p>"
 * ```
 *
 * @property to Override template recipients (if null, uses template's to field)
 * @property cc Override template CC recipients
 * @property bcc Override template BCC recipients
 * @property subject Override template subject (if null, subject is still processed for substitutions)
 * @property substitutions Map of variable names to replacement values
 */
public data class EmailPersonalization(
    public val to: List<EmailAddressWithName>? = null,
    public val cc: List<EmailAddressWithName>? = null,
    public val bcc: List<EmailAddressWithName>? = null,
    public val subject: String? = null,
    public val substitutions: Map<String, String> = mapOf(),
) {
    /**
     * Applies personalization to an email template.
     */
    public operator fun invoke(template: Email): Email {
        var html = template.html
        var plainText = template.plainText
        var subjectText = template.subject
        substitutions.forEach { (key, value) ->
            html = html.replace("{{$key}}", value)
            plainText = plainText.replace("{{$key}}", value)
            subjectText = subjectText.replace("{{$key}}", value)
        }
        return template.copy(
            to = to ?: template.to,
            cc = cc ?: template.cc,
            bcc = bcc ?: template.bcc,
            subject = subject ?: subjectText,
            html = html,
            plainText = plainText
        )
    }
}

/**
 * Converts plain text to HTML.
 */
public fun String.emailPlainTextToHtml(): String {
    return "<pre>$this</pre>"
}

/**
 * Extracts plain text from HTML.
 */
public fun String.emailApproximatePlainText(): String {
    return this
        .replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<p.*?>"), "\n")
        .replace(Regex("<div.*?>"), "\n")
        .replace(Regex("<li.*?>"), "\n- ")
        .replace(Regex("<.*?>"), "")
        .replace(Regex("&nbsp;"), " ")
        .replace(Regex("&lt;"), "<")
        .replace(Regex("&gt;"), ">")
        .replace(Regex("&amp;"), "&")
        .replace(Regex("\\s*\\n\\s*"), "\n")
        .replace(Regex(" +"), " ")
}
