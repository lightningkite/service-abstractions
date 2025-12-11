package com.lightningkite.services.phonecall

import com.lightningkite.PhoneNumber
import com.lightningkite.services.*
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.data.WebhookSubserviceWithResponse
import com.lightningkite.services.data.WebsocketAdapter
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Service abstraction for making and receiving phone calls.
 *
 * PhoneCallService provides a unified interface for voice communication:
 * - Initiating outbound calls
 * - Receiving inbound calls via webhooks
 * - Processing call events (status changes, transcription, audio)
 * - Text-to-speech and audio playback
 *
 * ## Webhook Architecture
 *
 * Phone calls are inherently event-driven. This service uses [WebhookSubservice]
 * for all inbound events to work properly in multi-server environments:
 *
 * - [onIncomingCall] - Incoming call webhooks; returns call handling instructions
 * - [onCallStatus] - Call lifecycle events (ringing, answered, completed)
 * - [onTranscription] - Speech-to-text results
 *
 * ## Available Implementations
 *
 * - **TestPhoneCallService** (`test`) - In-memory mock for testing
 * - **ConsolePhoneCallService** (`console`) - Logs calls to console (development)
 * - **TwilioPhoneCallService** (`twilio://`) - Twilio Voice API (requires phonecall-twilio)
 *
 * ## Configuration
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val phonecall: PhoneCallService.Settings = PhoneCallService.Settings(
 *         "twilio://accountSid:authToken@+15551234567"
 *     )
 * )
 *
 * val context = SettingContext(...)
 * val phoneService: PhoneCallService = settings.phonecall("voice", context)
 * ```
 *
 * ## Outbound Call Example
 *
 * ```kotlin
 * // Configure webhooks (typically done once at startup)
 * phoneService.onCallStatus.configureWebhook("https://myserver.com/webhooks/call-status")
 *
 * // Start a call
 * val callId = phoneService.startCall(
 *     to = "+15559876543".toPhoneNumber(),
 *     options = OutboundCallOptions(transcriptionEnabled = true)
 * )
 *
 * // Send TTS to the call
 * phoneService.speak(callId, "Hello! Press 1 to confirm your appointment.")
 *
 * // Hang up
 * phoneService.hangup(callId)
 * ```
 *
 * ## Inbound Call Example
 *
 * ```kotlin
 * // In your HTTP handler
 * post("/webhooks/incoming-call") {
 *     val incomingCall = phoneService.onIncomingCall.parseWebhook(
 *         queryParameters, headers, body
 *     )
 *
 *     // Return instructions
 *     val response = if (isBusinessHours()) {
 *         CallInstructions.Accept(transcriptionEnabled = true)
 *     } else {
 *         CallInstructions.Say("We are closed. Please call back during business hours.")
 *     }
 *
 *     call.respondText(
 *         phoneService.renderInstructions(response),
 *         ContentType.Application.Xml
 *     )
 * }
 * ```
 *
 * ## Important Gotchas
 *
 * - **Webhooks required**: Incoming calls need an HTTP endpoint exposed to the provider
 * - **Phone number ownership**: From numbers must be owned/rented from the provider
 * - **Cost per minute**: Voice calls are billed per minute (typically $0.01-0.05/min)
 * - **TTS costs extra**: Text-to-speech may incur additional per-character charges
 * - **Transcription costs extra**: Speech-to-text typically costs $0.01-0.02/min
 * - **Latency**: Real-time audio has network latency; plan for ~100-300ms delays
 * - **Rate limits**: Providers limit concurrent calls (Twilio: ~100 default)
 *
 * @see CallInstructions
 * @see WebhookSubservice
 */
public interface PhoneCallService : Service {

    /**
     * Configuration for instantiating a PhoneCallService.
     *
     * The URL scheme determines the voice provider:
     * - `test` - In-memory mock for testing (default)
     * - `console` - Console logging for development
     * - `twilio://accountSid:authToken@fromPhoneNumber` - Twilio Voice
     *
     * @property url Connection string defining the voice provider and credentials
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "test"
    ) : Setting<PhoneCallService> {
        public companion object : UrlSettingParser<PhoneCallService>() {
            init {
                register("test") { name, _, context -> TestPhoneCallService(name, context) }
                register("console") { name, _, context -> ConsolePhoneCallService(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): PhoneCallService {
            return parse(name, url, context)
        }
    }

    // ==================== Outbound Call Control ====================

    /**
     * Initiates an outbound phone call.
     *
     * The call begins in [CallStatus.QUEUED], transitions to [CallStatus.RINGING] when
     * the network is reached, and [CallStatus.IN_PROGRESS] when answered.
     *
     * @param to Recipient phone number in E.164 format (e.g., +15559876543)
     * @param options Call configuration including caller ID, timeout, transcription settings
     * @return Unique call identifier for subsequent operations
     * @throws PhoneCallException if call initiation fails
     */
    public suspend fun startCall(
        to: PhoneNumber,
        options: OutboundCallOptions = OutboundCallOptions()
    ): String

    /**
     * Speaks text to an active call using text-to-speech.
     *
     * @param callId The call to send audio to
     * @param text Text to convert to speech
     * @param voice TTS voice configuration
     */
    public suspend fun speak(
        callId: String,
        text: String,
        voice: TtsVoice = TtsVoice()
    )

    /**
     * Plays audio from a URL to an active call.
     *
     * @param callId The call to send audio to
     * @param url URL of the audio file (WAV, MP3)
     * @param loop Number of times to play (1 = once, 0 = infinite)
     */
    public suspend fun playAudioUrl(
        callId: String,
        url: String,
        loop: Int = 1
    )

    /**
     * Plays audio to an active call.
     *
     * Note: Not all providers support raw audio data. Consider using [playAudioUrl] instead.
     *
     * @param callId The call to send audio to
     * @param audio Audio data with media type (WAV, MP3, etc.)
     */
    public suspend fun playAudio(
        callId: String,
        audio: TypedData
    )

    /**
     * Sends DTMF tones to an active call.
     *
     * @param callId The call to send tones to
     * @param digits String of digits (0-9, *, #)
     */
    public suspend fun sendDtmf(callId: String, digits: String)

    /**
     * Puts a call on hold.
     *
     * @param callId The call to hold
     */
    public suspend fun hold(callId: String)

    /**
     * Resumes a held call.
     *
     * @param callId The call to resume
     */
    public suspend fun resume(callId: String)

    /**
     * Updates an active call with new instructions.
     *
     * This allows you to change what happens on a call mid-flight, such as
     * playing a new message, gathering input, or transferring the call.
     *
     * @param callId The call to update
     * @param instructions New instructions for the call
     */
    public suspend fun updateCall(callId: String, instructions: CallInstructions)

    /**
     * Terminates a call.
     *
     * @param callId The call to hang up
     */
    public suspend fun hangup(callId: String)

    /**
     * Gets the current status of a call.
     *
     * @param callId The call identifier
     * @return Call info or null if not found
     */
    public suspend fun getCallStatus(callId: String): CallInfo?

    // ==================== Webhooks ====================

    /**
     * Webhook for incoming call notifications.
     *
     * When a call arrives at your phone number, the provider sends a webhook.
     * Your handler should respond with instructions rendered via [renderInstructions].
     *
     * The parsed result is [IncomingCallEvent].
     */
    public val onIncomingCall: WebhookSubserviceWithResponse<IncomingCallEvent, CallInstructions?>

    /**
     * Webhook for call status changes.
     *
     * Receives updates when calls transition states (ringing, answered, completed).
     * Configure the callback URL via [WebhookSubservice.configureWebhook].
     */
    public val onCallStatus: WebhookSubservice<CallStatusEvent>

    /**
     * Webhook for speech-to-text transcription results.
     *
     * Receives transcription when speech is detected on a call with
     * transcription enabled.
     */
    public val onTranscription: WebhookSubservice<TranscriptionEvent>

    /**
     * Webhook for DTMF digit input and speech gather results.
     *
     * Receives events when a [CallInstructions.Gather] completes.
     * The response can provide new instructions for handling the call.
     *
     * Note: This is optional. If not provided, use [CallInstructions.Gather.actionUrl]
     * to handle gather results via a custom endpoint.
     */
    public val onDtmf: WebhookSubserviceWithResponse<DtmfEvent, CallInstructions?>?
        get() = null

    // ==================== Audio Streaming ====================

    /**
     * WebSocket adapter for bidirectional audio streaming.
     *
     * When audio streaming is enabled on a call (via [CallInstructions.StreamAudio]),
     * the provider connects to your WebSocket endpoint. Use this adapter to:
     * - Validate the initial connection via [WebsocketAdapter.parseStart]
     * - Parse incoming audio frames from the provider's format
     * - Render outgoing audio frames to the provider's format
     *
     * ## Audio Format
     *
     * The audio format is typically:
     * - Encoding: μ-law (PCMU)
     * - Sample rate: 8000 Hz
     * - Channels: Mono
     * - Payload: Base64-encoded
     *
     * ## Usage Example
     *
     * ```kotlin
     * // In your WebSocket handler
     * val startInfo = phoneService.audioStream?.parseStart(queryParams, headers, body)
     *     ?: throw UnsupportedOperationException("Audio streaming not supported")
     *
     * // For each incoming WebSocket frame
     * val event = phoneService.audioStream!!.parse(frame)
     * when (event) {
     *     is AudioStreamEvent.Audio -> processAudio(event.payload)
     *     is AudioStreamEvent.Stop -> closeConnection()
     *     // ...
     * }
     *
     * // To send audio back
     * val frame = phoneService.audioStream!!.render(
     *     AudioStreamCommand.Audio(streamId, base64Audio)
     * )
     * websocket.send(frame)
     * ```
     *
     * @return WebSocket adapter, or null if the provider doesn't support audio streaming
     */
    public val audioStream: WebsocketAdapter<AudioStreamStart, AudioStreamEvent, AudioStreamCommand>?
        get() = null

    // ==================== Response Rendering ====================

    override val healthCheckFrequency: Duration get() = 5.minutes

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Phone call service ready")
    }
}
