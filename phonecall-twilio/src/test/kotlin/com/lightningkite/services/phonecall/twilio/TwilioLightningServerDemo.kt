package com.lightningkite.services.phonecall.twilio

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
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.send
import com.lightningkite.lightningserver.websockets.text
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebsocketAdapter
import com.lightningkite.services.data.workingDirectory
import com.lightningkite.services.phonecall.*
import com.lightningkite.toPhoneNumber
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Lightning Server demo for Twilio phone call webhooks.
 *
 * This demonstrates:
 * 1. Setting up a Lightning Server to handle Twilio webhooks
 * 2. Handling inbound calls with CallInstructions responses
 * 3. Processing Gather results (DTMF input from users)
 * 4. Making outbound calls that reference webhooks
 *
 * ## Prerequisites
 *
 * 1. Create `local/twilio.env` with your credentials:
 *    ```
 *    phoneNumber="+14355551234"
 *    sid="SK..."
 *    secret="..."
 *    accountSid="AC..."
 *    ```
 *
 * 2. Expose your local server using ngrok:
 *    ```bash
 *    ngrok http 8080
 *    ```
 *
 * 3. Configure your Twilio phone number's Voice webhook URL to:
 *    `https://YOUR-NGROK-URL.ngrok.io/webhooks/voice`
 *
 * ## Running
 *
 * ```bash
 * ./gradlew :phonecall-twilio:runLightningServerDemo
 * ```
 */
object TwilioLightningServerDemo {
    init {
        TwilioPhoneCallService
    }
    // ==================== Main ====================

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("=".repeat(60))
        println("🚀 Lightning Server - Twilio Phone Demo")
        println("=".repeat(60))

        val built = PhoneServer.build()

        val engine = NettyEngine(built, Clock.System)

        // Start server in background
        val serverJob = launch(Dispatchers.Default) {
            try {
                engine.settings.loadFromFile(workingDirectory.then("settings.json"), engine.internalSerializersModule)
                println("Engine starting...")
                engine.start()
                println("Engine started!")
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }
        with(engine) {
            PhoneServer.cliInteractive()
        }
        serverJob.cancelAndJoin()

    }
}


object PhoneServer : ServerBuilder() {
    val twilio = setting("twilio", PhoneCallService.Settings())

    // Track active calls and their state
    private val callStates = mutableMapOf<String, CallState>()

    data class CallState(
        val from: String,
        val to: String,
        var menuSelection: String? = null,
        var gatheredDigits: String? = null
    )

    context(runtime: ServerRuntime)
    suspend fun cliInteractive() {
        println("\n" + "=".repeat(60))
        println("🎮 INTERACTIVE OPTIONS")
        println("=".repeat(60))
        println("""
            |
            |Enter a command:
            |  call <phone>  - Make an outbound call
            |  status        - Show active calls
            |  quit          - Stop server and exit
            |
        """.trimMargin())

        // Interactive command loop
        while (currentCoroutineContext().isActive) {
            print("\n> ")
            val input = readlnOrNull()?.trim() ?: continue

            when {
                input.startsWith("call ") -> {
                    val phoneNumber = input.removePrefix("call ").trim()
                    if (phoneNumber.isNotEmpty()) {
                        println("📞 Initiating call to $phoneNumber...")
                        try {
                            val callId = twilio().startCall(
                                phoneNumber.toPhoneNumber(),
                                OutboundCallOptions(
                                    machineDetection = MachineDetectionMode.ENABLED,
                                )
                            )
                            println("✅ Call started! SID: $callId")

                            // Speak a message
                            twilio().speak(callId, "Hello! This is an outbound call from the Lightning Server demo. Goodbye!")
                            twilio().hangup(callId)
                            println("✅ Call completed!")
                        } catch (e: Exception) {
                            println("❌ Call failed: ${e.message}")
                        }
                    }
                }
                input == "status" -> {
                    println("Active calls: (check /calls endpoint)")
                }
                input == "quit" || input == "exit" -> {
                    println("👋 Shutting down...")
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
     * Main voice webhook - handles incoming calls.
     * Twilio POSTs form data here when a call comes in.
     */
    val voiceWebhookConfigure = path.path("webhooks").path("voice").path("startup") bind StartupTask {
        twilio().onIncomingCall.configureWebhook(voiceWebhook.location.path.resolved().fullUrl())
    }
    val voiceWebhook = path.path("webhooks").path("voice").post bind HttpHandler { request ->
        val params = parseFormData(request.body?.text() ?: "")
        val callSid = params["CallSid"] ?: "unknown"
        val from = params["From"] ?: "unknown"
        val to = params["To"] ?: "unknown"

        println("\n📞 Incoming call!")
        println("   Call SID: $callSid")
        println("   From: $from")
        println("   To: $to")

        // Track this call
        callStates[callSid] = CallState(from, to)

        // Build response TwiML using the service's instruction rendering
        val instructions = CallInstructions.Say(
            text = "Welcome to the Lightning Server phone demo! ",
            then = CallInstructions.Gather(
                prompt = "Press 1 to hear a joke. Press 2 to record a message. Press 3 to be transferred. Press 4 for audio streaming echo demo. Or press 9 to hang up.",
                numDigits = 1,
                actionUrl = gatherWebhook.location.path.resolved().fullUrl(),
                then = CallInstructions.Say(
                    text = "We didn't receive your input. Goodbye!",
                    then = CallInstructions.Hangup
                )
            )
        )

        val response = twilio().onIncomingCall.render(instructions)
        HttpResponse(
            status = HttpStatus(response.status),
            body = response.body,
            headers = HttpHeaders(response.headers.entries.flatMap { it.value.map { v -> it.key to v }})
        )
    }

    /**
     * Gather webhook manually defined in a call instruction - handles DTMF digit input from the user.
     */
    val gatherWebhook = path.path("webhooks").path("gather").post bind HttpHandler { request ->
        val params = parseFormData(request.body?.text() ?: "")
        val callSid = params["CallSid"] ?: "unknown"
        val digits = params["Digits"]
        val speech = params["SpeechResult"]

        println("\n🔢 Gather result received!")
        println("   Call SID: $callSid")
        println("   Digits: $digits")
        println("   Speech: $speech")

        // Update call state
        callStates[callSid]?.gatheredDigits = digits

        val instructions: CallInstructions = when (digits) {
            "1" -> {
                // Tell a joke
                CallInstructions.Say(
                    text = "Here's a joke for you: Why do programmers prefer dark mode? Because light attracts bugs!",
                    then = CallInstructions.Pause(
                        duration = 1.seconds,
                        then = CallInstructions.Redirect("/webhooks/voice")
                    )
                )
            }
            "2" -> {
                // Record a message
                CallInstructions.Say(
                    text = "Please leave your message after the beep. Press pound when finished.",
                    then = CallInstructions.Record(
                        maxDuration = 30.seconds,
                        actionUrl = "/webhooks/recording",
                        transcribe = true,
                        playBeep = true
                    )
                )
            }
            "3" -> {
                // Transfer (demo - just says transferring)
                CallInstructions.Say(
                    text = "Transferring you now. Just kidding, this is a demo! Returning to the main menu.",
                    then = CallInstructions.Redirect("/webhooks/voice")
                )
            }
            "4" -> {
                // Audio streaming demo - echoes audio back with a delay
                // Build WebSocket URL from publicUrl setting
                val publicUrl = generalSettings().publicUrl.removeSuffix("/")
                val wsUrl = publicUrl
                    .replace("http://", "wss://")
                    .replace("https://", "wss://") + "/webhooks/audio-stream"
                CallInstructions.Say(
                    text = "Starting audio stream demo. Speak and you'll hear your voice echoed back. Press any key to stop.",
                    then = CallInstructions.StreamAudio(
                        websocketUrl = wsUrl,
                        track = AudioTrack.INBOUND,
                        customParameters = mapOf("callSid" to callSid),
                        then = CallInstructions.Say(
                            text = "Audio streaming ended. Returning to main menu.",
                            then = CallInstructions.Redirect("/webhooks/voice")
                        )
                    )
                )
            }
            "9" -> {
                // Hang up
                CallInstructions.Say(
                    text = "Thank you for trying the Lightning Server phone demo. Goodbye!",
                    then = CallInstructions.Hangup
                )
            }
            else -> {
                // Invalid input
                CallInstructions.Say(
                    text = "Sorry, that's not a valid option.",
                    then = CallInstructions.Redirect("/webhooks/voice")
                )
            }
        }

        // jank...
        val response = twilio().onIncomingCall.render(instructions)
        HttpResponse(
            status = HttpStatus(response.status),
            body = response.body,
            headers = HttpHeaders(response.headers.entries.flatMap { it.value.map { v -> it.key to v }})
        )
    }

    /**
     * Transcription webhook - receives recorded audio URL.
     */
    val transcriptionWebhookConfigure = path.path("webhooks").path("transcription").path("startup") bind StartupTask {
        twilio().onTranscription.configureWebhook(transcriptionWebhook.location.path.resolved().fullUrl())
    }
    val transcriptionWebhook = path.path("webhooks").path("transcription").post bind HttpHandler { request ->
        val parsed = twilio().onTranscription.parse(request.queryParameters.entries, request.headers.normalizedEntries.mapValues { it.value.map { it.toHttpString() } }, request.body ?: throw BadRequestException())

        println("\n🎙️ Recording received!")
        println("   Call SID: ${parsed.callId}")
        println("   Transcription: ${parsed.text}")

        val instructions = CallInstructions.Say(
            text = "Thank you for your message! Returning to the main menu.",
            then = CallInstructions.Redirect("/webhooks/voice")
        )

        HttpResponse(null, HttpStatus.NoContent)
    }

    /**
     * Status callback - receives call status updates.
     */
    val statusWebhook = path.path("webhooks").path("status").post bind HttpHandler { request ->
        val params = parseFormData(request.body?.text() ?: "")
        val callSid = params["CallSid"] ?: "unknown"
        val callStatus = params["CallStatus"]
        val duration = params["CallDuration"]

        println("\n📊 Call status update!")
        println("   Call SID: $callSid")
        println("   Status: $callStatus")
        println("   Duration: ${duration ?: "N/A"} seconds")

        if (callStatus == "completed" || callStatus == "failed" || callStatus == "busy" || callStatus == "no-answer") {
            callStates.remove(callSid)
            println("   Call removed from tracking")
        }

        HttpResponse(null, HttpStatus.NoContent)
    }

    // ==================== Audio Streaming WebSocket ====================

    /**
     * State tracked for each audio stream WebSocket connection.
     */
    data class AudioStreamState(
        val adapter: WebsocketAdapter<AudioStreamStart, AudioStreamEvent, AudioStreamCommand>,
        var streamId: String? = null,
        var callId: String? = null
    )

    /**
     * Audio stream WebSocket - receives audio from Twilio and echoes it back.
     *
     * This demonstrates bidirectional audio streaming using the TwilioAudioStreamAdapter.
     * In a real application, you would process the audio (e.g., send to AI, transcribe, etc.)
     * and send back generated audio.
     */
    val audioStreamWebhook = path.path("webhooks").path("audio-stream") bind WebSocketHandler(
        willConnect = {
            // Parse the connection and validate it
            val adapter = twilio().audioStream ?: throw BadRequestException("Audio streaming not supported")
            println("\n🎙️ Audio stream WebSocket connecting...")

            // Return initial state - we'll update it when we get the "start" event
            AudioStreamState(adapter = adapter)
        },
        didConnect = {
            println("🎙️ Audio stream WebSocket connected!")
        },
        messageFromClient = { frameData ->
            val adapter = currentState.adapter
            val frame = WebsocketAdapter.Frame.Text(frameData.text)

            try {
                val event = adapter.parse(frame)

                when (event) {
                    is AudioStreamEvent.Connected -> {
                        println("🎙️ Stream connected: callId=${event.callId}, streamId=${event.streamId}")
                        currentState.streamId = event.streamId
                        currentState.callId = event.callId
                    }
                    is AudioStreamEvent.Audio -> {
                        // Echo the audio back with the same payload
                        // In a real app, you'd process this audio (send to AI, etc.)
                        val streamId = currentState.streamId ?: event.streamId
                        if (streamId.isNotEmpty()) {
                            val echoCommand = AudioStreamCommand.Audio(
                                streamId = streamId,
                                payload = event.payload
                            )
                            val responseFrame = adapter.render(echoCommand)
                            send((responseFrame as WebsocketAdapter.Frame.Text).text)
                        }
                    }
                    is AudioStreamEvent.Dtmf -> {
                        println("🎙️ DTMF received: ${event.digit} - closing stream")
                        // On any key press, close the WebSocket to end the stream
                        // This will cause Twilio to proceed to the "then" instruction
                        close(WebSocketClose.NORMAL)
                    }
                    is AudioStreamEvent.Stop -> {
                        println("🎙️ Stream stopping: ${event.streamId}")
                    }
                    is AudioStreamEvent.NoOp -> {
                        // Ignore no-op events
                    }
                }
            } catch (e: Exception) {
                println("🎙️ Error parsing audio stream message: ${e.message}")
                e.printStackTrace()
            }
        },
        disconnect = {
            println("🎙️ Audio stream WebSocket disconnected")
        }
    )

    // ==================== Helper Endpoints ====================

    /**
     * Health check endpoint.
     */
    val health = path.path("health").get bind HttpHandler {
        HttpResponse(body = TypedData.text("OK - Lightning Server Phone Demo", MediaType.Text.Plain))
    }

    /**
     * Show active calls.
     */
    val activeCalls = path.path("calls").get bind HttpHandler {
        val callList = callStates.entries.joinToString("\n") { (sid, state) ->
            "  $sid: from=${state.from}, to=${state.to}, digits=${state.gatheredDigits}"
        }
        HttpResponse(
            body = TypedData.text(
                "Active calls:\n${if (callList.isEmpty()) "  (none)" else callList}",
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

    private fun buildFallbackTwiml(instructions: CallInstructions): String {
        // Simple fallback if service not available
        return """<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Say>Service not configured. Please try again later.</Say>
  <Hangup/>
</Response>"""
    }

    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Alive")
    }
}