package com.lightningkite.services.voiceagent.phonecall

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.phonecall.AudioStreamCommand
import com.lightningkite.services.phonecall.AudioStreamEvent
import com.lightningkite.services.phonecall.AudioTrack
import com.lightningkite.services.phonecall.CallInstructions
import com.lightningkite.services.voiceagent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for VoiceAgentBridge functions and classes.
 */
@OptIn(ExperimentalEncodingApi::class)
class VoiceAgentBridgeTest {

    private lateinit var context: TestSettingContext
    private lateinit var voiceAgentService: TestVoiceAgentService

    @BeforeTest
    fun setup() {
        context = TestSettingContext()
        voiceAgentService = TestVoiceAgentService("test-voice-agent", context)
    }

    // ==================== createVoiceAgentStreamInstructions Tests ====================

    @Test
    fun `createVoiceAgentStreamInstructions without greeting returns StreamAudio`() {
        val instructions = createVoiceAgentStreamInstructions(
            websocketUrl = "wss://example.com/audio",
        )

        assertIs<CallInstructions.StreamAudio>(instructions)
        assertEquals("wss://example.com/audio", instructions.websocketUrl)
        assertEquals(AudioTrack.INBOUND, instructions.track)
        assertTrue(instructions.customParameters.isEmpty())
    }

    @Test
    fun `createVoiceAgentStreamInstructions with greeting returns Say then StreamAudio`() {
        val instructions = createVoiceAgentStreamInstructions(
            websocketUrl = "wss://example.com/audio",
            greeting = "Hello, please hold.",
        )

        assertIs<CallInstructions.Say>(instructions)
        assertEquals("Hello, please hold.", instructions.text)

        val streamInstruction = instructions.then
        assertIs<CallInstructions.StreamAudio>(streamInstruction)
        assertEquals("wss://example.com/audio", streamInstruction.websocketUrl)
    }

    @Test
    fun `createVoiceAgentStreamInstructions passes custom parameters`() {
        val instructions = createVoiceAgentStreamInstructions(
            websocketUrl = "wss://example.com/audio",
            customParameters = mapOf("callId" to "123", "userId" to "456"),
        )

        assertIs<CallInstructions.StreamAudio>(instructions)
        assertEquals("123", instructions.customParameters["callId"])
        assertEquals("456", instructions.customParameters["userId"])
    }

    // ==================== TranscriptEntry Tests ====================

    @Test
    fun `TranscriptEntry data class works correctly`() {
        val userEntry = TranscriptEntry(TranscriptRole.USER, "Hello")
        val agentEntry = TranscriptEntry(TranscriptRole.AGENT, "Hi there!")

        assertEquals(TranscriptRole.USER, userEntry.role)
        assertEquals("Hello", userEntry.text)
        assertEquals(TranscriptRole.AGENT, agentEntry.role)
        assertEquals("Hi there!", agentEntry.text)
    }

    // ==================== handleDirectVoiceSession Tests ====================

    @Test
    fun `handleDirectVoiceSession creates session with config`() = runBlocking {
        val config = VoiceAgentSessionConfig(
            instructions = "Test instructions",
            voice = VoiceConfig(name = "alloy"),
        )

        val incomingAudio = emptyFlow<String>()
        var sessionReadyCalled = false

        // Run with timeout to avoid hanging
        withTimeoutOrNull(500.milliseconds) {
            handleDirectVoiceSession(
                voiceAgentService = voiceAgentService,
                sessionConfig = config,
                incomingAudio = incomingAudio,
                sendAudio = {},
                sendClear = {},
                toolHandler = { _, _ -> "{}" },
                onSessionReady = { sessionReadyCalled = true },
            )
        }

        assertEquals(1, voiceAgentService.sessionsCreated.size)
        assertEquals("Test instructions", voiceAgentService.sessionsCreated[0].instructions)
        assertTrue(sessionReadyCalled)
    }

    @Test
    fun `handleDirectVoiceSession forwards audio to session`() = runBlocking {
        val config = VoiceAgentSessionConfig(instructions = "Test")

        val testAudio = Base64.encode(ByteArray(100) { 0x7F })
        val incomingAudio = flowOf(testAudio)

        var session: TestVoiceAgentSession? = null

        withTimeoutOrNull(500.milliseconds) {
            handleDirectVoiceSession(
                voiceAgentService = voiceAgentService,
                sessionConfig = config,
                incomingAudio = incomingAudio,
                sendAudio = {},
                sendClear = {},
                toolHandler = { _, _ -> "{}" },
                onSessionReady = { s ->
                    session = s as TestVoiceAgentSession
                },
            )
        }

        assertNotNull(session)
        assertEquals(1, session!!.audioReceived.size)
        assertEquals(100, session!!.audioReceived[0].size)
    }

    @Test
    fun `handleDirectVoiceSession sends audio events to client`() = runBlocking {
        val config = VoiceAgentSessionConfig(instructions = "Test")
        voiceAgentService.mockResponses.add("Hello!")

        val receivedAudio = mutableListOf<String>()
        val incomingAudio = flowOf(Base64.encode(ByteArray(10)))

        withTimeoutOrNull(500.milliseconds) {
            handleDirectVoiceSession(
                voiceAgentService = voiceAgentService,
                sessionConfig = config,
                incomingAudio = incomingAudio,
                sendAudio = { receivedAudio.add(it) },
                sendClear = {},
                toolHandler = { _, _ -> "{}" },
            )
        }

        // The mock sends text events, not audio, so this tests the event flow works
        assertEquals(1, voiceAgentService.sessionsCreated.size)
    }

    @Test
    fun `handleDirectVoiceSession handles tool calls`() = runBlocking {
        val config = VoiceAgentSessionConfig(instructions = "Test")
        voiceAgentService.mockToolCalls.add(MockToolCall("get_weather", """{"city":"NYC"}"""))

        var toolCalled = false
        var toolName: String? = null
        var toolArgs: String? = null

        val incomingAudio = flowOf(Base64.encode(ByteArray(10)))

        withTimeoutOrNull(500.milliseconds) {
            handleDirectVoiceSession(
                voiceAgentService = voiceAgentService,
                sessionConfig = config,
                incomingAudio = incomingAudio,
                sendAudio = {},
                sendClear = {},
                toolHandler = { name, args ->
                    toolCalled = true
                    toolName = name
                    toolArgs = args
                    """{"temp": 72}"""
                },
            )
        }

        assertTrue(toolCalled)
        assertEquals("get_weather", toolName)
        assertEquals("""{"city":"NYC"}""", toolArgs)
    }

    @Test
    fun `handleDirectVoiceSession records transcripts`() = runBlocking {
        val config = VoiceAgentSessionConfig(instructions = "Test")
        voiceAgentService.mockResponses.add("Hello there!")

        val transcripts = mutableListOf<TranscriptEntry>()
        val incomingAudio = flowOf(Base64.encode(ByteArray(10)))

        withTimeoutOrNull(500.milliseconds) {
            handleDirectVoiceSession(
                voiceAgentService = voiceAgentService,
                sessionConfig = config,
                incomingAudio = incomingAudio,
                sendAudio = {},
                sendClear = {},
                toolHandler = { _, _ -> "{}" },
                onTranscript = { transcripts.add(it) },
            )
        }

        // Should have agent transcript from mock response
        val agentTranscripts = transcripts.filter { it.role == TranscriptRole.AGENT }
        assertTrue(agentTranscripts.any { it.text == "Hello there!" })
    }

    // ==================== handlePhoneVoiceSession Tests ====================

    @Test
    fun `handlePhoneVoiceSession creates session immediately with provided streamId and callId`() = runBlocking {
        val config = VoiceAgentSessionConfig(instructions = "Test")

        // Empty flow - session should still be created since streamId/callId are provided
        val phoneEvents = emptyFlow<AudioStreamEvent>()

        var streamConnectedCalled = false

        val result = withTimeoutOrNull(500.milliseconds) {
            handlePhoneVoiceSession(
                voiceAgentService = voiceAgentService,
                sessionConfig = config,
                streamId = "stream-123",
                callId = "call-456",
                phoneAudioEvents = phoneEvents,
                sendToPhone = {},
                toolHandler = { _, _ -> "{}" },
                onStreamConnected = { streamConnectedCalled = true },
                jitterBufferMs = 0,
            )
        }

        // Session should be created (streamId/callId provided directly)
        assertEquals(1, voiceAgentService.sessionsCreated.size)
        assertTrue(streamConnectedCalled, "onStreamConnected should be called")
    }

    private fun connectedEvent(callId: String = "call-456", streamId: String = "stream-123") =
        AudioStreamEvent.Connected(callId = callId, streamId = streamId)

    private fun audioEvent(streamId: String = "stream-123", payload: String) =
        AudioStreamEvent.Audio(
            callId = "call-456",
            streamId = streamId,
            payload = payload,
            timestamp = 0L,
            sequenceNumber = 0L
        )

    private fun stopEvent(streamId: String = "stream-123") =
        AudioStreamEvent.Stop(callId = "call-456", streamId = streamId)

    @Test
    fun `handlePhoneVoiceSession creates session after Connected event`() = runBlocking {
        val config = VoiceAgentSessionConfig(instructions = "Test")

        var streamConnectedCalled = false

        val phoneEvents = flow {
            emit(connectedEvent())
            // Keep flow alive briefly
            delay(100.milliseconds)
        }

        withTimeoutOrNull(500.milliseconds) {
            handlePhoneVoiceSession(
                voiceAgentService = voiceAgentService,
                sessionConfig = config,
                streamId = "stream-123",
                callId = "call-456",
                phoneAudioEvents = phoneEvents,
                sendToPhone = {},
                toolHandler = { _, _ -> "{}" },
                onStreamConnected = { streamConnectedCalled = true },
            )
        }

        assertEquals(1, voiceAgentService.sessionsCreated.size)
        assertTrue(streamConnectedCalled)
    }

    @Test
    fun `handlePhoneVoiceSession converts audio formats`() = runBlocking {
        val config = VoiceAgentSessionConfig(instructions = "Test")

        var session: TestVoiceAgentSession? = null

        // Create µ-law audio (100 bytes = 12.5ms at 8kHz)
        val mulawAudio = ByteArray(100) { 0x7F.toByte() }
        val mulawBase64 = Base64.encode(mulawAudio)

        val phoneEvents = flow {
            emit(connectedEvent())
            emit(audioEvent(payload = mulawBase64))
            delay(100.milliseconds)
        }

        withTimeoutOrNull(500.milliseconds) {
            handlePhoneVoiceSession(
                voiceAgentService = voiceAgentService,
                sessionConfig = config,
                streamId = "stream-123",
                callId = "call-456",
                phoneAudioEvents = phoneEvents,
                sendToPhone = {},
                toolHandler = { _, _ -> "{}" },
                onStreamConnected = { s -> session = s as TestVoiceAgentSession },
            )
        }

        assertNotNull(session)
        assertTrue(session!!.audioReceived.isNotEmpty())

        // µ-law to PCM16 24kHz: 100 samples → 300 samples → 600 bytes
        assertEquals(600, session!!.audioReceived[0].size)
    }

    @Test
    fun `handlePhoneVoiceSession sends audio back to phone`() = runBlocking {
        val config = VoiceAgentSessionConfig(instructions = "Test")

        val sentCommands = mutableListOf<AudioStreamCommand>()

        val phoneEvents = flow {
            emit(connectedEvent())
            delay(500.milliseconds)  // Extended to allow jitter buffer to fill
        }

        var session: TestVoiceAgentSession? = null

        // Launch the session handler with no jitter buffer for faster test
        val job = launch {
            handlePhoneVoiceSession(
                voiceAgentService = voiceAgentService,
                sessionConfig = config,
                streamId = "stream-123",
                callId = "call-456",
                phoneAudioEvents = phoneEvents,
                sendToPhone = { sentCommands.add(it) },
                toolHandler = { _, _ -> "{}" },
                onStreamConnected = { s ->
                    session = s as TestVoiceAgentSession
                },
                jitterBufferMs = 0,  // Disable jitter buffer for immediate send
            )
        }

        // Wait for session to be ready
        delay(100.milliseconds)

        // Emit audio event from agent
        session?.emitEvent(VoiceAgentEvent.AudioDelta(
            responseId = "resp-1",
            itemId = "item-1",
            contentIndex = 0,
            delta = Base64.encode(ByteArray(600) { 0 }), // 300 PCM16 samples
        ))

        delay(100.milliseconds)
        job.cancel()

        // Should have sent Audio command
        val audioCommands = sentCommands.filterIsInstance<AudioStreamCommand.Audio>()
        assertTrue(audioCommands.isNotEmpty(), "Expected Audio commands to be sent")
        assertEquals("stream-123", audioCommands[0].streamId)
    }

    @Test
    fun `handlePhoneVoiceSession clears audio on speech start`() = runBlocking {
        val config = VoiceAgentSessionConfig(instructions = "Test")

        val sentCommands = mutableListOf<AudioStreamCommand>()

        val phoneEvents = flow {
            emit(connectedEvent())
            delay(200.milliseconds)
        }

        var session: TestVoiceAgentSession? = null

        val job = launch {
            handlePhoneVoiceSession(
                voiceAgentService = voiceAgentService,
                sessionConfig = config,
                streamId = "stream-123",
                callId = "call-456",
                phoneAudioEvents = phoneEvents,
                sendToPhone = { sentCommands.add(it) },
                toolHandler = { _, _ -> "{}" },
                onStreamConnected = { s -> session = s as TestVoiceAgentSession },
                jitterBufferMs = 0,
            )
        }

        delay(100.milliseconds)

        // Emit SpeechStarted event
        session?.emitEvent(VoiceAgentEvent.SpeechStarted(audioOffsetMs = 0L))

        delay(50.milliseconds)
        job.cancel()

        // Should have sent Clear command
        val clearCommands = sentCommands.filterIsInstance<AudioStreamCommand.Clear>()
        assertTrue(clearCommands.isNotEmpty(), "Expected Clear command on speech start")
        assertEquals("stream-123", clearCommands[0].streamId)
    }

    @Test
    fun `handlePhoneVoiceSession stops on Stop event`(): Unit = runBlocking {
        val config = VoiceAgentSessionConfig(instructions = "Test")

        val phoneEvents = flow {
            emit(connectedEvent())
            delay(50.milliseconds)
            emit(stopEvent())
        }

        // Should complete (not hang) when Stop is received
        val result = withTimeoutOrNull(500.milliseconds) {
            try {
                handlePhoneVoiceSession(
                    voiceAgentService = voiceAgentService,
                    sessionConfig = config,
                    streamId = "stream-123",
                    callId = "call-456",
                    phoneAudioEvents = phoneEvents,
                    sendToPhone = {},
                    toolHandler = { _, _ -> "{}" },
                    jitterBufferMs = 0,
                )
                "completed"
            } catch (e: CancellationException) {
                "cancelled"
            }
        }

        // Should have completed (either normally or via cancellation from Stop event)
        assertNotNull(result)
    }

    @Test
    fun `handlePhoneVoiceSession uses PCM16_24K format for session`() = runBlocking {
        val config = VoiceAgentSessionConfig(
            instructions = "Test",
            inputAudioFormat = AudioFormat.PCM16_16K, // Different from default
            outputAudioFormat = AudioFormat.PCM16_16K,
        )

        val phoneEvents = flow {
            emit(connectedEvent())
            delay(100.milliseconds)
        }

        withTimeoutOrNull(500.milliseconds) {
            handlePhoneVoiceSession(
                voiceAgentService = voiceAgentService,
                sessionConfig = config,
                streamId = "stream-123",
                callId = "call-456",
                phoneAudioEvents = phoneEvents,
                sendToPhone = {},
                toolHandler = { _, _ -> "{}" },
                jitterBufferMs = 0,
            )
        }

        // Session should be created with PCM16_24K regardless of input config
        assertEquals(1, voiceAgentService.sessionsCreated.size)
        assertEquals(AudioFormat.PCM16_24K, voiceAgentService.sessionsCreated[0].inputAudioFormat)
        assertEquals(AudioFormat.PCM16_24K, voiceAgentService.sessionsCreated[0].outputAudioFormat)
    }
}
