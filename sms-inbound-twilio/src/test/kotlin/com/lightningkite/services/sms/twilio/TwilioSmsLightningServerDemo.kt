package com.lightningkite.services.sms.twilio

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.engine.netty.NettyEngine
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.workingDirectory
import com.lightningkite.services.sms.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

/**
 * Lightning Server demo for Twilio SMS/MMS inbound webhooks.
 *
 * This demonstrates:
 * 1. Setting up a Lightning Server to handle Twilio SMS webhooks
 * 2. Handling inbound SMS messages
 * 3. Processing MMS with media attachments
 *
 * ## Prerequisites
 *
 * 1. Create `local/twilio.env` with your credentials:
 *    ```
 *    sid="AC..."
 *    authToken="..."
 *    ```
 *
 * 2. Expose your local server using ngrok:
 *    ```bash
 *    ngrok http 8080
 *    ```
 *
 * 3. Configure your Twilio phone number's Messaging webhook URL to:
 *    `https://YOUR-NGROK-URL.ngrok.io/webhooks/sms`
 *    Set HTTP method to POST
 *
 * ## Running
 *
 * ```bash
 * ./gradlew :sms-inbound-twilio:runLightningServerDemo
 * ```
 */
object TwilioSmsLightningServerDemo {
    init {
        TwilioSmsInboundService
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("=".repeat(60))
        println("Lightning Server - Twilio SMS Inbound Demo")
        println("=".repeat(60))

        val built = SmsServer.build()

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
            SmsServer.cliInteractive()
        }
        serverJob.cancelAndJoin()
    }
}


object SmsServer : ServerBuilder() {
    val twilio = setting("twilioSms", SmsInboundService.Settings())

    // Store received messages for inspection
    private val receivedMessages = mutableListOf<InboundSms>()

    context(runtime: ServerRuntime)
    suspend fun cliInteractive() {
        println("\n" + "=".repeat(60))
        println("INTERACTIVE OPTIONS")
        println("=".repeat(60))
        println(
            """
            |
            |Enter a command:
            |  messages   - Show received messages
            |  clear      - Clear received messages
            |  quit       - Stop server and exit
            |
        """.trimMargin()
        )

        // Interactive command loop
        while (currentCoroutineContext().isActive) {
            print("\n> ")
            val input = readlnOrNull()?.trim() ?: continue

            when {
                input == "messages" -> {
                    if (receivedMessages.isEmpty()) {
                        println("No messages received yet.")
                    } else {
                        println("Received messages (${receivedMessages.size}):")
                        receivedMessages.forEachIndexed { index, sms ->
                            println("  ${index + 1}. From: ${sms.from.raw}")
                            println("     To: ${sms.to.raw}")
                            println("     Body: ${sms.body}")
                            println("     Time: ${sms.receivedAt}")
                            if (sms.mediaUrls.isNotEmpty()) {
                                println("     Media (${sms.mediaUrls.size}):")
                                sms.mediaUrls.forEachIndexed { i, url ->
                                    println("       ${i + 1}. ${sms.mediaContentTypes.getOrNull(i) ?: "unknown"}: $url")
                                }
                            }
                            println("     Provider ID: ${sms.providerMessageId}")
                            println()
                        }
                    }
                }

                input == "clear" -> {
                    receivedMessages.clear()
                    println("Messages cleared.")
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
     * Configure webhook on startup.
     */
    val smsWebhookConfigure = path.path("webhooks").path("sms").path("startup") bind StartupTask {
        twilio().onReceived.configureWebhook(smsWebhook.location.path.resolved().fullUrl())
    }

    /**
     * Main SMS webhook - handles incoming SMS/MMS messages.
     * Twilio POSTs form data here when a message comes in.
     */
    val smsWebhook = path.path("webhooks").path("sms").post bind HttpHandler { request ->
        println("\n" + "=".repeat(40))
        println("Incoming SMS Webhook!")
        println("=".repeat(40))

        try {
            val inboundSms = twilio().onReceived.parse(
                queryParameters = request.queryParameters.entries,
                headers = request.headers.normalizedEntries.mapValues { it.value.map { v -> v.toHttpString() } },
                body = request.body ?: throw BadRequestException("Missing request body")
            )

            // Store the message
            receivedMessages.add(inboundSms)

            println("From: ${inboundSms.from.raw}")
            println("To: ${inboundSms.to.raw}")
            println("Body: ${inboundSms.body}")
            println("Time: ${inboundSms.receivedAt}")
            if (inboundSms.mediaUrls.isNotEmpty()) {
                println("Media attachments: ${inboundSms.mediaUrls.size}")
                inboundSms.mediaUrls.forEachIndexed { i, url ->
                    println("  ${i + 1}. ${inboundSms.mediaContentTypes.getOrNull(i) ?: "unknown"}")
                }
            }
            println("Provider Message ID: ${inboundSms.providerMessageId}")

            // Return empty TwiML response (no auto-reply)
            // You could return TwiML here to send an auto-reply
            HttpResponse(
                status = HttpStatus.OK,
                body = TypedData.text(
                    """<?xml version="1.0" encoding="UTF-8"?><Response></Response>""",
                    MediaType.Application.Xml
                )
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

    /**
     * Status callback webhook - receives message delivery status updates.
     */
    val statusWebhook = path.path("webhooks").path("status").post bind HttpHandler { request ->
        val params = parseFormData(request.body?.text() ?: "")
        val messageSid = params["MessageSid"] ?: "unknown"
        val messageStatus = params["MessageStatus"]
        val errorCode = params["ErrorCode"]

        println("\n" + "-".repeat(40))
        println("Message Status Update!")
        println("-".repeat(40))
        println("Message SID: $messageSid")
        println("Status: $messageStatus")
        if (errorCode != null) {
            println("Error Code: $errorCode")
        }

        HttpResponse(null, HttpStatus.NoContent)
    }

    // ==================== Helper Endpoints ====================

    /**
     * Health check endpoint.
     */
    val health = path.path("health").get bind HttpHandler {
        HttpResponse(body = TypedData.text("OK - Lightning Server SMS Demo", MediaType.Text.Plain))
    }

    /**
     * Show received messages as JSON.
     */
    val messagesEndpoint = path.path("messages").get bind HttpHandler {
        val messageList = if (receivedMessages.isEmpty()) {
            "(none)"
        } else {
            receivedMessages.joinToString("\n") { sms ->
                "From: ${sms.from.raw}, Body: ${sms.body}, Media: ${sms.mediaUrls.size}"
            }
        }
        HttpResponse(
            body = TypedData.text(
                "Received messages (${receivedMessages.size}):\n$messageList",
                MediaType.Text.Plain
            )
        )
    }

    // ==================== Helpers ====================

    private fun parseFormData(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        return body.split("&")
            .filter { it.contains("=") }
            .associate {
                val (key, value) = it.split("=", limit = 2)
                java.net.URLDecoder.decode(key, "UTF-8") to
                        java.net.URLDecoder.decode(value, "UTF-8")
            }
    }
}
