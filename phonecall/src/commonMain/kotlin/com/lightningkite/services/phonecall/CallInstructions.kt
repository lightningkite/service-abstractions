package com.lightningkite.services.phonecall

import com.lightningkite.PhoneNumber
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Instructions for handling a phone call.
 *
 * These instructions are returned from incoming call webhooks and tell the
 * provider how to handle the call. Use [PhoneCallService.renderInstructions]
 * to convert to provider-specific format (e.g., TwiML for Twilio).
 *
 * ## Composable Instructions
 *
 * Many instructions support a [then] parameter for chaining:
 *
 * ```kotlin
 * CallInstructions.Say(
 *     text = "Welcome to Example Corp.",
 *     then = CallInstructions.Gather(
 *         prompt = "Press 1 for sales, 2 for support.",
 *         numDigits = 1,
 *         actionUrl = "/webhooks/gather-result"
 *     )
 * )
 * ```
 *
 * ## Example Usage
 *
 * ```kotlin
 * // Simple greeting and hangup
 * val instructions = CallInstructions.Say(
 *     text = "Thank you for calling. Goodbye!",
 *     then = CallInstructions.Hangup
 * )
 *
 * // Accept and enable transcription
 * val instructions = CallInstructions.Accept(
 *     transcriptionEnabled = true,
 *     initialMessage = "Hello! How can I help you today?"
 * )
 *
 * // Interactive menu
 * val instructions = CallInstructions.Gather(
 *     prompt = "Press 1 for English, 2 for Spanish.",
 *     numDigits = 1,
 *     actionUrl = "https://myserver.com/webhooks/language-selection"
 * )
 * ```
 */
@Serializable
public sealed class CallInstructions {

    /**
     * Accept the call and establish a connection.
     *
     * Use this when you want to handle the call programmatically,
     * potentially with transcription.
     *
     * @property transcriptionEnabled Enable speech-to-text on caller's audio
     * @property initialMessage TTS message to speak when call connects
     * @property voice Voice configuration for TTS
     */
    @Serializable
    public data class Accept(
        val transcriptionEnabled: Boolean = false,
        val initialMessage: String? = null,
        val voice: TtsVoice = TtsVoice()
    ) : CallInstructions()

    /**
     * Reject the call.
     *
     * The caller hears a busy signal or rejection tone.
     */
    @Serializable
    public data object Reject : CallInstructions()

    /**
     * Speak text using text-to-speech.
     *
     * @property text Text to speak to the caller
     * @property voice Voice configuration (language, gender, specific voice)
     * @property loop Number of times to repeat (1 = play once)
     * @property then Instructions to execute after speaking
     */
    @Serializable
    public data class Say(
        val text: String,
        val voice: TtsVoice = TtsVoice(),
        val loop: Int = 1,
        val then: CallInstructions? = null
    ) : CallInstructions()

    /**
     * Play an audio file.
     *
     * @property url URL of the audio file to play (WAV, MP3)
     * @property loop Number of times to repeat (1 = play once)
     * @property then Instructions to execute after playing
     */
    @Serializable
    public data class Play(
        val url: String,
        val loop: Int = 1,
        val then: CallInstructions? = null
    ) : CallInstructions()

    /**
     * Gather DTMF digits or speech input from the caller.
     *
     * The result is sent to [actionUrl] as a webhook.
     *
     * @property prompt Optional TTS prompt before gathering
     * @property numDigits Number of digits to collect (null = wait for timeout or #)
     * @property timeout How long to wait for input
     * @property speechEnabled Enable speech recognition in addition to DTMF
     * @property actionUrl URL to receive the gathered input
     * @property finishOnKey Key that ends gathering (default: #)
     * @property then Instructions if gathering times out
     */
    @Serializable
    public data class Gather(
        val prompt: String? = null,
        val numDigits: Int? = null,
        val timeout: Duration = 5.seconds,
        val speechEnabled: Boolean = false,
        val actionUrl: String,
        val finishOnKey: String = "#",
        val then: CallInstructions? = null
    ) : CallInstructions()

    /**
     * Forward/dial the call to another phone number.
     *
     * @property to Phone number to forward to
     * @property timeout How long to wait for answer
     * @property callerId Caller ID to show (null = use original caller)
     * @property then Instructions if forward fails or times out
     */
    @Serializable
    public data class Forward(
        val to: PhoneNumber,
        val timeout: Duration = 30.seconds,
        val callerId: PhoneNumber? = null,
        val then: CallInstructions? = null
    ) : CallInstructions()

    /**
     * Join or create a conference call (multi-party).
     *
     * This enables multiple participants to be connected in a single call.
     * Useful for call transfers where an agent can introduce parties before leaving.
     *
     * @property name Unique name for the conference room
     * @property startOnEnter Whether to start the conference when this participant joins
     * @property endOnExit Whether to end the conference when this participant leaves
     * @property muted Whether this participant joins muted
     * @property beep Whether to play a beep when participants join/leave
     * @property waitUrl URL for hold music while waiting for other participants
     * @property statusCallbackUrl URL for conference status webhooks
     * @property statusCallbackEvents Events to send to callback (e.g., "join", "leave")
     * @property then Instructions to execute after leaving the conference
     */
    @Serializable
    public data class Conference(
        val name: String,
        val startOnEnter: Boolean = true,
        val endOnExit: Boolean = false,
        val muted: Boolean = false,
        val beep: Boolean = true,
        val waitUrl: String? = null,
        val statusCallbackUrl: String? = null,
        val statusCallbackEvents: List<String> = listOf("join", "leave"),
        val then: CallInstructions? = null
    ) : CallInstructions()

    /**
     * End the call.
     */
    @Serializable
    public data object Hangup : CallInstructions()

    /**
     * Pause for a duration of silence.
     *
     * @property duration How long to pause
     * @property then Instructions to execute after pause
     */
    @Serializable
    public data class Pause(
        val duration: Duration = 1.seconds,
        val then: CallInstructions? = null
    ) : CallInstructions()

    /**
     * Record audio from the caller.
     *
     * The recording is sent to [actionUrl] when complete.
     *
     * @property maxDuration Maximum recording duration
     * @property actionUrl URL to receive the recording
     * @property transcribe Whether to also transcribe the recording
     * @property playBeep Play a beep before recording starts
     * @property finishOnKey Key that stops recording (default: #)
     * @property then Instructions if recording times out or is stopped
     */
    @Serializable
    public data class Record(
        val maxDuration: Duration = 60.seconds,
        val actionUrl: String,
        val transcribe: Boolean = false,
        val playBeep: Boolean = true,
        val finishOnKey: String = "#",
        val then: CallInstructions? = null
    ) : CallInstructions()

    /**
     * Redirect to another webhook URL for instructions.
     *
     * @property url URL to fetch next instructions from
     */
    @Serializable
    public data class Redirect(
        val url: String
    ) : CallInstructions()

    /**
     * Queue the caller (typically for call centers).
     *
     * @property name Queue name
     * @property waitUrl URL for hold music/messages
     * @property then Instructions if queue fails
     */
    @Serializable
    public data class Enqueue(
        val name: String,
        val waitUrl: String? = null,
        val then: CallInstructions? = null
    ) : CallInstructions()

    @Serializable
    public data class ImplementationSpecific(val raw: String): CallInstructions()

    /**
     * Start bidirectional audio streaming to a WebSocket endpoint.
     *
     * This enables real-time audio processing, useful for:
     * - AI voice assistants (e.g., with OpenAI Realtime API)
     * - Real-time transcription and analysis
     * - Custom audio processing pipelines
     *
     * When this instruction is executed, the provider connects to your
     * WebSocket endpoint and begins streaming audio. Use [PhoneCallService.audioStream]
     * to parse and render the WebSocket messages.
     *
     * ## Audio Format
     *
     * The audio format is typically:
     * - Encoding: μ-law (PCMU)
     * - Sample rate: 8000 Hz
     * - Channels: Mono
     * - Payload: Base64-encoded in JSON messages
     *
     * ## Example
     *
     * ```kotlin
     * // In your incoming call handler
     * CallInstructions.Say(
     *     text = "Connecting you to our AI assistant.",
     *     then = CallInstructions.StreamAudio(
     *         websocketUrl = "wss://myserver.com/voice-ai",
     *         track = AudioTrack.BOTH
     *     )
     * )
     * ```
     *
     * @property websocketUrl WebSocket URL for the audio stream (wss://)
     * @property track Which audio track(s) to stream
     * @property customParameters Additional parameters passed to the WebSocket connection
     * @property then Instructions to execute after streaming ends
     */
    @Serializable
    public data class StreamAudio(
        val websocketUrl: String,
        val track: AudioTrack = AudioTrack.INBOUND,
        val customParameters: Map<String, String> = emptyMap(),
        val then: CallInstructions? = null
    ) : CallInstructions() {
        init {
            require(websocketUrl.startsWith("wss://")) { "WebSocket URL (${websocketUrl}) must start with wss://" }
            require(!websocketUrl.contains("?")) { "WebSocket URL (${websocketUrl}) has query parameters, which is not allowed. Use customParameters instead." }
        }
    }
}
