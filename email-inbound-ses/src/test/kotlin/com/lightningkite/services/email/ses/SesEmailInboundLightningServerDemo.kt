package com.lightningkite.services.email.ses

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.engine.netty.NettyEngine
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.services.data.HttpAdapter
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.workingDirectory
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.ReceivedEmail
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

/**
 * Lightning Server demo for AWS SES inbound email webhooks.
 *
 * This demonstrates:
 * 1. Setting up a Lightning Server to handle SES email webhooks via SNS
 * 2. Handling inbound emails with MIME parsing
 * 3. **Automatic** SNS subscription confirmation
 *
 * ## Prerequisites
 *
 * 1. AWS SES inbound email infrastructure must be configured:
 *    - Domain verified in SES
 *    - Receipt rule set with SNS action
 *    - MX records pointing to SES
 *
 * 2. Expose your local server using ngrok:
 *    ```bash
 *    ngrok http 8080
 *    ```
 *
 * 3. Configure your SNS topic subscription to:
 *    `https://YOUR-NGROK-URL.ngrok.io/webhooks/email`
 *    Protocol: HTTPS
 *
 * The subscription will be **automatically confirmed** when SNS sends the
 * SubscriptionConfirmation message to your webhook.
 *
 * ## Running
 *
 * ```bash
 * ./gradlew :email-inbound-ses:runLightningServerDemo
 * ```
 *
 * ## Testing
 *
 * Send an email to an address on your verified SES domain. The email will be:
 * 1. Received by SES via MX record
 * 2. Processed by receipt rule
 * 3. Published to SNS topic
 * 4. POSTed to your webhook endpoint
 * 5. Parsed and displayed in the console
 */
object SesEmailInboundLightningServerDemo {
    init {
        SesEmailInboundService
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("=".repeat(60))
        println("Lightning Server - AWS SES Inbound Email Demo")
        println("=".repeat(60))

        val built = EmailServer.build()

        val engine = NettyEngine(built, Clock.System)

        // Start server in background
        val serverJob = launch(Dispatchers.Default) {
            try {
                engine.settings.loadFromFile(workingDirectory.then("settings.json"), engine.internalSerializersModule)
                println("Engine starting...")
                engine.start()
                println("Engine started!")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        with(engine) {
            EmailServer.cliInteractive()
        }
        serverJob.cancelAndJoin()
    }
}


object EmailServer : ServerBuilder() {
    val emailInbound = setting("emailInbound", EmailInboundService.Settings("ses://"))

    // Store received emails for inspection
    private val receivedEmails = mutableListOf<ReceivedEmail>()

    context(runtime: ServerRuntime)
    suspend fun cliInteractive() {
        println("\n" + "=".repeat(60))
        println("INTERACTIVE OPTIONS")
        println("=".repeat(60))
        println(
            """
            |
            |Enter a command:
            |  emails      - Show received emails
            |  clear       - Clear received emails
            |  quit        - Stop server and exit
            |
        """.trimMargin()
        )

        // Interactive command loop
        while (currentCoroutineContext().isActive) {
            print("\n> ")
            val input = readlnOrNull()?.trim() ?: continue

            when {
                input == "emails" -> {
                    if (receivedEmails.isEmpty()) {
                        println("No emails received yet.")
                    } else {
                        println("Received emails (${receivedEmails.size}):")
                        receivedEmails.forEachIndexed { index, email ->
                            println("  ${index + 1}. From: ${email.from.value.raw} ${email.from.label?.let { "($it)" } ?: ""}")
                            println("     To: ${email.to.joinToString { it.value.raw }}")
                            if (email.cc.isNotEmpty()) {
                                println("     Cc: ${email.cc.joinToString { it.value.raw }}")
                            }
                            println("     Subject: ${email.subject}")
                            println("     Time: ${email.receivedAt}")
                            println("     Message-ID: ${email.messageId}")
                            if (email.spamScore != null) {
                                println("     Spam Score: ${email.spamScore}")
                            }
                            println("     Body Preview: ${email.plainText?.take(100)?.replace("\n", " ") ?: email.html?.take(100)?.replace("\n", " ") ?: "(no body)"}")
                            if (email.attachments.isNotEmpty()) {
                                println("     Attachments (${email.attachments.size}):")
                                email.attachments.forEachIndexed { i, att ->
                                    println("       ${i + 1}. ${att.filename} (${att.contentType}, ${att.size} bytes)")
                                }
                            }
                            if (email.inReplyTo != null) {
                                println("     In-Reply-To: ${email.inReplyTo}")
                            }
                            println()
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
                }
            }
        }
    }

    // ==================== Webhook Endpoints ====================

    /**
     * Configure webhook on startup - logs the webhook URL.
     */
    val emailWebhookConfigure = path.path("webhooks").path("email").path("startup") bind StartupTask {
        val webhookUrl = emailWebhook.location.path.resolved().fullUrl()
        println("\n" + "=".repeat(60))
        println("EMAIL WEBHOOK READY")
        println("=".repeat(60))
        println("Configure your SNS topic subscription to POST to:")
        println("  $webhookUrl")
        println()
        println("Subscriptions will be AUTOMATICALLY CONFIRMED when SNS sends")
        println("the SubscriptionConfirmation message to your webhook.")
        println("=".repeat(60) + "\n")
    }

    /**
     * Main email webhook - handles incoming SNS notifications from SES.
     * SNS POSTs JSON here when an email is received.
     *
     * The service automatically confirms SNS subscriptions via SpecialCaseException.
     */
    val emailWebhook = path.path("webhooks").path("email").post bind HttpHandler { request ->
        println("\n" + "=".repeat(40))
        println("Incoming Email Webhook!")
        println("=".repeat(40))

        val headersMap = request.headers.normalizedEntries.mapValues { it.value.map { v -> v.toHttpString() } }
        val messageType = headersMap["x-amz-sns-message-type"]?.firstOrNull()
        println("SNS Message Type: $messageType")

        try {
            val inboundEmail = emailInbound().onReceived.parse(
                queryParameters = request.queryParameters.entries,
                headers = headersMap,
                body = request.body ?: throw IllegalArgumentException("Missing request body")
            )

            // Store the email
            receivedEmails.add(inboundEmail)

            println("From: ${inboundEmail.from.value.raw} ${inboundEmail.from.label?.let { "($it)" } ?: ""}")
            println("To: ${inboundEmail.to.joinToString { it.value.raw }}")
            println("Subject: ${inboundEmail.subject}")
            println("Time: ${inboundEmail.receivedAt}")
            println("Message-ID: ${inboundEmail.messageId}")
            if (inboundEmail.spamScore != null) {
                println("Spam Score: ${inboundEmail.spamScore}")
            }
            println("Body length: ${inboundEmail.plainText?.length ?: 0} plain, ${inboundEmail.html?.length ?: 0} html")
            if (inboundEmail.attachments.isNotEmpty()) {
                println("Attachments: ${inboundEmail.attachments.size}")
                inboundEmail.attachments.forEach { att ->
                    println("  - ${att.filename} (${att.contentType})")
                }
            }

            // Return 200 OK to acknowledge receipt
            HttpResponse(
                status = HttpStatus.OK,
                body = TypedData.text("OK", MediaType.Text.Plain)
            )
        } catch (e: HttpAdapter.SpecialCaseException) {
            // Service handled a special case (subscription confirmation, etc.)
            println("Service handled special case (e.g., subscription confirmation)")
            val resp = e.intendedResponse
            HttpResponse(
                status = HttpStatus(resp.status),
                body = resp.body
            )
        } catch (e: UnsupportedOperationException) {
            if (e.message?.contains("S3") == true) {
                println("Error: Email content stored in S3 (>150KB). S3 retrieval not yet implemented.")
                HttpResponse(
                    status = HttpStatus.OK,
                    body = TypedData.text("S3 content not supported", MediaType.Text.Plain)
                )
            } else {
                println("Unsupported operation: ${e.message}")
                HttpResponse(
                    status = HttpStatus.BadRequest,
                    body = TypedData.text("Error: ${e.message}", MediaType.Text.Plain)
                )
            }
        } catch (e: SecurityException) {
            println("Security error (invalid signature): ${e.message}")
            HttpResponse(
                status = HttpStatus.Forbidden,
                body = TypedData.text("Signature verification failed", MediaType.Text.Plain)
            )
        } catch (e: Exception) {
            println("Error processing webhook: ${e.message}")
            e.printStackTrace()
            HttpResponse(
                status = HttpStatus.BadRequest,
                body = TypedData.text("Error: ${e.message}", MediaType.Text.Plain)
            )
        }
    }

    // ==================== Helper Endpoints ====================

    /**
     * Health check endpoint.
     */
    val health = path.path("health").get bind HttpHandler {
        HttpResponse(body = TypedData.text("OK - Lightning Server SES Email Demo", MediaType.Text.Plain))
    }

    /**
     * Show received emails as plain text.
     */
    val emailsEndpoint = path.path("emails").get bind HttpHandler {
        val emailList = if (receivedEmails.isEmpty()) {
            "(none)"
        } else {
            receivedEmails.joinToString("\n") { email ->
                "From: ${email.from.value.raw}, Subject: ${email.subject}, Attachments: ${email.attachments.size}"
            }
        }
        HttpResponse(
            body = TypedData.text(
                "Received emails (${receivedEmails.size}):\n$emailList",
                MediaType.Text.Plain
            )
        )
    }
}
