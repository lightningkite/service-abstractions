package com.lightningkite.services.email.javasmtp

import com.lightningkite.MediaType
import com.lightningkite.services.SettingContext
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailPersonalization
import com.lightningkite.services.email.EmailService
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import jakarta.activation.DataHandler
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.util.Properties
import kotlin.use

/**
 * SMTP email implementation using Jakarta Mail (JavaMail) for sending emails.
 *
 * Provides production-ready email sending with support for:
 * - **SMTP authentication**: Username/password authentication
 * - **TLS/SSL encryption**: Automatic configuration based on port (465 SSL, 587 STARTTLS)
 * - **Bulk sending**: Efficient batch operations with persistent connections
 * - **HTML + Plain text**: Multipart alternative format for client compatibility
 * - **Attachments**: Full MIME attachment support (inline and regular)
 * - **Custom headers**: Support for reply-to, CC, BCC, and custom headers
 *
 * ## Supported URL Schemes
 *
 * - `smtp://host:port` - Unauthenticated SMTP (rare)
 * - `smtp://username:password@host:port?fromEmail=...&fromLabel=...` - Authenticated SMTP
 *
 * Format: `smtp://[username]:[password]@[host]:[port]?[params]`
 *
 * Query parameters:
 * - `fromEmail` (required): Default sender email address
 * - `fromLabel` (optional): Default sender display name (defaults to context.projectName)
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Gmail SMTP
 * EmailService.Settings("smtp://myuser@gmail.com:app-password@smtp.gmail.com:587?fromEmail=noreply@example.com&fromLabel=My App")
 *
 * // SendGrid SMTP
 * EmailService.Settings("smtp://apikey:SG.xxx@smtp.sendgrid.net:587?fromEmail=support@example.com")
 *
 * // Office 365
 * EmailService.Settings("smtp://user@company.com:password@smtp.office365.com:587?fromEmail=noreply@company.com")
 *
 * // Using helper function
 * EmailService.Settings.Companion.smtp(
 *     username = "apikey",
 *     password = "SG.xxx",
 *     host = "smtp.sendgrid.net",
 *     port = "587",
 *     fromEmail = "support@example.com",
 *     fromLabel = "Support Team"
 * )
 * ```
 *
 * ## Implementation Notes
 *
 * - **Port-based TLS**: Automatically enables SSL on port 465, STARTTLS on port 587
 * - **Multipart emails**: Always sends both HTML and plain text alternatives
 * - **Attachment handling**: Closes attachment streams after sending (important for memory)
 * - **Bulk optimization**: Reuses SMTP connection for bulk sends (faster than individual)
 * - **Session caching**: Jakarta Mail session created once and reused
 *
 * ## Important Gotchas
 *
 * - **Gmail requires app passwords**: Regular passwords don't work with 2FA enabled
 * - **Port 25 often blocked**: Use 587 (STARTTLS) or 465 (SSL) instead
 * - **Attachment memory**: Attachments are loaded into memory; beware of large files
 * - **sendBulk reuses connection**: More efficient but connection failures affect entire batch
 * - **HTML rendering**: Different email clients render HTML differently (test thoroughly)
 * - **From address**: Some providers (Gmail, SendGrid) restrict from addresses to verified domains
 * - **Rate limiting**: SMTP servers may rate limit; consider queuing for high volumes
 *
 * ## Common SMTP Providers
 *
 * | Provider | Host | Port | Notes |
 * |----------|------|------|-------|
 * | Gmail | smtp.gmail.com | 587 | Requires app password |
 * | SendGrid | smtp.sendgrid.net | 587 | Username is always "apikey" |
 * | Mailgun | smtp.mailgun.org | 587 | Region-specific hosts |
 * | Office 365 | smtp.office365.com | 587 | Requires modern auth |
 * | AWS SES | email-smtp.region.amazonaws.com | 587 | Requires SMTP credentials |
 *
 * @property name Service name for logging/metrics
 * @property context Service context
 * @property hostName SMTP server hostname
 * @property port SMTP server port (465 for SSL, 587 for STARTTLS, 25 for plain)
 * @property username SMTP authentication username (null for no auth)
 * @property password SMTP authentication password (null for no auth)
 * @property from Default sender address and name
 */
public class JavaSmtpEmailService(
    override val name: String,
    override val context: SettingContext,
    public val hostName: String,
    public val port: Int,
    public val username: String?,
    password: String?,
    public val from: EmailAddressWithName,
) : EmailService {

    private val tracer: Tracer? = context.openTelemetry?.getTracer("email-javasmtp")

    public companion object {
        private fun parseParameterString(params: String): Map<String, List<String>> = params
            .takeIf { it.isNotBlank() }
            ?.split("&")
            ?.filter { it.isNotBlank() }
            ?.map {
                it.substringBefore('=') to it.substringAfter('=', "")
            }
            ?.groupBy { it.first }
            ?.mapValues { it.value.map { it.second } }
            ?: emptyMap()

        public fun EmailService.Settings.Companion.smtp(
            username: String,
            password: String,
            host: String,
            port: String,
            fromEmail: String,
            fromLabel: String? = null,
        ): EmailService.Settings = EmailService.Settings("smtp://$username:$password@$host:$port?fromEmail=$fromEmail&fromLabel=$fromLabel")

        init {
            EmailService.Settings.register("smtp") { name, url, context ->
                Regex("""smtp://(?:(?<username>[^:]+):(?<password>.+)@)?(?<host>[^:@]+):(?<port>[0-9]+)(?:\?(?<params>.*))?""")
                    .matchEntire(url)
                    ?.let { match ->
                        val port = match.groups["port"]!!.value.toInt()
                        val params = parseParameterString(match.groups["params"]?.value ?: "")
                        JavaSmtpEmailService(
                            name = name,
                            context = context,
                            hostName = match.groups["host"]!!.value,
                            port = port,
                            username = match.groups["username"]?.value,
                            password = match.groups["password"]?.value,
                            from = EmailAddressWithName(
                                value = params["fromEmail"]!!.first(),
                                label = params["fromLabel"]?.first() ?: context.projectName,
                            )
                        )
                    }
                    ?: throw IllegalStateException("Invalid SMTP URL. The URL should match the pattern: smtp://[username]:[password]@[host]:[port]?[params]\nAvailable params are: fromEmail")

            }
        }
    }

    public val session: Session = Session.getInstance(
        Properties().apply {
            username?.let { username ->
                put("mail.smtp.user", username)
            }
            put("mail.smtp.host", hostName)
            put("mail.smtp.port", port)
            put("mail.smtp.auth", username != null && password != null)
            put("mail.smtp.ssl.enable", port == 465)
            put("mail.smtp.starttls.enable", port == 587)
            put("mail.smtp.starttls.required", port == 587)
        },
        if (username != null && password != null)
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication = PasswordAuthentication(
                    username,
                    password
                )
            }
        else
            null
    )

    override suspend fun send(email: Email) {
        if (email.to.isEmpty() && email.cc.isEmpty() && email.bcc.isEmpty()) return

        val span = tracer?.spanBuilder("email.send")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("email.operation", "send")
            ?.setAttribute("email.system", "smtp")
            ?.setAttribute("email.smtp.host", hostName)
            ?.setAttribute("email.smtp.port", port.toLong())
            ?.setAttribute("email.from", email.from?.value.toString() ?: from.value.toString())
            ?.setAttribute("email.to", email.to.joinToString(", ") { it.value.toString() })
            ?.setAttribute("email.subject", email.subject)
            ?.also { builder ->
                if (email.cc.isNotEmpty()) {
                    builder.setAttribute("email.cc", email.cc.joinToString(", ") { it.value.toString() })
                }
                if (email.attachments.isNotEmpty()) {
                    builder.setAttribute("email.attachments.count", email.attachments.size.toLong())
                }
            }
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                Transport.send(
                    email.copy(
                        from = email.from ?: from,
                    ).toJavaX(session)
                )
                email.attachments.forEach { it.typedData.data.close() }
                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to send email: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun sendBulk(template: Email, personalizations: List<EmailPersonalization>) {
        if (personalizations.isEmpty()) return

        val span = tracer?.spanBuilder("email.sendBulk")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("email.operation", "sendBulk")
            ?.setAttribute("email.system", "smtp")
            ?.setAttribute("email.smtp.host", hostName)
            ?.setAttribute("email.smtp.port", port.toLong())
            ?.setAttribute("email.from", template.from?.value.toString() ?: from.value.toString())
            ?.setAttribute("email.subject", template.subject)
            ?.setAttribute("email.personalizations.count", personalizations.size.toLong())
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                session.transport
                    .also { it.connect() }
                    .use { transport ->
                        personalizations
                            .asSequence()
                            .map {
                                it(template).copy(
                                    from = template.from ?: from,
                                )
                            }
                            .forEach { email ->
                                transport.sendMessage(
                                    email.toJavaX(session).also { it.saveChanges() },
                                    email.to
                                        .plus(email.cc)
                                        .plus(email.bcc)
                                        .map { InternetAddress(it.value.toString(), it.label) }
                                        .toTypedArray()
                                        .also { if (it.isEmpty()) return@forEach }
                                )
                            }
                    }
                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to send bulk emails: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }


    override suspend fun sendBulk(emails: Collection<Email>) {
        if (emails.isEmpty()) return

        val span = tracer?.spanBuilder("email.sendBulk")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("email.operation", "sendBulk")
            ?.setAttribute("email.system", "smtp")
            ?.setAttribute("email.smtp.host", hostName)
            ?.setAttribute("email.smtp.port", port.toLong())
            ?.setAttribute("email.from", from.value.toString())
            ?.setAttribute("email.count", emails.size.toLong())
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                session.transport
                    .also { it.connect() }
                    .use { transport ->
                        emails.forEach { email ->
                            transport.sendMessage(
                                email.copy(
                                    from = from
                                )
                                    .toJavaX(session)
                                    .also { it.saveChanges() },
                                email.to
                                    .plus(email.cc)
                                    .plus(email.bcc)
                                    .map { InternetAddress(it.value.toString(), it.label) }
                                    .toTypedArray()
                                    .also { if (it.isEmpty()) return@forEach }
                            )
                        }
                    }
                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to send bulk emails: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    private suspend fun Email.toJavaX(session: Session = Session.getDefaultInstance(Properties(), null)): Message =
        MimeMessage(session).apply {
            val email = this@toJavaX
            subject = email.subject
            email.from?.let { setFrom(InternetAddress(it.value.toString(), it.label)) }
            email.to.map { InternetAddress(it.value.toString(), it.label) }
                .also { setRecipients(Message.RecipientType.TO, it.toTypedArray()) }
            email.cc.map { InternetAddress(it.value.toString(), it.label) }
                .also { setRecipients(Message.RecipientType.CC, it.toTypedArray()) }
            email.bcc.map { InternetAddress(it.value.toString(), it.label) }
                .also { setRecipients(Message.RecipientType.BCC, it.toTypedArray()) }

            email.customHeaders.entries.forEach {
                addHeader(it.key, it.value.joinToString(","))
            }

            setContent(MimeMultipart("mixed").apply {
                addBodyPart(MimeBodyPart().apply {
                    setContent(MimeMultipart("alternative").apply {
                        addBodyPart(MimeBodyPart().apply {
                            dataHandler = DataHandler(ByteArrayDataSource(plainText, MediaType.Text.Plain.toString()))
                        })
                        addBodyPart(MimeBodyPart().apply {
                            dataHandler = DataHandler(ByteArrayDataSource(html, MediaType.Text.Html.toString()))
                        })
                    })
                })
                for (attachment in email.attachments) {
                    addBodyPart(MimeBodyPart().apply {
                        addHeader(
                            "Content-Disposition",
                            "form-data;name=${if(attachment.inline) "inline" else "attachment"};filename=${attachment.filename}"
                        )
                        dataHandler = DataHandler(
                            ByteArrayDataSource(
                                attachment.typedData.data.bytes(),
                                attachment.typedData.mediaType.toString()
                            )
                        )
                    })
                }
            })
        }

}
