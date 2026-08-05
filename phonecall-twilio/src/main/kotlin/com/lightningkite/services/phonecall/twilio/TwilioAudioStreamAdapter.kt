package com.lightningkite.services.phonecall.twilio

import com.lightningkite.services.data.TypedData
import com.lightningkite.services.phonecall.*
import com.lightningkite.services.webhooksubservice.WebsocketAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.*
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val logger = KotlinLogging.logger("TwilioAudioStreamAdapter")

/**
 * WebSocket adapter for Twilio Media Streams.
 *
 * Twilio Media Streams provide bidirectional audio streaming over WebSocket.
 * The audio is μ-law 8kHz mono, base64-encoded in JSON messages.
 *
 * ## Twilio Message Format (Inbound)
 *
 * ```json
 * // Connected event
 * {"event": "connected", "protocol": "Call", "version": "1.0.0"}
 *
 * // Start event (contains stream metadata)
 * {"event": "start", "streamSid": "MZ...", "start": {"callSid": "CA...", "customParameters": {...}}}
 *
 * // Media event (audio data)
 * {"event": "media", "streamSid": "MZ...", "media": {"payload": "base64...", "timestamp": "123", "chunk": "1"}}
 *
 * // DTMF event
 * {"event": "dtmf", "streamSid": "MZ...", "dtmf": {"digit": "1"}}
 *
 * // Stop event
 * {"event": "stop", "streamSid": "MZ..."}
 * ```
 *
 * ## Twilio Message Format (Outbound)
 *
 * ```json
 * // Send audio
 * {"event": "media", "streamSid": "MZ...", "media": {"payload": "base64..."}}
 *
 * // Clear audio queue
 * {"event": "clear", "streamSid": "MZ..."}
 *
 * // Mark for tracking
 * {"event": "mark", "streamSid": "MZ...", "mark": {"name": "my-mark"}}
 * ```
 *
 * ## Stateless Design
 *
 * This adapter is completely stateless to support serverless environments like AWS Lambda
 * where each WebSocket message may be handled by a different Lambda instance. All necessary
 * information is extracted directly from each message's JSON payload.
 *
 * ## Signature Validation
 *
 * When [authToken] is supplied, the WebSocket upgrade request is validated the same way Twilio's
 * REST webhooks are: HMAC-SHA1 over the expected URL (plus any sorted query parameters) using
 * [authToken] as the key, compared against the `X-Twilio-Signature` header in constant time. Since
 * [CallInstructions.StreamAudio.websocketUrl] is a single static endpoint per app (Twilio does not
 * forward query parameters on it — call-specific data travels via `customParameters` instead), the
 * expected URL is configured once via [configureExpectedUrl] rather than derived per-request.
 *
 * Validation is mandatory and fails closed once [authToken] is provided: [configureExpectedUrl]
 * must be called before [parseStart], or every connection attempt is rejected. If [authToken] is
 * left `null`, no validation is performed and the endpoint is unauthenticated — callers should only
 * omit it when the WebSocket endpoint is otherwise secured (e.g. not publicly reachable).
 *
 * @property authToken Twilio auth token for signature validation (optional; see above)
 * @see <a href="https://www.twilio.com/docs/voice/media-streams">Twilio Media Streams</a>
 */
public class TwilioAudioStreamAdapter(
    private val authToken: String? = null,
) : WebsocketAdapter<AudioStreamStart, AudioStreamEvent, AudioStreamCommand> {

    private val json = Json { ignoreUnknownKeys = true }

    // The static wss:// URL Twilio was configured to connect to, used to validate the signature.
    // Set once via configureExpectedUrl(); see the class doc for why a single static URL suffices.
    @Volatile
    private var expectedUrl: String? = null

    private val hmacKeySpec by lazy { SecretKeySpec(authToken!!.toByteArray(Charsets.UTF_8), "HmacSHA1") }

    // Mac is NOT thread-safe, so use a ThreadLocal (mirrors TwilioPhoneCallService's approach).
    private val threadLocalMac = ThreadLocal.withInitial<Mac> {
        Mac.getInstance("HmacSHA1").also { it.init(hmacKeySpec) }
    }

    /**
     * Configures the static WebSocket URL Twilio was given for [CallInstructions.StreamAudio],
     * used to validate the `X-Twilio-Signature` header on incoming connections. Must be called
     * before [parseStart] if this adapter was constructed with an [authToken].
     */
    public fun configureExpectedUrl(url: String) {
        this.expectedUrl = url
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun validateSignature(url: String, params: Map<String, String>, signature: String): Boolean {
        val data = buildString {
            append(url)
            params.keys.sorted().forEach { key ->
                append(key)
                append(params[key] ?: "")
            }
        }
        val mac = threadLocalMac.get()
        mac.reset()
        val rawHmac = mac.doFinal(data.toByteArray(Charsets.UTF_8))

        // Decode and compare in constant time — string equality short-circuits and leaks
        // timing information about the expected HMAC.
        val provided = try {
            Base64.decode(signature)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return MessageDigest.isEqual(provided, rawHmac)
    }

    override suspend fun parseStart(
        queryParameters: List<Pair<String, String>>,
        headers: Map<String, List<String>>,
        body: TypedData,
    ): AudioStreamStart {
        // Twilio WebSocket connections don't have body content on upgrade
        // The stream metadata comes in the first "start" message
        // For now, we return a placeholder that will be updated on "start" event

        if (authToken != null) {
            val url = expectedUrl
                ?: throw SecurityException(
                    "Cannot validate Twilio Media Streams signature: expected WebSocket URL not configured. " +
                            "Call configureExpectedUrl() with the wss:// URL used for StreamAudio before processing connections."
                )
            val signature = headers["X-Twilio-Signature"]?.firstOrNull()
                ?: headers["x-twilio-signature"]?.firstOrNull()
                ?: throw SecurityException("Missing X-Twilio-Signature header")
            if (!validateSignature(url, queryParameters.toMap(), signature)) {
                throw SecurityException("Invalid Twilio Media Streams signature")
            }
        }

        logger.debug { "WebSocket connection initiated, awaiting start event" }

        return AudioStreamStart(
            callId = "",  // Will be populated from "start" event
            streamId = "",  // Will be populated from "start" event
            metadata = queryParameters.toMap()
        )
    }

    override suspend fun parse(frame: WebsocketAdapter.Frame): AudioStreamEvent {
        val text = when (frame) {
            is WebsocketAdapter.Frame.Text -> frame.text
            is WebsocketAdapter.Frame.Binary -> frame.bytes.decodeToString()
        }

        val jsonObj = json.parseToJsonElement(text).jsonObject
        val event = jsonObj["event"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing 'event' field in Twilio message")

        // Extract streamSid from message (present in all events except "connected")
        val streamSid = jsonObj["streamSid"]?.jsonPrimitive?.contentOrNull ?: ""

        return when (event) {
            "connected" -> {
                // Initial connected event - doesn't have stream info yet
                // Return NoOp since we need to wait for "start" event for actual metadata
                logger.debug { "Twilio stream connected (protocol: ${jsonObj["protocol"]?.jsonPrimitive?.contentOrNull})" }
                AudioStreamEvent.NoOp
            }

            "start" -> {
                val startObj = jsonObj["start"]?.jsonObject
                    ?: throw IllegalArgumentException("Missing 'start' object in start event")
                val callSid = startObj["callSid"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Missing 'callSid' in start event")

                // Extract custom parameters
                val params = startObj["customParameters"]?.jsonObject?.let { paramsObj ->
                    paramsObj.entries.associate { (k, v) ->
                        k to (v.jsonPrimitive.contentOrNull ?: "")
                    }
                } ?: emptyMap()

                logger.info { "Twilio stream started: streamSid=$streamSid, callSid=$callSid" }

                AudioStreamEvent.Connected(
                    callId = callSid,
                    streamId = streamSid,
                    customParameters = params
                )
            }

            "media" -> {
                val mediaObj = jsonObj["media"]?.jsonObject
                    ?: throw IllegalArgumentException("Missing 'media' object in media event")

                val payload = mediaObj["payload"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Missing 'payload' in media event")
                val timestamp = mediaObj["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val chunk = mediaObj["chunk"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

                // Note: callId is not available in media events, but streamId is sufficient
                // for routing since the caller already has context from the Connected event
                AudioStreamEvent.Audio(
                    callId = "",
                    streamId = streamSid,
                    payload = payload,
                    timestamp = timestamp,
                    sequenceNumber = chunk
                )
            }

            "dtmf" -> {
                val dtmfObj = jsonObj["dtmf"]?.jsonObject
                    ?: throw IllegalArgumentException("Missing 'dtmf' object in dtmf event")
                val digit = dtmfObj["digit"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Missing 'digit' in dtmf event")

                logger.debug { "DTMF received: $digit on stream $streamSid" }

                AudioStreamEvent.Dtmf(
                    callId = "",
                    streamId = streamSid,
                    digit = digit
                )
            }

            "stop" -> {
                logger.info { "Twilio stream stopping: $streamSid" }

                AudioStreamEvent.Stop(
                    callId = "",
                    streamId = streamSid
                )
            }

            "mark" -> {
                // Mark events are acknowledgments - log and drop
                val markName = jsonObj["mark"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                logger.debug { "Mark received: $markName on stream $streamSid" }
                AudioStreamEvent.NoOp
            }

            else -> {
                logger.warn { "Unknown Twilio stream event: $event" }
                throw IllegalArgumentException("Unknown Twilio stream event: $event")
            }
        }
    }

    override suspend fun render(output: AudioStreamCommand): WebsocketAdapter.Frame {
        val jsonStr = when (output) {
            is AudioStreamCommand.Audio -> {
                buildJsonObject {
                    put("event", "media")
                    put("streamSid", output.streamId)
                    putJsonObject("media") {
                        put("payload", output.payload)
                    }
                }.toString()
            }

            is AudioStreamCommand.Clear -> {
                buildJsonObject {
                    put("event", "clear")
                    put("streamSid", output.streamId)
                }.toString()
            }

            is AudioStreamCommand.Mark -> {
                buildJsonObject {
                    put("event", "mark")
                    put("streamSid", output.streamId)
                    putJsonObject("mark") {
                        put("name", output.name)
                    }
                }.toString()
            }
        }

        return WebsocketAdapter.Frame.Text(jsonStr)
    }

    public companion object {
        /**
         * Converts a list of query parameters to a map.
         * If there are duplicate keys, the last value wins.
         */
        private fun List<Pair<String, String>>.toMap(): Map<String, String> =
            this.associate { it.first to it.second }
    }
}
