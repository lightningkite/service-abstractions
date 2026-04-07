package com.lightningkite.services.email.mailgun

import com.lightningkite.services.SettingContext
import com.lightningkite.services.email.*
import com.lightningkite.services.recordExceptionWithFingerprint
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import io.opentelemetry.api.trace.*
import kotlinx.io.asInputStream
import kotlinx.io.buffered
import kotlin.text.get

public class MailgunEmailService(
    override val name: String,
    override val context: SettingContext,
    private val key: String,
    private val domain: String,
) : EmailService {

    private val tracer: Tracer? = context.openTelemetry?.getTracer("email-mailgun")

    private val client = com.lightningkite.services.http.client.config {
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
        val parts = email.attachments.map {
            FormPart(
                if (it.inline) "inline" else "attachment", ChannelProvider(
                    size = it.typedData.data.size,
                    block = { it.typedData.data.source().buffered().asInputStream().toByteReadChannel() }
                ))
        }

        val result = client.submitFormWithBinaryData(
            url = "https://api.mailgun.net/v3/$domain/messages",
            formData = formData {
                append("from", email.from?.label?.let { "$it <noreply@$domain>" } ?: "<noreply@$domain>")
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
                parts.forEach { append(it) }
            },
        )
        email.attachments.forEach { it.typedData.data.close() }
        if (!result.status.isSuccess())
            throw Exception("Got status ${result.status}: ${result.bodyAsText()}")
    }

    override suspend fun send(email: Email) {
        if (email.to.isEmpty() && email.cc.isEmpty() && email.bcc.isEmpty()) return

        val span = tracer?.spanBuilder("email.send")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("email.operation", "send")
            ?.setAttribute("email.system", "mailgun")
            ?.setAttribute("email.from", email.from?.value?.toString() ?: domain)
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
                sendImpl(email)
                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to send email: ${e.message}")
            span?.recordExceptionWithFingerprint(e)
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
            ?.setAttribute("email.system", "mailgun")
            ?.setAttribute("email.from", template.from?.value?.toString() ?: domain)
            ?.setAttribute("email.subject", template.subject)
            ?.setAttribute("email.personalizations.count", personalizations.size.toLong())
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
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
                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to send bulk emails: ${e.message}")
            span?.recordExceptionWithFingerprint(e)
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
            ?.setAttribute("email.system", "Mailgun")
            ?.setAttribute("email.from", domain)
            ?.setAttribute("email.count", emails.size.toLong())
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                emails.forEach { email ->
                    sendImpl(email)
                }
                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to send bulk emails: ${e.message}")
            span?.recordExceptionWithFingerprint(e)
            throw e
        } finally {
            span?.end()
        }
    }
}
