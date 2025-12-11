package com.lightningkite.services.voiceagent.openai

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.voiceagent.SerializableToolDescriptor
import com.lightningkite.services.voiceagent.SerializableToolParameterDescriptor
import com.lightningkite.services.voiceagent.SerializableToolParameterType
import com.lightningkite.services.voiceagent.TurnDetection
import com.lightningkite.services.voiceagent.VoiceAgentEvent
import com.lightningkite.services.voiceagent.VoiceAgentService
import com.lightningkite.services.voiceagent.VoiceAgentSession
import com.lightningkite.services.voiceagent.VoiceAgentSessionConfig
import com.lightningkite.services.voiceagent.VoiceConfig
import com.lightningkite.services.voiceagent.AudioFormat as VoiceAudioFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.util.Base64
import javax.sound.sampled.*
import kotlin.random.Random

/**
 * Demo tool definitions for the voice agent.
 * These tools demonstrate how to integrate function calling with voice interactions.
 */
private val demoTools = listOf(
    SerializableToolDescriptor(
        name = "get_current_time",
        description = "Get the current date and time. Use this when the user asks what time it is or the current date.",
    ),
    SerializableToolDescriptor(
        name = "get_weather",
        description = "Get the current weather for a location. Use this when the user asks about the weather.",
        requiredParameters = listOf(
            SerializableToolParameterDescriptor(
                name = "location",
                description = "The city or location to get weather for (e.g., 'New York', 'London')",
                type = SerializableToolParameterType.String
            )
        )
    ),
    SerializableToolDescriptor(
        name = "calculate",
        description = "Perform a mathematical calculation. Use this for math questions.",
        requiredParameters = listOf(
            SerializableToolParameterDescriptor(
                name = "expression",
                description = "The mathematical expression to evaluate (e.g., '2 + 2', '15 * 3')",
                type = SerializableToolParameterType.String
            )
        )
    ),
    SerializableToolDescriptor(
        name = "set_reminder",
        description = "Set a reminder for the user. Use this when the user wants to be reminded of something.",
        requiredParameters = listOf(
            SerializableToolParameterDescriptor(
                name = "message",
                description = "What to remind the user about",
                type = SerializableToolParameterType.String
            ),
            SerializableToolParameterDescriptor(
                name = "minutes",
                description = "Number of minutes from now to trigger the reminder",
                type = SerializableToolParameterType.Integer
            )
        )
    )
)

/**
 * Execute a tool call and return the result as a JSON string.
 */
private fun executeTool(toolName: String, arguments: String): String {
    val args = try {
        Json.decodeFromString<JsonObject>(arguments)
    } catch (e: Exception) {
        return """{"error": "Failed to parse arguments: ${e.message}"}"""
    }

    return when (toolName) {
        "get_current_time" -> {
            val now = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a")
            """{"current_time": "${now.format(formatter)}"}"""
        }

        "get_weather" -> {
            val location = args["location"]?.jsonPrimitive?.content ?: "Unknown"
            // Simulated weather data for demo
            val conditions = listOf("sunny", "partly cloudy", "cloudy", "rainy", "windy")
            val temp = Random.nextInt(45, 85)
            val condition = conditions.random()
            """{"location": "$location", "temperature": $temp, "unit": "fahrenheit", "condition": "$condition", "humidity": ${Random.nextInt(30, 80)}}"""
        }

        "calculate" -> {
            val expression = args["expression"]?.jsonPrimitive?.content ?: ""
            try {
                // Simple evaluation for basic operations
                val result = evaluateExpression(expression)
                """{"expression": "$expression", "result": $result}"""
            } catch (e: Exception) {
                """{"error": "Could not evaluate expression: ${e.message}"}"""
            }
        }

        "set_reminder" -> {
            val message = args["message"]?.jsonPrimitive?.content ?: ""
            val minutes = args["minutes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            // In a real app, you'd actually schedule the reminder
            println("\n[Reminder Set] Will remind in $minutes minutes: \"$message\"")
            """{"status": "success", "message": "Reminder set for $minutes minutes from now", "reminder_text": "$message"}"""
        }

        else -> """{"error": "Unknown tool: $toolName"}"""
    }
}

/**
 * Simple expression evaluator for basic math operations.
 */
private fun evaluateExpression(expr: String): Double {
    // Remove spaces and handle basic operations
    val cleaned = expr.replace(" ", "")

    // Handle addition
    if (cleaned.contains("+")) {
        val parts = cleaned.split("+")
        return parts.sumOf { evaluateExpression(it) }
    }

    // Handle subtraction (but not negative numbers)
    val subIndex = cleaned.lastIndexOf('-')
    if (subIndex > 0) {
        val left = cleaned.substring(0, subIndex)
        val right = cleaned.substring(subIndex + 1)
        return evaluateExpression(left) - evaluateExpression(right)
    }

    // Handle multiplication
    if (cleaned.contains("*")) {
        val parts = cleaned.split("*")
        return parts.map { evaluateExpression(it) }.reduce { a, b -> a * b }
    }

    // Handle division
    if (cleaned.contains("/")) {
        val parts = cleaned.split("/")
        return parts.map { evaluateExpression(it) }.reduce { a, b -> a / b }
    }

    // Base case: parse as number
    return cleaned.toDouble()
}

/**
 * Interactive voice agent demo using the system microphone and speaker.
 *
 * Prerequisites:
 * - Set the OPENAI_API_KEY environment variable
 * - Have a working microphone and speaker connected
 *
 * Run with: ./gradlew :voiceagent-openai:run or execute main() directly in IDE
 */
fun main() = runBlocking {
    OpenAIVoiceAgentService
    val apiKey = System.getenv("OPENAI_API_KEY")
        ?: File("local/openai.txt").readText()

    println("=== OpenAI Voice Agent Demo ===")
    println("Initializing audio devices...")

    // Audio format matching OpenAI's PCM16 24kHz mono
    val audioFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        24000f,  // Sample rate
        16,      // Bits per sample
        1,       // Channels (mono)
        2,       // Frame size (16-bit = 2 bytes)
        24000f,  // Frame rate
        false    // Little endian
    )

    // Set up microphone capture
    val micLine = setupMicrophone(audioFormat)
    if (micLine == null) {
        println("ERROR: Could not access microphone. Check your audio settings.")
        return@runBlocking
    }

    // Set up speaker playback
    val speakerLine = setupSpeaker(audioFormat)
    if (speakerLine == null) {
        println("ERROR: Could not access speaker. Check your audio settings.")
        micLine.close()
        return@runBlocking
    }

    println("Audio devices initialized successfully!")
    println()

    // Create the voice agent service
    val context = TestSettingContext()
    val service = VoiceAgentService.Settings("openai-realtime://?apiKey=$apiKey&model=gpt-4o-realtime-preview-2024-12-17")
        .invoke("demo", context)

    println("Connecting to OpenAI Realtime API...")

    val config = VoiceAgentSessionConfig(
        instructions = """
            You are a friendly and helpful voice assistant. Keep your responses concise
            and conversational. You're having a real-time voice conversation, so respond
            naturally as you would in spoken dialogue.

            You have access to the following tools:
            - get_current_time: Get the current date and time
            - get_weather: Get weather for a location (simulated data for demo)
            - calculate: Perform mathematical calculations
            - set_reminder: Set a reminder for the user

            Use these tools when appropriate to help the user. For example, if they ask
            "What time is it?", use the get_current_time tool. If they ask about weather,
            use the get_weather tool.
        """.trimIndent(),
        voice = VoiceConfig(name = "alloy"),
        turnDetection = TurnDetection.ServerVAD(
            threshold = 0.5,
            prefixPaddingMs = 300,
            silenceDurationMs = 700,
            createResponse = true,
            interruptResponse = true
        ),
        inputAudioFormat = VoiceAudioFormat.PCM16_24K,
        outputAudioFormat = VoiceAudioFormat.PCM16_24K,
        tools = demoTools,
    )

    val session = service.createSession(config)

    println("Session created: ${session.sessionId}")
    println()
    println("=== Voice Agent Ready ===")
    println("Speak into your microphone. The agent will respond through your speakers.")
    println()
    println("Available tools to try:")
    println("  - Ask \"What time is it?\" or \"What's today's date?\"")
    println("  - Ask \"What's the weather in New York?\"")
    println("  - Ask \"What's 25 times 17?\" or other math questions")
    println("  - Ask \"Remind me to call mom in 5 minutes\"")
    println()
    println("Press Ctrl+C to exit.")
    println()

    // Create audio playback queue to ensure sequential playback (prevents stutter/overlap)
    val audioQueue = Channel<ByteArray>(Channel.UNLIMITED)

    // Start audio playback job - plays chunks sequentially from the queue
    val playbackJob = launch(Dispatchers.IO) {
        for (audioBytes in audioQueue) {
            speakerLine.write(audioBytes, 0, audioBytes.size)
        }
    }

    // Start audio capture job
    val captureJob = launch(Dispatchers.IO) {
        captureAudio(micLine, session)
    }

    // Start event handling job (pass session for tool result sending)
    val eventJob = launch {
        handleEvents(session, audioQueue)
    }

    // Wait for interruption
    Runtime.getRuntime().addShutdownHook(Thread {
        println("\nShutting down...")
        runBlocking {
            captureJob.cancel()
            eventJob.cancel()
            playbackJob.cancel()
            audioQueue.close()
            session.close()
            micLine.close()
            speakerLine.close()
        }
    })

    // Keep running until interrupted
    try {
        joinAll(captureJob, eventJob)
    } catch (e: CancellationException) {
        // Expected on shutdown
    }
}

private fun setupMicrophone(format: AudioFormat): TargetDataLine? {
    return try {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            println("Microphone line not supported for format: $format")
            return null
        }
        val line = AudioSystem.getLine(info) as TargetDataLine
        line.open(format, 4800) // 100ms buffer at 24kHz mono 16-bit
        line.start()
        println("Microphone: ${line.lineInfo}")
        line
    } catch (e: Exception) {
        println("Failed to setup microphone: ${e.message}")
        null
    }
}

private fun setupSpeaker(format: AudioFormat): SourceDataLine? {
    return try {
        val info = DataLine.Info(SourceDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            println("Speaker line not supported for format: $format")
            return null
        }
        val line = AudioSystem.getLine(info) as SourceDataLine
        line.open(format, 24000) // 500ms buffer
        line.start()
        println("Speaker: ${line.lineInfo}")
        line
    } catch (e: Exception) {
        println("Failed to setup speaker: ${e.message}")
        null
    }
}

private suspend fun captureAudio(micLine: TargetDataLine, session: VoiceAgentSession) {
    val buffer = ByteArray(2400) // 50ms of audio at 24kHz mono 16-bit
    var totalBytesSent = 0L
    var lastLogTime = System.currentTimeMillis()

    while (currentCoroutineContext().isActive) {
        val bytesRead = micLine.read(buffer, 0, buffer.size)
        if (bytesRead > 0) {
            val audioData = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
            session.sendAudio(audioData)
            totalBytesSent += bytesRead

            // Log every 5 seconds to show audio is being captured
            val now = System.currentTimeMillis()
            if (now - lastLogTime > 5000) {
                println("[Audio] Sent ${totalBytesSent / 1024} KB of audio data")
                lastLogTime = now
            }
        }
        // Small yield to prevent tight loop
        yield()
    }
}

private suspend fun handleEvents(session: VoiceAgentSession, audioQueue: Channel<ByteArray>) {
    val currentTranscript = StringBuilder()
    var isResponseInProgress = false

    session.events.collectLatest { event ->
        when (event) {
            is VoiceAgentEvent.SessionCreated -> {
                println("[Session] Connected successfully")
            }

            is VoiceAgentEvent.SpeechStarted -> {
                println("[You] Speaking...")
            }

            is VoiceAgentEvent.SpeechEnded -> {
                println("[You] Finished speaking")
            }

            is VoiceAgentEvent.InputTranscription -> {
                println("[You] \"${event.text}\"")
            }

            is VoiceAgentEvent.ResponseStarted -> {
                isResponseInProgress = true
                currentTranscript.clear()
                print("[Assistant] ")
            }

            is VoiceAgentEvent.TextDelta -> {
                print(event.delta)
                currentTranscript.append(event.delta)
            }

            is VoiceAgentEvent.AudioDelta -> {
                // Decode base64 audio and queue for sequential playback
                val audioBytes = Base64.getDecoder().decode(event.delta)
                audioQueue.send(audioBytes)
            }

            is VoiceAgentEvent.ResponseDone -> {
                if (isResponseInProgress) {
                    println() // New line after response
                    isResponseInProgress = false
                }
                event.usage?.let { usage ->
                    println("[Usage] Input: ${usage.inputTokens}, Output: ${usage.outputTokens}")
                }
            }

            is VoiceAgentEvent.Error -> {
                println("\n[ERROR] ${event.message}")
                println("  Code: ${event.code}")
            }

            is VoiceAgentEvent.RateLimitsUpdated -> {
                // Silently track rate limits
            }

            is VoiceAgentEvent.ToolCallStarted -> {
                println("\n[Tool] Calling ${event.toolName}...")
            }

            is VoiceAgentEvent.ToolCallDone -> {
                println("[Tool] ${event.toolName} called with: ${event.arguments}")
                // Execute the tool and send result back to the agent
                val result = executeTool(event.toolName, event.arguments)
                println("[Tool] Result: $result")
                session.sendToolResult(event.callId, result)
            }

            else -> {
                // Log other events for debugging
                println("[Debug] Event: ${event::class.simpleName}")
            }
        }
    }
}
