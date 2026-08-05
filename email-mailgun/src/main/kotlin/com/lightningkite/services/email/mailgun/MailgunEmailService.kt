package com.lightningkite.services.email.mailgun

import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.SettingContext
import com.lightningkite.services.email.*
import com.lightningkite.services.http.client
import com.lightningkite.services.telemetry.telemetryTrace
import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * @param baseClient Base HTTP client to derive the Mailgun-authenticated client from. Defaults to
 * the shared production client; tests can pass a `MockEngine`-backed client here instead.
 */
public class MailgunEmailService(
    override val name: String,
    override val context: SettingContext,
    private val key: String,
    private val domain: String,
    baseClient: HttpClient = client,
) : EmailService {

    private val client = baseClient.config {
        install(Auth) {
            basic {
                credentials {
                    BasicAuthCredentials(username = "api", password = key)
                }
            }
        }
    }

    public companion object {

        init {
            EmailService.Settings.register("mailgun") { name, url, context ->
                Regex("""mailgun://(?<key>[^@]+)@(?<domain>.+)""").matchEntire(url)?.let { match ->
                    MailgunEmailService(
                        name,
                        context,
                        match.groups["key"]!!.value,
                        match.groups["domain"]!!.value
                    )
                }
                    ?: throw IllegalStateException("Invalid Mailgun URL. The URL should match the pattern: mailgun://[key]@[domain]")

            }
        }
    }

    private suspend fun sendImpl(email: Email) {
        // Read attachment bytes here (suspend context); FormBuilder.append's bodyBuilder is not suspend.
        val attachmentBytes = email.attachments.map { it to it.typedData.data.bytes() }

        val result = client.submitFormWithBinaryData(
            url = "https://api.mailgun.net/v3/$domain/messages",
            formData = formData {
                // email.from.value (the address the caller actually configured) must be honored when
                // present — falling back to noreply@$domain unconditionally silently broke reply-to
                // flows and DKIM/sender-alignment for any caller-specified sender.
                val fromAddress = email.from?.value?.raw ?: "noreply@$domain"
                append("from", email.from?.label?.let { "$it <$fromAddress>" } ?: fromAddress)
                email.to.forEach {
                    append("to", it.value.raw)
                }
                append("subject", email.subject)
                append("text", email.plainText)
                append("html", email.html)
                append("o:tracking", "false")
                email.customHeaders.entries.forEach {
                    append("h:${it.key}", it.value.joinToString())
                }
                // Use ktor's filename/content-type-aware append() overload — the bare FormPart(...)
                // constructor used previously defaulted to Headers.Empty, so attachments arrived at
                // Mailgun with neither a filename nor a content type and were effectively unusable.
                attachmentBytes.forEach { (attachment, bytes) ->
                    append(
                        key = if (attachment.inline) "inline" else "attachment",
                        filename = attachment.filename,
                        contentType = ContentType.parse(attachment.typedData.mediaType.toString()),
                        size = bytes.size.toLong(),
                    ) {
                        write(bytes)
                    }
                }
            },
        )
        email.attachments.forEach { it.typedData.data.close() }
        if (!result.status.isSuccess())
            throw Exception("Got status ${result.status}: ${result.bodyAsText()}")
    }

    override suspend fun send(email: Email) {
        if (email.to.isEmpty() && email.cc.isEmpty() && email.bcc.isEmpty()) return

        telemetryTrace("send", attributes = TelemetryAttributes {
            put(TelemetryKey.OfString("email.operation"), "send")
            put(TelemetryKey.OfString("email.system"), "mailgun")
            put(TelemetryKeys.Messaging.system, "mailgun")
            put(TelemetryKey.OfString("email.from"), email.from?.value?.toString() ?: domain)
            put(TelemetryKey.OfString("email.to"), email.to.joinToString(", ") { it.value.toString() })
            put(TelemetryKey.OfString("email.subject"), email.subject)
            if (email.cc.isNotEmpty()) {
                put(TelemetryKey.OfString("email.cc"), email.cc.joinToString(", ") { it.value.toString() })
            }
            if (email.attachments.isNotEmpty()) {
                put(TelemetryKey.OfLong("email.attachments.count"), email.attachments.size.toLong())
            }
        }) { _ ->
            sendImpl(email)
        }
    }

    override suspend fun sendBulk(template: Email, personalizations: List<EmailPersonalization>) {
        if (personalizations.isEmpty()) return

        telemetryTrace("sendBulk", attributes = TelemetryAttributes {
            put(TelemetryKey.OfString("email.operation"), "sendBulk")
            put(TelemetryKey.OfString("email.system"), "mailgun")
            put(TelemetryKeys.Messaging.system, "mailgun")
            put(TelemetryKey.OfString("email.from"), template.from?.value?.toString() ?: domain)
            put(TelemetryKey.OfString("email.subject"), template.subject)
            put(TelemetryKey.OfLong("email.personalizations.count"), personalizations.size.toLong())
        }) { _ ->
            personalizations
                .asSequence()
                .map {
                    it(template).copy(
                        from = template.from,
                    )
                }
                .forEach { email ->
                    sendImpl(email)
                }
        }
    }


    override suspend fun sendBulk(emails: Collection<Email>) {
        if (emails.isEmpty()) return

        // TODO: use Mailgun batch send API instead of individual POSTs — requires API restructure
        telemetryTrace("sendBulk", attributes = TelemetryAttributes {
            put(TelemetryKey.OfString("email.operation"), "sendBulk")
            put(TelemetryKey.OfString("email.system"), "mailgun")
            put(TelemetryKeys.Messaging.system, "mailgun")
            put(TelemetryKey.OfString("email.from"), domain)
            put(TelemetryKey.OfLong("email.count"), emails.size.toLong())
        }) { _ ->
            emails.forEach { email ->
                sendImpl(email)
            }
        }
    }
}
