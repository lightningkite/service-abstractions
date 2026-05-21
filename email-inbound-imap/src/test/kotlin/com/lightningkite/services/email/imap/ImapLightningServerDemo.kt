package com.lightningkite.services.email.imap

import com.icegreen.greenmail.util.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.engine.netty.NettyEngine
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.services.data.*
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.ReceivedEmail
import com.lightningkite.services.kfile.workingDirectory
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Lightning Server demo for IMAP email inbound polling.
 *
 * Demonstrates IMAP polling alongside a Lightning Server runtime. IMAP is pull-only:
 * `onReceived.pull()` returns the emails directly, so the server processes them in-process
 * instead of looping back through an HTTP webhook.
 *
 * ## Mode 1: GreenMail Test Mode (Default)
 *
 * Runs with an embedded GreenMail mock server (no external dependencies):
 *
 * ```bash
 * ./gradlew :email-inbound-imap:runLightningServerDemo
 * ```
 *
 * ## Mode 2: Real IMAP Server
 *
 * Create `local/imap-settings.json` with:
 * ```json
 * { "imapEmail": "imaps://user:password@imap.gmail.com:993/INBOX" }
 * ```
 *
 * Or set environment variable:
 * ```bash
 * IMAP_URL="imaps://user:password@imap.gmail.com:993/INBOX" ./gradlew :email-inbound-imap:runLightningServerDemo
 * ```
 *
 * ## Interactive Commands
 *
 * - `send`   - Send a test email (GreenMail mode only)
 * - `poll`   - Pull new emails from the IMAP server
 * - `emails` - Show received emails
 * - `clear`  - Clear received emails
 * - `quit`   - Stop server
 */
object ImapLightningServerDemo {
    init {
        ImapEmailInboundService
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("=".repeat(60))
        println("Lightning Server - IMAP Email Inbound Demo")
        println("=".repeat(60))

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
            |  poll     - Pull new emails from the IMAP server
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
                        val pulled = imap().onReceived.pull()
                        receivedEmails.addAll(pulled)
                        println("Pulled ${pulled.size} email(s). Use 'emails' to view.")
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
