package com.lightningkite.services.email.javasmtp

import com.lightningkite.MediaType
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailPersonalization
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

class JavaSmtpEmailServiceTest {

    @Serializable
    data class SmtpConfig(
        val hostName: String,
        val port: Int,
        val username: String?,
        val password: String?,
        val fromEmail: String,
    )

    val client = run {
        val credentials = File("local/test-smtp.json")
        if (!credentials.exists()) {
            println("No credentials to test with at ${credentials.absolutePath}")
            return@run null
        }
        val creds = credentials.readText().let { Json.decodeFromString<SmtpConfig>(credentials.readText()) }
        JavaSmtpEmailService(
            TestSettingContext(),
            hostName = creds.hostName,
            port = creds.port,
            username = creds.username,
            password = creds.password,
            from = EmailAddressWithName(creds.fromEmail)
        )
    }

    @Test
    fun testSmtp() {
        val client = client ?: return
        runBlocking {
            client.send(
                Email(
                    subject = "Subject 2",
                    to = listOf(EmailAddressWithName("joseph@lightningkite.com", "Joseph Ivie")),
                    html = "<p>Hello world!</p>",
                )
            )
        }
    }

    @Test
    fun testSmtpAttachment() {
        val client = client ?: return
        runBlocking {
            client.send(
                Email(
                    subject = "Test email with attachment",
                    to = listOf(EmailAddressWithName("joseph@lightningkite.com", "Joseph Ivie")),
                    html = "<p>Hello world!</p>",
                    attachments = listOf(Email.Attachment(
                        inline = false,
                        filename = "test.txt",
                        typedData = TypedData.text("Test", MediaType.Text.Plain)
                    ))
                )
            )
        }
    }

    @Test
    fun testSendBulk(): Unit = runBlocking {
        val client = client ?: return@runBlocking
        val email1 = EmailAddressWithName(
            "joseph@lightningkite.com",
            "Joseph Ivie One"
        )
        val email2 = EmailAddressWithName(
            "joseph+two@lightningkite.com",
            "Joseph Ivie Two"
        )
        val email3 = EmailAddressWithName(
            "joseph+three@lightningkite.com",
            "Joseph Ivie Three"
        )

        client.sendBulk(
            Email(
                subject = "Bulk Email Test",
                to = emptyList(),
                html = "<p>Hello {{UserName}}!</p>",
            ),
            personalizations = listOf(
                EmailPersonalization(
                    to = listOf(email1),
                    cc = listOf(email2),
                    bcc = listOf(email3),
                    substitutions = mapOf("{{UserName}}" to email1.label!!)
                ),
                EmailPersonalization(
                    to = listOf(email2),
                    cc = listOf(email3),
                    bcc = listOf(email1),
                    substitutions = mapOf("{{UserName}}" to email2.label!!)
                ),
                EmailPersonalization(
                    to = listOf(email3),
                    cc = listOf(email1),
                    bcc = listOf(email2),
                    substitutions = mapOf("{{UserName}}" to email3.label!!)
                ),
                EmailPersonalization(
                    to = listOf(email1, email2, email3),
                    substitutions = mapOf(
                        "{{UserName}}" to listOf(
                            email1.label!!,
                            email2.label!!,
                            email3.label!!
                        ).joinToString { it })
                ),
            ),
        )

    }
}