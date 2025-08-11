package com.lightningkite.services.email.javasmtp

import com.lightningkite.MediaType
import com.lightningkite.services.SettingContext
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailPersonalization
import com.lightningkite.services.email.EmailService
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
 * An email client that will send real emails through SMTP.
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
                            ?: throw IllegalStateException("SMTP Email requires a fromEmail to be set.")
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
        Transport.send(
            email.copy(
                from = email.from ?: from,
            ).toJavaX(session)
        )
        email.attachments.forEach { it.typedData.data.close() }
    }

    override suspend fun sendBulk(template: Email, personalizations: List<EmailPersonalization>) {
        if (personalizations.isEmpty()) return
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
    }


    override suspend fun sendBulk(emails: Collection<Email>) {
        if (emails.isEmpty()) return
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
