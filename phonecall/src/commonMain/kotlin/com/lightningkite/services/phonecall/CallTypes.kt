package com.lightningkite.services.phonecall

import com.lightningkite.PhoneNumber
import com.lightningkite.services.data.TypedData
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// ==================== Outbound Call Options ====================

/**
 * Configuration options for initiating an outbound phone call.
 *
 * @property from Caller ID shown to recipient (must be a number owned/verified by the provider)
 * @property callerName Caller name for CNAM display (not supported by all providers/carriers)
 * @property timeout Maximum time to wait for the call to be answered
 * @property transcriptionEnabled Enable real-time speech-to-text on incoming audio
 * @property recordingEnabled Enable call recording (may have legal requirements in some jurisdictions)
 * @property machineDetection Enable answering machine detection to wait for human answer
 * @property postAnswerDelay Additional delay after call is answered before returning from startCall.
 *   Useful for Twilio trial accounts which play a ~12 second verification message after answer.
 *   Set to 12.seconds for trial account testing, or Duration.ZERO (default) for production.
 * @property sendDigitsOnConnect DTMF digits to send when call connects (for navigating IVR/extensions)
 * @property initialMessage Initial TTS message when call connects
 * @property metadata Custom key-value pairs attached to the call for tracking
 */
@Serializable
public data class OutboundCallOptions(
    val from: PhoneNumber? = null,
    val callerName: String? = null,
    val timeout: Duration = 30.seconds,
    val transcriptionEnabled: Boolean = false,
    val recordingEnabled: Boolean = false,
    val machineDetection: MachineDetectionMode = MachineDetectionMode.DISABLED,
    val postAnswerDelay: Duration = Duration.ZERO,
    val sendDigitsOnConnect: String? = null,
    val initialMessage: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Mode for answering machine detection on outbound calls.
 *
 * When enabled, the provider will attempt to detect whether a human or machine answered,
 * and return this information. This is useful for:
 * - Twilio trial accounts (waits until human presses key to confirm)
 * - Avoiding leaving messages on voicemail when you want a human
 * - Detecting fax machines
 */
@Serializable
public enum class MachineDetectionMode {
    /** No machine detection - call returns as soon as answered */
    DISABLED,
    /** Detect immediately whether human or machine answered */
    ENABLED,
    /** Wait for end of voicemail greeting before returning (for leaving messages) */
    DETECT_MESSAGE_END
}

// ==================== Webhook Events ====================

/**
 * Event received when an incoming call arrives.
 *
 * @property callId Unique identifier for this call
 * @property from Caller's phone number
 * @property to Your phone number that was called
 * @property direction Call direction (always INBOUND for incoming calls)
 * @property metadata Provider-specific metadata (e.g., geographic info, carrier)
 */
@Serializable
public data class IncomingCallEvent(
    val callId: String,
    val from: PhoneNumber,
    val to: PhoneNumber,
    val direction: CallDirection = CallDirection.INBOUND,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Event received when a call changes state.
 *
 * @property callId Unique identifier for the call
 * @property status Current call status
 * @property direction Whether call was inbound or outbound
 * @property from Caller phone number
 * @property to Recipient phone number
 * @property duration Call duration (available when call ends)
 * @property endReason Why the call ended (available when status is terminal)
 * @property metadata Custom metadata attached to the call
 */
@Serializable
public data class CallStatusEvent(
    val callId: String,
    val status: CallStatus,
    val direction: CallDirection,
    val from: PhoneNumber,
    val to: PhoneNumber,
    val duration: Duration? = null,
    val endReason: CallEndReason? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Event received with speech-to-text transcription results.
 *
 * @property callId Unique identifier for the call
 * @property text Transcribed text
 * @property isFinal Whether this is a final result or interim/partial
 * @property confidence Recognition confidence (0.0 to 1.0) if available
 * @property language Detected language code if available
 */
@Serializable
public data class TranscriptionEvent(
    val callId: String,
    val text: String,
    val isFinal: Boolean,
    val confidence: Float? = null,
    val language: String? = null
)

// ==================== Call Status Types ====================

/**
 * Direction of a phone call.
 */
@Serializable
public enum class CallDirection {
    /** Call received from external party */
    INBOUND,
    /** Call initiated to external party */
    OUTBOUND
}

/**
 * Current status of a phone call.
 */
@Serializable
public enum class CallStatus {
    /** Call initiated but not yet ringing (Vonage: started) */
    STARTED,
    /** Call is queued for processing (Twilio-specific, maps to STARTED for others) */
    QUEUED,
    /** Call is ringing at the recipient */
    RINGING,
    /** Call is connected and active */
    IN_PROGRESS,
    /** Call is on hold */
    ON_HOLD,
    /** Call completed normally */
    COMPLETED,
    /** Recipient's line was busy */
    BUSY,
    /** No answer within timeout */
    NO_ANSWER,
    /** Call was explicitly rejected/declined by recipient */
    REJECTED,
    /** Call was canceled before connecting */
    CANCELED,
    /** Call failed due to technical error */
    FAILED
}

/**
 * Reason why a call ended.
 */
@Serializable
public enum class CallEndReason {
    /** Normal hangup by either party */
    COMPLETED,
    /** Recipient's line was busy */
    BUSY,
    /** No answer within timeout */
    NO_ANSWER,
    /** Call was actively rejected/declined */
    REJECTED,
    /** Technical failure (network, provider error) */
    FAILED,
    /** Caller cancelled before connection */
    CANCELLED
}

/**
 * Information about an active or completed call.
 *
 * @property callId Unique identifier for the call
 * @property status Current call status
 * @property direction Whether call was inbound or outbound
 * @property from Caller phone number
 * @property to Recipient phone number
 * @property startTime When the call started
 * @property duration Call duration (if completed)
 * @property answeredBy Who/what answered the call (if machine detection was enabled)
 */
@Serializable
public data class CallInfo(
    val callId: String,
    val status: CallStatus,
    val direction: CallDirection,
    val from: PhoneNumber,
    val to: PhoneNumber,
    val startTime: Instant? = null,
    val duration: Duration? = null,
    val answeredBy: AnsweredBy? = null
)

/**
 * Who or what answered an outbound call.
 * Only populated when machine detection is enabled.
 */
@Serializable
public enum class AnsweredBy {
    /** A human answered the call */
    HUMAN,
    /** An answering machine started its greeting */
    MACHINE_START,
    /** End of machine greeting detected (beep heard) */
    MACHINE_END_BEEP,
    /** End of machine greeting detected (silence) */
    MACHINE_END_SILENCE,
    /** End of machine greeting detected (other) */
    MACHINE_END_OTHER,
    /** A fax machine answered */
    FAX,
    /** Could not determine who answered */
    UNKNOWN
}

// ==================== DTMF/Gather Events ====================

/**
 * Event received when caller enters DTMF digits or speech input.
 *
 * This is sent when a [CallInstructions.Gather] completes, either because
 * the expected number of digits was entered, a finish key was pressed,
 * or the timeout was reached.
 *
 * @property callId Unique identifier for the call
 * @property digits The DTMF digits pressed (e.g., "1", "123#")
 * @property speechResult Speech recognition result if speech input was enabled
 * @property confidence Confidence of speech recognition (0.0 to 1.0)
 * @property metadata Additional provider-specific metadata
 */
@Serializable
public data class DtmfEvent(
    val callId: String,
    val digits: String = "",
    val speechResult: String? = null,
    val confidence: Float? = null,
    val metadata: Map<String, String> = emptyMap()
)

// ==================== TTS Voice Configuration ====================

/**
 * Text-to-speech voice configuration.
 *
 * @property language BCP-47 language code (e.g., "en-US", "es-MX", "fr-FR")
 * @property gender Preferred voice gender
 * @property name Provider-specific voice name (overrides language/gender if set)
 * @property style Provider-specific voice style variant (e.g., Vonage style 0-5)
 * @property ssml If true, treat text as SSML markup for advanced speech control
 */
@Serializable
public data class TtsVoice(
    val language: String = "en-US",
    val gender: TtsGender = TtsGender.NEUTRAL,
    val name: String? = null,
    val style: Int? = null,
    val ssml: Boolean = false
)

/**
 * Voice gender preference for text-to-speech.
 */
@Serializable
public enum class TtsGender {
    MALE,
    FEMALE,
    NEUTRAL
}

// ==================== Rendered Instructions ====================

/**
 * Provider-specific rendered call instructions with content type.
 *
 * Different providers use different formats:
 * - Twilio: TwiML (XML)
 * - Plivo: Plivo XML
 * - Vonage: NCCO (JSON)
 *
 * @property content The rendered instructions string
 * @property contentType Media type of the content (application/xml or application/json)
 */
public data class RenderedInstructions(
    val content: String,
    val contentType: InstructionsContentType
)

/**
 * Content type for rendered call instructions.
 */
public enum class InstructionsContentType(public val mediaType: String) {
    /** XML format (Twilio TwiML, Plivo XML, Bandwidth BXML) */
    XML("application/xml"),
    /** JSON format (Vonage NCCO) */
    JSON("application/json")
}

// ==================== Exception ====================

/**
 * Exception thrown when phone call operations fail.
 *
 * Common causes:
 * - Invalid phone number format (not E.164)
 * - Insufficient account balance
 * - Rate limit exceeded
 * - Invalid credentials
 * - Call not found
 * - Provider error
 */
public class PhoneCallException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
