package com.lightningkite.services.email.imap

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import com.lightningkite.MediaType
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.engine.netty.NettyEngine
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.workingDirectory
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.ReceivedEmail
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Lightning Server demo for IMAP email inbound polling.
 *
 * This demonstrates:
 * 1. Setting up a Lightning Server to receive email webhooks
 * 2. Polling an IMAP server for new emails
 * 3. Processing received emails with a webhook handler
 *
 * ## Mode 1: GreenMail Test Mode (Default)
 *
 * Runs with embedded GreenMail mock server for testing without external dependencies:
 *
 * ```bash
 * ./gradlew :email-inbound-imap:runLightningServerDemo
 * ```
 *
 * Then use the interactive commands to send test emails and poll.
 *
 * ## Mode 2: Real IMAP Server
 *
 * Create `local/imap-settings.json` with:
 * ```json
 * {
 *   "imapEmail": "imaps://user:password@imap.gmail.com:993/INBOX"
 * }
 * ```
 *
 * Or set environment variable:
 * ```bash
 * IMAP_URL="imaps://user:password@imap.gmail.com:993/INBOX" ./gradlew :email-inbound-imap:runLightningServerDemo
 * ```
 *
 * ## Interactive Commands
 *
 * - `send` - Send a test email (GreenMail mode only)
 * - `poll` - Poll for new emails
 * - `emails` - Show received emails
 * - `clear` - Clear received emails
 * - `quit` - Stop server
 */
object ImapLightningServerDemo {
    init {
        ImapEmailInboundService
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("=".repeat(60))
        println("Lightning Server - IMAP Email Inbound Demo")
        println("=".repeat(60))

        // Check for external IMAP URL
        val externalImapUrl = System.getenv("IMAP_URL")

        val greenMail: GreenMail?

        if (externalImapUrl != null) {
            println("\n[MODE] Using external IMAP server from IMAP_URL environment variable")
            println("IMAP URL: ${externalImapUrl.replace(Regex(":[^:@]+@"), ":****@")}")
            greenMail = null
            ImapServer.imapUrl = externalImapUrl
        } else {
            println("\n[MODE] Using GreenMail embedded test server")
            greenMail = GreenMail(
                arrayOf(
                    ServerSetup(3143, null, ServerSetup.PROTOCOL_IMAP),
                    ServerSetup(3025, null, ServerSetup.PROTOCOL_SMTP)
                )
            )
            greenMail.start()
            greenMail.setUser("test@localhost", "test@localhost", "password")
            ImapServer.imapUrl = "imap://test%40localhost:password@localhost:3143/INBOX"
            println("GreenMail started - IMAP: 3143, SMTP: 3025")
            println("Test user: test@localhost / password")
        }

        ImapServer.greenMail = greenMail

        try {
            val built = ImapServer.build()
            val engine = NettyEngine(built, Clock.System)

            val serverJob = launch(Dispatchers.Default) {
                try {
                    val settingsFile = workingDirectory.then("imap-settings.json")
                    if (settingsFile.exists()) {
                        engine.settings.loadFromFile(settingsFile, engine.internalSerializersModule)
                    }
                    println("\nStarting Lightning Server on port 8080...")
                    engine.start()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Wait for server to start
            delay(1.seconds)

            with(engine) {
                ImapServer.cliInteractive()
            }

            serverJob.cancelAndJoin()
        } finally {
            greenMail?.stop()
        }
    }
}

object ImapServer : ServerBuilder() {
    var imapUrl: String = ""
    var greenMail: GreenMail? = null

    // Use a default URL that will be overwritten by main() before server starts
    val imap = setting("imapEmail", EmailInboundService.Settings("console"))

    private val receivedEmails = mutableListOf<ReceivedEmail>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    context(_: ServerRuntime)
    suspend fun cliInteractive() {
        println("\n" + "=".repeat(60))
        println("INTERACTIVE COMMANDS")
        println("=".repeat(60))
        println(
            """
            |
            |Available commands:
            |  send     - Send a test email (GreenMail mode only)
            |  poll     - Poll IMAP for new emails
            |  emails   - Show received emails
            |  clear    - Clear received emails
            |  quit     - Stop server and exit
            |
        """.trimMargin()
        )

        while (currentCoroutineContext().isActive) {
            print("\n> ")
            val input = readlnOrNull()?.trim() ?: continue

            when {
                input == "send" || input.startsWith("send ") -> {
                    if (greenMail == null) {
                        println("Send command only available in GreenMail mode")
                        continue
                    }

                    val subject = if (input == "send") {
                        print("Subject: ")
                        readlnOrNull() ?: "Test Email"
                    } else {
                        input.removePrefix("send ").trim()
                    }

                    print("Body: ")
                    val body = readlnOrNull() ?: "This is a test email."

                    sendTestEmail(subject, body)
                }

                input == "poll" -> {
                    println("Polling IMAP server for new emails...")
                    try {
                        imap().onReceived.onSchedule()
                        println("Poll complete. Check 'emails' for received messages.")
                    } catch (e: Exception) {
                        println("Error during poll: ${e.message}")
                        e.printStackTrace()
                    }
                }

                input == "emails" -> {
                    if (receivedEmails.isEmpty()) {
                        println("No emails received yet.")
                    } else {
                        println("Received emails (${receivedEmails.size}):")
                        receivedEmails.forEachIndexed { index, email ->
                            println()
                            println("--- Email ${index + 1} ---")
                            println("Message-ID: ${email.messageId}")
                            println("From: ${email.from.label ?: ""} <${email.from.value.raw}>")
                            println("To: ${email.to.joinToString { it.value.raw }}")
                            println("Subject: ${email.subject}")
                            println("Date: ${email.receivedAt}")
                            println("Plain text: ${email.plainText?.take(200) ?: "(none)"}")
                            if (email.html != null) {
                                println("HTML: (${email.html!!.length} chars)")
                            }
                            if (email.attachments.isNotEmpty()) {
                                println("Attachments: ${email.attachments.map { it.filename }}")
                            }
                            if (email.inReplyTo != null) {
                                println("In-Reply-To: ${email.inReplyTo}")
                            }
                        }
                    }
                }

                input == "clear" -> {
                    receivedEmails.clear()
                    println("Emails cleared.")
                }

                input == "quit" || input == "exit" -> {
                    println("Shutting down...")
                    break
                }

                input.isNotEmpty() -> {
                    println("Unknown command: $input")
                    println("Try: send, poll, emails, clear, quit")
                }
            }
        }
    }

    private fun sendTestEmail(subject: String, body: String) {
        val gm = greenMail ?: return

        val message = GreenMailUtil.createTextEmail(
            "test@localhost",
            "sender@example.com",
            subject,
            body,
            gm.smtp.serverSetup
        )
        GreenMailUtil.sendMimeMessage(message)
        println("Email sent: '$subject'")
    }

    // ==================== Webhook Endpoints ====================

    val emailWebhookConfig = path.path("webhooks").path("email").path("startup") bind StartupTask {
        val webhookUrl = emailWebhook.location.path.resolved().fullUrl()
        println("Configuring webhook URL: $webhookUrl")
        imap().onReceived.configureWebhook(webhookUrl)
    }

    val emailWebhook = path.path("webhooks").path("email").post bind HttpHandler { request ->
        println("\n" + "=".repeat(40))
        println("Incoming Email Webhook!")
        println("=".repeat(40))

        try {
            val bodyText = request.body?.text() ?: throw BadRequestException("Missing body")
            val email = json.decodeFromString<ReceivedEmail>(bodyText)

            receivedEmails.add(email)

            println("From: ${email.from.value.raw}")
            println("Subject: ${email.subject}")
            println("Body preview: ${email.plainText?.take(100) ?: "(no text)"}")

            HttpResponse(
                status = HttpStatus.OK,
                body = TypedData.text("OK", MediaType.Text.Plain)
            )
        } catch (e: Exception) {
            println("Error: ${e.message}")
            HttpResponse(
                status = HttpStatus.BadRequest,
                body = TypedData.text("Error: ${e.message}", MediaType.Text.Plain)
            )
        }
    }

    val health = path.path("health").get bind HttpHandler {
        HttpResponse(body = TypedData.text("OK - IMAP Email Demo", MediaType.Text.Plain))
    }

    val emailsEndpoint = path.path("emails").get bind HttpHandler {
        HttpResponse(
            body = TypedData.text(
                json.encodeToString(receivedEmails),
                MediaType.Application.Json
            )
        )
    }
}
