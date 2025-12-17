package com.lightningkite.services.voiceagent.phonecall

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.engine.netty.NettyEngine
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.lightningserver.typed.MetaEndpoints
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.send
import com.lightningkite.lightningserver.websockets.text
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.workingDirectory
import com.lightningkite.services.database.Database
import com.lightningkite.services.phonecall.*
import com.lightningkite.services.phonecall.twilio.TwilioPhoneCallService
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.voiceagent.*
import com.lightningkite.services.voiceagent.openai.OpenAIVoiceAgentService
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Lightning Server demo for Phone Call + Voice Agent integration.
 *
 * This demonstrates:
 * 1. Setting up a Lightning Server to handle Twilio phone call webhooks
 * 2. Bridging phone call audio streams with OpenAI's Realtime Voice Agent
 * 3. Tool calling during phone conversations
 *
 * ## Prerequisites
 *
 * 1. Create `local/settings.json` with your credentials (auto-generated on first run):
 *    ```json
 *    {
 *      "phonecall": { "url": "twilio://ACCOUNT_SID:AUTH_TOKEN@+1XXXXXXXXXX" },
 *      "voiceagent": { "url": "openai-realtime://?apiKey=sk-..." }
 *    }
 *    ```
 *
 * 2. Expose your local server using ngrok:
 *    ```bash
 *    ngrok http 8080
 *    ```
 *
 * 3. Configure your Twilio phone number:
 *    - Voice webhook URL: `https://YOUR-NGROK-URL.ngrok.io/webhooks/incoming-call` (POST)
 *    - Status callback URL: `https://YOUR-NGROK-URL.ngrok.io/webhooks/call-status` (POST)
 *
 * ## Running
 *
 * ```bash
 * ./gradlew :voiceagent-phonecall:runLightningServerDemo
 * ```
 *
 * Then call your Twilio phone number to talk to the AI voice agent!
 */
object PhoneCallVoiceAgentLightningServerDemo {
    init {
        // Ensure services are registered
        TwilioPhoneCallService
        OpenAIVoiceAgentService
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("=".repeat(60))
        println("Lightning Server - Phone Call Voice Agent Demo")
        println("=".repeat(60))

        val built = VoiceAgentServer.build()

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
            VoiceAgentServer.cliInteractive()
        }
        serverJob.cancelAndJoin()
    }
}

object VoiceAgentServer : ServerBuilder() {
    val phonecall = setting("phone", PhoneCallService.Settings())
    val voiceagent = setting("voiceAgent", VoiceAgentService.Settings())
    val pubsub = setting("pubSub", PubSub.Settings())
    val database = setting("databaseRam", Database.Settings("ram"))
    val cache = setting("cache", Cache.Settings("ram"))

    // Track active calls
    private val activeCalls = mutableMapOf<String, CallInfo>()
    private val callLogs = mutableListOf<String>()

    @Serializable
    data class CallInfo(
        val callId: String,
        val from: String,
        val to: String,
        val startTime: String,
        var status: String = "connecting"
    )

    context(runtime: ServerRuntime)
    suspend fun cliInteractive() {
        val baseUrl = generalSettings().publicUrl
        println("\n" + "=".repeat(60))
        println("SERVER READY")
        println("=".repeat(60))
        println(
            """
            |
            |Your webhook URLs (configure in Twilio):
            |  Incoming call: $baseUrl/webhooks/incoming-call
            |  Call status:   $baseUrl/webhooks/call-status
            |  Audio stream:  ${baseUrl.replace("http", "ws")}/voice-ai
            |
            |Commands:
            |  calls    - Show active calls
            |  logs     - Show recent logs
            |  clear    - Clear logs
            |  quit     - Stop server and exit
            |
        """.trimMargin()
        )

        // Interactive command loop
        while (currentCoroutineContext().isActive) {
            print("\n> ")
            val input = readlnOrNull()?.trim() ?: continue

            when {
                input == "calls" -> {
                    if (activeCalls.isEmpty()) {
                        println("No active calls.")
                    } else {
                        println("Active calls (${activeCalls.size}):")
                        activeCalls.values.forEach { call ->
                            println("  ${call.callId}: ${call.from} -> ${call.to} [${call.status}]")
                        }
                    }
                }

                input == "logs" -> {
                    if (callLogs.isEmpty()) {
                        println("No logs yet.")
                    } else {
                        println("Recent logs (${callLogs.size}):")
                        callLogs.takeLast(20).forEach { println("  $it") }
                    }
                }

                input == "clear" -> {
                    callLogs.clear()
                    println("Logs cleared.")
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

    private fun log(message: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val logLine = "[$timestamp] $message"
        callLogs.add(logLine)
        println(logLine)
    }

    // ==================== Demo Tool Definitions ====================

    private val demoTools = listOf(
        SerializableToolDescriptor(
            name = "get_current_time",
            description = "Get the current date and time",
        ),
        SerializableToolDescriptor(
            name = "get_weather",
            description = "Get weather for a location",
            requiredParameters = listOf(
                SerializableToolParameterDescriptor(
                    name = "location",
                    description = "City name",
                    type = SerializableToolParameterType.String
                )
            )
        ),
        SerializableToolDescriptor(
            name = "book_appointment",
            description = "Book an appointment",
            requiredParameters = listOf(
                SerializableToolParameterDescriptor(
                    name = "date",
                    description = "Date in YYYY-MM-DD format",
                    type = SerializableToolParameterType.String
                ),
                SerializableToolParameterDescriptor(
                    name = "time",
                    description = "Time in HH:MM format",
                    type = SerializableToolParameterType.String
                ),
                SerializableToolParameterDescriptor(
                    name = "service",
                    description = "Type of service",
                    type = SerializableToolParameterType.String
                )
            )
        ),
        SerializableToolDescriptor(
            name = "transfer_to_human",
            description = "Transfer the call to a human agent",
            requiredParameters = listOf(
                SerializableToolParameterDescriptor(
                    name = "reason",
                    description = "Reason for transfer",
                    type = SerializableToolParameterType.String
                )
            )
        )
    )

    private fun executeTool(toolName: String, arguments: String): String {
        val args = try {
            Json.decodeFromString<JsonObject>(arguments)
        } catch (e: Exception) {
            return """{"error": "Failed to parse arguments"}"""
        }

        return when (toolName) {
            "get_current_time" -> {
                val now = LocalDateTime.now()
                val formatted = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a"))
                """{"current_time": "$formatted"}"""
            }

            "get_weather" -> {
                val location = args["location"]?.jsonPrimitive?.content ?: "Unknown"
                val conditions = listOf("sunny", "partly cloudy", "cloudy", "rainy")
                val temp = Random.nextInt(50, 85)
                """{"location": "$location", "temperature": $temp, "unit": "fahrenheit", "condition": "${conditions.random()}"}"""
            }

            "book_appointment" -> {
                val date = args["date"]?.jsonPrimitive?.content ?: ""
                val time = args["time"]?.jsonPrimitive?.content ?: ""
                val service = args["service"]?.jsonPrimitive?.content ?: ""
                log("APPOINTMENT BOOKED: $service on $date at $time")
                """{"status": "confirmed", "date": "$date", "time": "$time", "service": "$service", "confirmation_number": "APT${Random.nextInt(10000, 99999)}"}"""
            }

            "transfer_to_human" -> {
                val reason = args["reason"]?.jsonPrimitive?.content ?: ""
                log("TRANSFER REQUESTED: $reason")
                """{"status": "transferring", "reason": "$reason", "estimated_wait": "2 minutes"}"""
            }

            else -> """{"error": "Unknown tool: $toolName"}"""
        }
    }

    // ==================== Webhook Endpoints ====================

    /**
     * Configure webhooks on startup.
     */
    val configureWebhooks = path.path("webhooks").path("startup") bind StartupTask {
        phonecall().onIncomingCall.configureWebhook(incomingCallWebhook.location.path.resolved().fullUrl())
        phonecall().onCallStatus.configureWebhook(callStatusWebhook.location.path.resolved().fullUrl())
        log("Webhooks configured")
    }

    /**
     * Incoming call webhook - returns TwiML to connect to voice agent.
     */
    val incomingCallWebhook = path.path("webhooks").path("incoming-call").post bind HttpHandler { request ->
        log("Incoming call webhook received")

        try {
            val event = phonecall().onIncomingCall.parse(
                queryParameters = request.queryParameters.entries,
                headers = request.headers.normalizedEntries.mapValues { it.value.map { v -> v.toHttpString() } },
                body = request.body ?: throw BadRequestException("Missing request body")
            )

            log("Incoming call from ${event.from.raw} to ${event.to.raw}")

            // Track the call
            activeCalls[event.callId] = CallInfo(
                callId = event.callId,
                from = event.from.raw,
                to = event.to.raw,
                startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            )

            // Get WebSocket URL for audio streaming
            val wsUrl = generalSettings().publicUrl
                .replace("http://", "wss://")
                .replace("https://", "wss://") + "/voice-ai"

            // Return instructions to greet and connect to voice agent
            val instructions = createVoiceAgentStreamInstructions(
                websocketUrl = wsUrl,
                greeting = "Hello! Welcome to our AI assistant. How can I help you today?",
                customParameters = mapOf("callId" to event.callId)
            )

            val response = phonecall().onIncomingCall.render(instructions)
            log("Returning TwiML to connect to voice agent at $wsUrl")
            log("TwiML: ${response.body?.text()}")

            HttpResponse(
                status = HttpStatus(response.status),
                body = response.body,
                headers = HttpHeaders(response.headers.entries.flatMap { it.value.map { v -> it.key to v } })
            )
        } catch (e: Exception) {
            log("Error processing incoming call: ${e.message}")
            e.printStackTrace()
            HttpResponse(
                status = HttpStatus.InternalServerError,
                body = TypedData.text("Error: ${e.message}", MediaType.Text.Plain)
            )
        }
    }

    /**
     * Call status webhook - tracks call lifecycle.
     */
    val callStatusWebhook = path.path("webhooks").path("call-status").post bind HttpHandler { request ->
        try {
            val event = phonecall().onCallStatus.parse(
                queryParameters = request.queryParameters.entries,
                headers = request.headers.normalizedEntries.mapValues { it.value.map { v -> v.toHttpString() } },
                body = request.body ?: throw BadRequestException("Missing request body")
            )

            log("Call ${event.callId} status: ${event.status}")

            // Update tracked call
            activeCalls[event.callId]?.status = event.status.name.lowercase()

            // Remove completed calls
            if (event.status in listOf(CallStatus.COMPLETED, CallStatus.FAILED, CallStatus.BUSY, CallStatus.NO_ANSWER)) {
                activeCalls.remove(event.callId)
                log("Call ${event.callId} ended: ${event.status}")
            }

            HttpResponse(null, HttpStatus.NoContent)
        } catch (e: Exception) {
            log("Error processing call status: ${e.message}")
            HttpResponse(null, HttpStatus.NoContent)
        }
    }

    // ==================== Voice Agent WebSocket ====================

    /** Session config for voice agent */
    private val voiceAgentSessionConfig = VoiceAgentSessionConfig(
        instructions = """
            You are a friendly and professional AI phone assistant. You're speaking with a caller
            on the phone, so keep your responses conversational and concise.

            You can help with:
            - Telling the time and date (use get_current_time tool)
            - Providing weather information (use get_weather tool)
            - Booking appointments (use book_appointment tool)
            - Transferring to a human agent if needed (use transfer_to_human tool)

            Always be polite and helpful. If you're not sure about something, offer to
            transfer the caller to a human agent.
        """.trimIndent(),
        voice = VoiceConfig(name = "alloy"),
        turnDetection = TurnDetection.ServerVAD(
            threshold = 0.5,
            prefixPaddingMs = 300,
            silenceDurationMs = 800,
            createResponse = true,
            interruptResponse = true
        ),
        tools = demoTools,
    )

    /**
     * Creates a handler for this request. Each Lambda instance creates its own -
     * cross-instance communication happens via PubSub.
     */
    context(runtime: ServerRuntime)
    private fun handler() = PubSubVoiceAgentHandler(
        voiceAgentService = voiceagent(),
        pubsub = pubsub(),
        audioStreamAdapter = phonecall().audioStream
            ?: throw BadRequestException("Phone service doesn't support audio streaming"),
        sessionConfig = voiceAgentSessionConfig,
        toolHandler = ::executeTool,
    )

    /**
     * WebSocket endpoint for bidirectional audio streaming with voice agent.
     *
     * Uses [PubSubVoiceAgentHandler] for Lambda-compatible deployment where each
     * WebSocket event may hit a different instance.
     */
    val voiceAgentWebSocket = path.path("voice-ai") bind WebSocketHandler(
        willConnect = {
            log("Voice AI WebSocket willConnect")
            handler().createState()
        },
        didConnect = {
            log("Voice AI WebSocket didConnect: ${currentState.connectionId}")
            handler().onConnect(currentState) { send(it) }
        },
        messageFromClient = { frameData ->
            handler().onMessage(currentState, frameData.text)
        },
        disconnect = {
            log("Voice AI WebSocket disconnect: ${currentState.connectionId}")
            handler().onDisconnect(currentState)
        }
    )

    /** Simple echo WebSocket to test if Twilio can connect at all */
    val testWebSocket = path.path("test-ws") bind WebSocketHandler(
        willConnect = {
            log("Test WebSocket willConnect")
            Unit
        },
        didConnect = {
            log("Test WebSocket didConnect!")
            send("Hello from server!")
        },
        messageFromClient = { frameData ->
            log("Test WebSocket received: ${frameData.text.take(100)}")
            send("Echo: ${frameData.text.take(100)}")
        },
        disconnect = {
            log("Test WebSocket disconnect")
        }
    )

    // ==================== Helper Endpoints ====================

    /**
     * Health check endpoint.
     */
    val health = path.path("health").get bind HttpHandler {
        HttpResponse(body = TypedData.text("OK - Phone Call Voice Agent Demo", MediaType.Text.Plain))
    }

    val meta = path.path("meta") module MetaEndpoints("asdf", database, cache)

    /**
     * Show active calls.
     */
    val callsEndpoint = path.path("calls").get bind HttpHandler {
        val callList = if (activeCalls.isEmpty()) {
            "No active calls"
        } else {
            activeCalls.values.joinToString("\n") { call ->
                "${call.callId}: ${call.from} -> ${call.to} [${call.status}] started ${call.startTime}"
            }
        }
        HttpResponse(body = TypedData.text("Active calls:\n$callList", MediaType.Text.Plain))
    }

    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Phone Call Voice Agent Demo - OK")
    }
}
