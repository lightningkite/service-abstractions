package com.lightningkite.services.email.javasmtp

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.email.*
import kotlinx.coroutines.runBlocking
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

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
            name = "email",
            context = TestSettingContext(),
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
                    html = {
                        body {
                            p {
                                +"Hello world!"
                            }
                        }
                    },
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
                    html = {
                        body {
                            p {
                                +"Hello world!"
                            }
                        }
                    },
                    attachments = listOf(
                        Email.Attachment(
                            inline = false,
                            filename = "test.txt",
                            typedData = TypedData.text("Test", MediaType.Text.Plain)
                        )
                    )
                )
            )
        }
    }

    // by Claude - verifies that inline CID attachments render in email clients (especially Gmail)
    @Test
    fun testSmtpInlineImage() {
        val client = client ?: return
        // Generate a simple test PNG
        val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                color = Color(0x00, 0x7A, 0xCC)
                fillRect(0, 0, 200, 200)
                color = Color.WHITE
                font = Font("SansSerif", Font.BOLD, 24)
                drawString("CID Test", 30, 110)
                dispose()
            }
        }
        val pngBytes = ByteArrayOutputStream().also { ImageIO.write(image, "PNG", it) }.toByteArray()
        val filename = "test-image.png"

        runBlocking {
            client.send(
                Email(
                    subject = "Inline CID image test",
                    to = listOf(EmailAddressWithName("joseph@lightningkite.com", "Joseph Ivie")),
                    html = {
                        body {
                            h2 { +"Inline Image Test" }
                            p { +"The image below should appear inline, not as a separate attachment:" }
                            img {
                                src = "cid:$filename"
                                alt = "Test image"
                                style = "width:200px;height:200px;"
                            }
                            p { +"If you see a blue square with white text above, CID inline images are working." }
                        }
                    },
                    attachments = listOf(
                        Email.Attachment(
                            inline = true,
                            filename = filename,
                            typedData = TypedData.bytes(pngBytes, MediaType.Image.PNG)
                        )
                    )
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
                html = {
                    body {
                        p {
                            +"Hello {{UserName}}!"
                        }
                    }
                },
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