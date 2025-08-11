package com.lightningkite.services.email

import com.lightningkite.EmailAddress
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.data.TypedData
import com.lightningkite.toEmailAddress
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.jvm.JvmName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * A service for sending emails.
 */
public interface EmailService : Service {
    /**
     * Settings for configuring an email service.
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
 * Represents an email to be sent.
 */
public data class Email(
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

    /**
     * Represents an attachment to an email.
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
 * Represents a labeled email address.
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
 * Represents personalization data for an email.
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
}
