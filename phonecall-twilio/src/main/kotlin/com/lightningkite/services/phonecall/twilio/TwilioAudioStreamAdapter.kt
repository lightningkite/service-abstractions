package com.lightningkite.services.phonecall.twilio

import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebsocketAdapter
import com.lightningkite.services.phonecall.AudioStreamCommand
import com.lightningkite.services.phonecall.AudioStreamEvent
import com.lightningkite.services.phonecall.AudioStreamStart
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.*

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
 * @property authToken Twilio auth token for signature validation (optional)
 * @see <a href="https://www.twilio.com/docs/voice/media-streams">Twilio Media Streams</a>
 */
public class TwilioAudioStreamAdapter(
    private val authToken: String? = null
) : WebsocketAdapter<AudioStreamStart, AudioStreamEvent, AudioStreamCommand> {

    private val json = Json { ignoreUnknownKeys = true }

    // Track stream state for correlation
    private var currentStreamSid: String? = null
    private var currentCallSid: String? = null
    private var customParameters: Map<String, String> = emptyMap()

    override suspend fun parseStart(
        queryParameters: List<Pair<String, String>>,
        headers: Map<String, List<String>>,
        body: TypedData
    ): AudioStreamStart {
        // Twilio WebSocket connections don't have body content on upgrade
        // The stream metadata comes in the first "start" message
        // For now, we return a placeholder that will be updated on "start" event

        // TODO: Implement signature validation if authToken is provided
        // Twilio signs WebSocket upgrade requests similar to webhooks

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

        return when (event) {
            "connected" -> {
                // Initial connected event - doesn't have stream info yet
                // We need to wait for the "start" event for actual stream metadata
                logger.debug { "Twilio stream connected (protocol: ${jsonObj["protocol"]?.jsonPrimitive?.contentOrNull})" }
                // Return a connected event with placeholder values
                // The caller should handle this and wait for the actual start
                AudioStreamEvent.Connected(
                    callId = currentCallSid ?: "",
                    streamId = currentStreamSid ?: "",
                    customParameters = emptyMap()
                )
            }

            "start" -> {
                val streamSid = jsonObj["streamSid"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Missing 'streamSid' in start event")
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

                // Store for later use
                currentStreamSid = streamSid
                currentCallSid = callSid
                customParameters = params

                logger.info { "Twilio stream started: streamSid=$streamSid, callSid=$callSid" }

                AudioStreamEvent.Connected(
                    callId = callSid,
                    streamId = streamSid,
                    customParameters = params
                )
            }

            "media" -> {
                val streamSid = jsonObj["streamSid"]?.jsonPrimitive?.contentOrNull ?: currentStreamSid ?: ""
                val mediaObj = jsonObj["media"]?.jsonObject
                    ?: throw IllegalArgumentException("Missing 'media' object in media event")

                val payload = mediaObj["payload"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Missing 'payload' in media event")
                val timestamp = mediaObj["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val chunk = mediaObj["chunk"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

                AudioStreamEvent.Audio(
                    callId = currentCallSid ?: "",
                    streamId = streamSid,
                    payload = payload,
                    timestamp = timestamp,
                    sequenceNumber = chunk
                )
            }

            "dtmf" -> {
                val streamSid = jsonObj["streamSid"]?.jsonPrimitive?.contentOrNull ?: currentStreamSid ?: ""
                val dtmfObj = jsonObj["dtmf"]?.jsonObject
                    ?: throw IllegalArgumentException("Missing 'dtmf' object in dtmf event")
                val digit = dtmfObj["digit"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Missing 'digit' in dtmf event")

                logger.debug { "DTMF received: $digit on stream $streamSid" }

                AudioStreamEvent.Dtmf(
                    callId = currentCallSid ?: "",
                    streamId = streamSid,
                    digit = digit
                )
            }

            "stop" -> {
                val streamSid = jsonObj["streamSid"]?.jsonPrimitive?.contentOrNull ?: currentStreamSid ?: ""
                logger.info { "Twilio stream stopping: $streamSid" }

                AudioStreamEvent.Stop(
                    callId = currentCallSid ?: "",
                    streamId = streamSid
                )
            }

            "mark" -> {
                // Mark events are acknowledgments - we can log but don't need to surface
                val streamSid = jsonObj["streamSid"]?.jsonPrimitive?.contentOrNull ?: currentStreamSid ?: ""
                val markName = jsonObj["mark"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                logger.debug { "Mark received: $markName on stream $streamSid" }

                // Return as a connected event (no-op) since we don't have a Mark event type
                // The caller can ignore these
                AudioStreamEvent.Connected(
                    callId = currentCallSid ?: "",
                    streamId = streamSid,
                    customParameters = mapOf("_markReceived" to (markName ?: ""))
                )
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
