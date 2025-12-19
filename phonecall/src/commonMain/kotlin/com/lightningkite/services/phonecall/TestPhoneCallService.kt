package com.lightningkite.services.phonecall

import com.lightningkite.MediaType
import com.lightningkite.PhoneNumber
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HttpAdapter
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.data.WebhookSubserviceWithResponse
import com.lightningkite.toPhoneNumber
import kotlin.time.Clock

/**
 * Test implementation of PhoneCallService for unit testing.
 *
 * Records all operations for verification in tests:
 *
 * ```kotlin
 * val testService = TestPhoneCallService("test", context)
 *
 * // Make a call
 * val callId = testService.startCall("+15551234567".toPhoneNumber())
 * testService.speak(callId, "Hello!")
 * testService.hangup(callId)
 *
 * // Assert
 * assertEquals(1, testService.calls.size)
 * assertEquals(CallStatus.COMPLETED, testService.calls[callId]?.status)
 * assertTrue(testService.spokenMessages.any { it.text == "Hello!" })
 * ```
 */
public class TestPhoneCallService(
    override val name: String,
    override val context: SettingContext
) : PhoneCallService {

    private var callCounter = 0

    /** All calls that have been made, keyed by call ID */
    public val calls: MutableMap<String, TestCallInfo> = mutableMapOf()

    /** All TTS messages spoken */
    public val spokenMessages: MutableList<SpokenMessage> = mutableListOf()

    /** All audio played from URLs */
    public val playedAudioUrls: MutableList<PlayedAudioUrl> = mutableListOf()

    /** All raw audio played */
    public val playedAudio: MutableList<PlayedAudio> = mutableListOf()

    /** All DTMF tones sent */
    public val sentDtmf: MutableList<SentDtmf> = mutableListOf()

    /** Rendered instructions history */
    public val renderedInstructions: MutableList<CallInstructions> = mutableListOf()

    /** Updated call instructions history */
    public val updatedInstructions: MutableList<UpdatedInstructions> = mutableListOf()

    /** Updated raw instructions history */
    public val updatedRawInstructions: MutableList<UpdatedRawInstructions> = mutableListOf()

    /** Configured webhook URLs */
    public val configuredWebhooks: MutableMap<String, String> = mutableMapOf()

    /** Whether to also print to console */
    public var printToConsole: Boolean = false

    /** Callback when a call is started */
    public var onCallStarted: ((TestCallInfo) -> Unit)? = null

    /** Callback when a call ends */
    public var onCallEnded: ((TestCallInfo) -> Unit)? = null

    /**
     * Clears all recorded data.
     */
    public fun reset() {
        callCounter = 0
        calls.clear()
        spokenMessages.clear()
        playedAudioUrls.clear()
        playedAudio.clear()
        sentDtmf.clear()
        renderedInstructions.clear()
        updatedInstructions.clear()
        updatedRawInstructions.clear()
        configuredWebhooks.clear()
    }

    override suspend fun startCall(to: PhoneNumber, options: OutboundCallOptions): String {
        val callId = "test-call-${++callCounter}"
        val from = options.from ?: "+10000000000".toPhoneNumber()

        val callInfo = TestCallInfo(
            callId = callId,
            status = CallStatus.IN_PROGRESS,
            direction = CallDirection.OUTBOUND,
            from = from,
            to = to,
            options = options,
            startTime = Clock.System.now()
        )
        calls[callId] = callInfo
        onCallStarted?.invoke(callInfo)

        if (printToConsole) {
            println("[$name] Started call $callId: $from -> $to")
        }

        return callId
    }

    override suspend fun speak(callId: String, text: String, voice: TtsVoice) {
        val message = SpokenMessage(callId, text, voice)
        spokenMessages.add(message)

        if (printToConsole) {
            println("[$name] Speak on $callId: $text")
        }
    }

    override suspend fun playAudioUrl(callId: String, url: String, loop: Int) {
        val played = PlayedAudioUrl(callId, url, loop)
        playedAudioUrls.add(played)

        if (printToConsole) {
            println("[$name] Play audio URL on $callId: $url (loop=$loop)")
        }
    }

    override suspend fun playAudio(callId: String, audio: TypedData) {
        val played = PlayedAudio(callId, audio)
        playedAudio.add(played)

        if (printToConsole) {
            println("[$name] Play audio on $callId: ${audio.mediaType}")
        }
    }

    override suspend fun sendDtmf(callId: String, digits: String) {
        val dtmf = SentDtmf(callId, digits)
        sentDtmf.add(dtmf)

        if (printToConsole) {
            println("[$name] DTMF on $callId: $digits")
        }
    }

    override suspend fun hold(callId: String) {
        calls[callId]?.let {
            calls[callId] = it.copy(status = CallStatus.ON_HOLD)
        }

        if (printToConsole) {
            println("[$name] Hold $callId")
        }
    }

    override suspend fun resume(callId: String) {
        calls[callId]?.let {
            calls[callId] = it.copy(status = CallStatus.IN_PROGRESS)
        }

        if (printToConsole) {
            println("[$name] Resume $callId")
        }
    }

    override suspend fun updateCall(callId: String, instructions: CallInstructions) {
        updatedInstructions.add(UpdatedInstructions(callId, instructions))

        if (printToConsole) {
            println("[$name] Update call $callId with: $instructions")
        }
    }

    /**
     * Updates call with raw instructions string (test implementation just records it).
     */
    public fun updateCallRaw(callId: String, instructions: String) {
        updatedRawInstructions.add(UpdatedRawInstructions(callId, instructions))

        if (printToConsole) {
            println("[$name] Update call $callId with raw instructions")
        }
    }

    override suspend fun hangup(callId: String) {
        calls[callId]?.let { call ->
            val updated = call.copy(status = CallStatus.COMPLETED)
            calls[callId] = updated
            onCallEnded?.invoke(updated)
        }

        if (printToConsole) {
            println("[$name] Hangup $callId")
        }
    }

    override suspend fun getCallStatus(callId: String): CallInfo? {
        return calls[callId]?.toCallInfo()
    }

    // ==================== Webhook Subservices ====================

    override val onIncomingCall: WebhookSubserviceWithResponse<IncomingCallEvent, CallInstructions?> = TestIncomingCallWebhook()

    private inner class TestIncomingCallWebhook : WebhookSubserviceWithResponse<IncomingCallEvent, CallInstructions?> {
        override suspend fun configureWebhook(httpUrl: String) {
            configuredWebhooks["incoming-call"] = httpUrl
            if (printToConsole) {
                println("[$name] Configured incoming-call webhook: $httpUrl")
            }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): IncomingCallEvent {
            return IncomingCallEvent(
                callId = "test-incoming-${++callCounter}",
                from = "+10000000001".toPhoneNumber(),
                to = "+10000000000".toPhoneNumber()
            )
        }

        override suspend fun render(output: CallInstructions?): HttpAdapter.HttpResponseLike {
            if (output == null) {
                return HttpAdapter.HttpResponseLike(204, mapOf(), null)
            }
            val rendered = renderInstructions(output)
            return HttpAdapter.HttpResponseLike(
                status = 200,
                headers = mapOf("Content-Type" to listOf("application/xml")),
                body = TypedData.text(rendered, MediaType.Application.Xml)
            )
        }

        override suspend fun onSchedule() {
            if (printToConsole) {
                println("[$name] incoming-call scheduled check")
            }
        }
    }

    override val onCallStatus: WebhookSubservice<CallStatusEvent> = TestWebhookSubservice(
        name = "call-status",
        defaultEvent = {
            CallStatusEvent(
                callId = "test-call-0",
                status = CallStatus.COMPLETED,
                direction = CallDirection.OUTBOUND,
                from = "+10000000000".toPhoneNumber(),
                to = "+10000000001".toPhoneNumber()
            )
        }
    )

    override val onTranscription: WebhookSubservice<TranscriptionEvent> = TestWebhookSubservice(
        name = "transcription",
        defaultEvent = {
            TranscriptionEvent(
                callId = "test-call-0",
                text = "Test transcription",
                isFinal = true
            )
        }
    )

    private inner class TestWebhookSubservice<T>(
        private val name: String,
        private val defaultEvent: (TypedData) -> T
    ) : WebhookSubservice<T> {
        var parseHandler: ((List<Pair<String, String>>, Map<String, List<String>>, TypedData) -> T)? = null

        override suspend fun configureWebhook(httpUrl: String) {
            configuredWebhooks[name] = httpUrl
            if (printToConsole) {
                println("[${this@TestPhoneCallService.name}] Configured $name webhook: $httpUrl")
            }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): T {
            return parseHandler?.invoke(queryParameters, headers, body) ?: defaultEvent(body)
        }

        override suspend fun onSchedule() {
            if (printToConsole) {
                println("[${this@TestPhoneCallService.name}] $name scheduled check")
            }
        }
    }

    /**
     * Renders [CallInstructions] to a simple XML-like string for testing.
     */
    public fun renderInstructions(instructions: CallInstructions): String {
        return renderInstructionsWithType(instructions).content
    }

    /**
     * Renders [CallInstructions] to [RenderedInstructions] with content type.
     */
    public fun renderInstructionsWithType(instructions: CallInstructions): RenderedInstructions {
        renderedInstructions.add(instructions)

        if (printToConsole) {
            println("[$name] Render instructions: $instructions")
        }

        // Return a simple XML-like representation for testing
        val content = buildString {
            appendLine("<Response>")
            appendInstruction(instructions, "  ")
            appendLine("</Response>")
        }
        return RenderedInstructions(content, InstructionsContentType.XML)
    }

    private fun StringBuilder.appendInstruction(inst: CallInstructions, indent: String) {
        when (inst) {
            is CallInstructions.Accept -> appendLine("$indent<Accept/>")
            is CallInstructions.Reject -> appendLine("$indent<Reject/>")
            is CallInstructions.Hangup -> appendLine("$indent<Hangup/>")
            is CallInstructions.Say -> {
                appendLine("$indent<Say>${inst.text}</Say>")
                inst.then?.let { appendInstruction(it, indent) }
            }
            is CallInstructions.Play -> {
                appendLine("$indent<Play>${inst.url}</Play>")
                inst.then?.let { appendInstruction(it, indent) }
            }
            is CallInstructions.Pause -> {
                appendLine("$indent<Pause length=\"${inst.duration.inWholeSeconds}\"/>")
                inst.then?.let { appendInstruction(it, indent) }
            }
            is CallInstructions.Gather -> {
                appendLine("$indent<Gather action=\"${inst.actionUrl}\"/>")
                inst.then?.let { appendInstruction(it, indent) }
            }
            is CallInstructions.Forward -> {
                appendLine("$indent<Dial>${inst.to}</Dial>")
                inst.then?.let { appendInstruction(it, indent) }
            }
            is CallInstructions.Conference -> {
                appendLine("$indent<Conference name=\"${inst.name}\"/>")
                inst.then?.let { appendInstruction(it, indent) }
            }
            is CallInstructions.Record -> {
                appendLine("$indent<Record action=\"${inst.actionUrl}\"/>")
                inst.then?.let { appendInstruction(it, indent) }
            }
            is CallInstructions.Redirect -> appendLine("$indent<Redirect>${inst.url}</Redirect>")
            is CallInstructions.Enqueue -> {
                appendLine("$indent<Enqueue>${inst.name}</Enqueue>")
                inst.then?.let { appendInstruction(it, indent) }
            }
            is CallInstructions.ImplementationSpecific -> {
                appendLine(inst.raw)
            }
            is CallInstructions.StreamAudio -> {
                appendLine("$indent<StreamAudio url=\"${inst.websocketUrl}\" track=\"${inst.track}\"/>")
                inst.then?.let { appendInstruction(it, indent) }
            }
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(
            HealthStatus.Level.OK,
            additionalMessage = "Test Phone Call Service - No real calls are made."
        )
    }

    // ==================== Test Data Classes ====================

    public data class TestCallInfo(
        val callId: String,
        val status: CallStatus,
        val direction: CallDirection,
        val from: PhoneNumber,
        val to: PhoneNumber,
        val options: OutboundCallOptions,
        val startTime: kotlin.time.Instant
    ) {
        public fun toCallInfo(): CallInfo = CallInfo(
            callId = callId,
            status = status,
            direction = direction,
            from = from,
            to = to,
            startTime = startTime
        )
    }

    public data class SpokenMessage(
        val callId: String,
        val text: String,
        val voice: TtsVoice
    )

    public data class PlayedAudioUrl(
        val callId: String,
        val url: String,
        val loop: Int
    )

    public data class PlayedAudio(
        val callId: String,
        val audio: TypedData
    )

    public data class SentDtmf(
        val callId: String,
        val digits: String
    )

    public data class UpdatedInstructions(
        val callId: String,
        val instructions: CallInstructions
    )

    public data class UpdatedRawInstructions(
        val callId: String,
        val instructions: String
    )
}
