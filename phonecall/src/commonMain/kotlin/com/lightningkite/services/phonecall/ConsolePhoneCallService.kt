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
 * Console implementation of PhoneCallService for development and debugging.
 *
 * Logs all operations to the console instead of making real calls.
 * Useful for local development and debugging call flows.
 */
public class ConsolePhoneCallService(
    override val name: String,
    override val context: SettingContext
) : PhoneCallService {

    private var callCounter = 0
    private val activeCalls = mutableMapOf<String, CallInfo>()

    override suspend fun startCall(to: PhoneNumber, options: OutboundCallOptions): String {
        val callId = "console-call-${++callCounter}"
        val from = options.from ?: "+10000000000".toPhoneNumber()

        println("[$name] Starting call $callId")
        println("  From: $from")
        println("  To: $to")
        println("  Options: $options")

        activeCalls[callId] = CallInfo(
            callId = callId,
            status = CallStatus.IN_PROGRESS,
            direction = CallDirection.OUTBOUND,
            from = from,
            to = to,
            startTime = Clock.System.now()
        )

        return callId
    }

    override suspend fun speak(callId: String, text: String, voice: TtsVoice) {
        println("[$name] Speaking on call $callId:")
        println("  Text: $text")
        println("  Voice: $voice")
    }

    override suspend fun playAudioUrl(callId: String, url: String, loop: Int) {
        println("[$name] Playing audio URL on call $callId:")
        println("  URL: $url")
        println("  Loop: $loop")
    }

    override suspend fun playAudio(callId: String, audio: TypedData) {
        println("[$name] Playing audio on call $callId:")
        println("  Media type: ${audio.mediaType}")
        println("  Size: ${audio.data.size} bytes")
    }

    override suspend fun sendDtmf(callId: String, digits: String) {
        println("[$name] Sending DTMF on call $callId: $digits")
    }

    override suspend fun hold(callId: String) {
        println("[$name] Holding call $callId")
        activeCalls[callId]?.let {
            activeCalls[callId] = it.copy(status = CallStatus.ON_HOLD)
        }
    }

    override suspend fun resume(callId: String) {
        println("[$name] Resuming call $callId")
        activeCalls[callId]?.let {
            activeCalls[callId] = it.copy(status = CallStatus.IN_PROGRESS)
        }
    }

    override suspend fun updateCall(callId: String, instructions: CallInstructions) {
        println("[$name] Updating call $callId with instructions:")
        println(renderInstructions(instructions))
    }

    /**
     * Updates call with raw instructions string.
     */
    public fun updateCallRaw(callId: String, instructions: String) {
        println("[$name] Updating call $callId with raw instructions:")
        println(instructions)
    }

    override suspend fun hangup(callId: String) {
        println("[$name] Hanging up call $callId")
        activeCalls[callId]?.let {
            activeCalls[callId] = it.copy(status = CallStatus.COMPLETED)
        }
    }

    override suspend fun getCallStatus(callId: String): CallInfo? {
        return activeCalls[callId]
    }

    override val onIncomingCall: WebhookSubserviceWithResponse<IncomingCallEvent, CallInstructions?> = object : WebhookSubserviceWithResponse<IncomingCallEvent, CallInstructions?> {
        override suspend fun configureWebhook(httpUrl: String) {
            println("[$name] Configured incoming call webhook: $httpUrl")
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): IncomingCallEvent {
            println("[$name] Parsing incoming call webhook")
            return IncomingCallEvent(
                callId = "console-incoming-${++callCounter}",
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
            println("[$name] Incoming call webhook scheduled check")
        }
    }

    override val onCallStatus: WebhookSubservice<CallStatusEvent> = object : WebhookSubservice<CallStatusEvent> {
        override suspend fun configureWebhook(httpUrl: String) {
            println("[$name] Configured call status webhook: $httpUrl")
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): CallStatusEvent {
            println("[$name] Parsing call status webhook")
            return CallStatusEvent(
                callId = "console-call-0",
                status = CallStatus.COMPLETED,
                direction = CallDirection.OUTBOUND,
                from = "+10000000000".toPhoneNumber(),
                to = "+10000000001".toPhoneNumber()
            )
        }

        override suspend fun onSchedule() {
            println("[$name] Call status webhook scheduled check")
        }
    }

    override val onTranscription: WebhookSubservice<TranscriptionEvent> = object : WebhookSubservice<TranscriptionEvent> {
        override suspend fun configureWebhook(httpUrl: String) {
            println("[$name] Configured transcription webhook: $httpUrl")
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): TranscriptionEvent {
            println("[$name] Parsing transcription webhook")
            return TranscriptionEvent(
                callId = "console-call-0",
                text = "Sample transcription",
                isFinal = true
            )
        }

        override suspend fun onSchedule() {
            println("[$name] Transcription webhook scheduled check")
        }
    }

    /**
     * Renders [CallInstructions] to XML string.
     */
    public fun renderInstructions(instructions: CallInstructions): String {
        return renderInstructionsWithType(instructions).content
    }

    /**
     * Renders [CallInstructions] to [RenderedInstructions] with content type.
     */
    public fun renderInstructionsWithType(instructions: CallInstructions): RenderedInstructions {
        val content = buildString {
            appendLine("<!-- Console Phone Call Service - Instructions -->")
            appendLine("<Response>")
            appendInstructions(instructions, indent = "  ")
            appendLine("</Response>")
        }
        println("[$name] Rendered instructions:")
        println(content)
        return RenderedInstructions(content, InstructionsContentType.XML)
    }

    private fun StringBuilder.appendInstructions(instructions: CallInstructions, indent: String) {
        when (instructions) {
            is CallInstructions.Accept -> {
                appendLine("$indent<Accept transcription=\"${instructions.transcriptionEnabled}\"/>")
                instructions.initialMessage?.let {
                    appendLine("$indent<Say>${it}</Say>")
                }
            }
            is CallInstructions.Reject -> appendLine("$indent<Reject/>")
            is CallInstructions.Say -> {
                appendLine("$indent<Say voice=\"${instructions.voice.language}\" loop=\"${instructions.loop}\">${instructions.text}</Say>")
                instructions.then?.let { appendInstructions(it, indent) }
            }
            is CallInstructions.Play -> {
                appendLine("$indent<Play loop=\"${instructions.loop}\">${instructions.url}</Play>")
                instructions.then?.let { appendInstructions(it, indent) }
            }
            is CallInstructions.Gather -> {
                appendLine("$indent<Gather numDigits=\"${instructions.numDigits}\" timeout=\"${instructions.timeout.inWholeSeconds}\" action=\"${instructions.actionUrl}\">")
                instructions.prompt?.let { appendLine("$indent  <Say>$it</Say>") }
                appendLine("$indent</Gather>")
                instructions.then?.let { appendInstructions(it, indent) }
            }
            is CallInstructions.Forward -> {
                appendLine("$indent<Dial timeout=\"${instructions.timeout.inWholeSeconds}\">${instructions.to}</Dial>")
                instructions.then?.let { appendInstructions(it, indent) }
            }
            is CallInstructions.Conference -> {
                appendLine("$indent<Conference name=\"${instructions.name}\" startOnEnter=\"${instructions.startOnEnter}\" endOnExit=\"${instructions.endOnExit}\"/>")
                instructions.then?.let { appendInstructions(it, indent) }
            }
            is CallInstructions.Hangup -> appendLine("$indent<Hangup/>")
            is CallInstructions.Pause -> {
                appendLine("$indent<Pause length=\"${instructions.duration.inWholeSeconds}\"/>")
                instructions.then?.let { appendInstructions(it, indent) }
            }
            is CallInstructions.Record -> {
                appendLine("$indent<Record maxLength=\"${instructions.maxDuration.inWholeSeconds}\" action=\"${instructions.actionUrl}\" transcribe=\"${instructions.transcribe}\"/>")
                instructions.then?.let { appendInstructions(it, indent) }
            }
            is CallInstructions.StreamAudio -> {
                appendLine("$indent<StreamAudio url=\"${instructions.websocketUrl}\" track=\"${instructions.track}\"/>")
                instructions.then?.let { appendInstructions(it, indent) }
            }
            is CallInstructions.Redirect -> appendLine("$indent<Redirect>${instructions.url}</Redirect>")
            is CallInstructions.Enqueue -> {
                appendLine("$indent<Enqueue waitUrl=\"${instructions.waitUrl}\">${instructions.name}</Enqueue>")
                instructions.then?.let { appendInstructions(it, indent) }
            }
            is CallInstructions.ImplementationSpecific -> {
                appendLine(instructions.raw)
            }
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(
            HealthStatus.Level.OK,
            additionalMessage = "Console Phone Call Service - No real calls are made."
        )
    }
}
