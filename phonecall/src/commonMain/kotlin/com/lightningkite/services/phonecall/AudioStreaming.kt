package com.lightningkite.services.phonecall

import kotlinx.serialization.Serializable

/**
 * Information about an established audio stream connection.
 *
 * Returned from [WebsocketAdapter.parseStart] after validating the WebSocket
 * connection request from the provider. This contains connection metadata
 * that may be useful for routing or logging.
 *
 * @property callId The call this stream belongs to
 * @property streamId Provider-specific stream identifier
 * @property metadata Additional provider-specific metadata
 */
@Serializable
public data class AudioStreamStart(
    val callId: String,
    val streamId: String,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Events received from an audio stream.
 *
 * When audio streaming is enabled on a call, the provider connects to your
 * WebSocket endpoint and sends these events. The audio format is typically
 * μ-law 8kHz mono, base64-encoded.
 *
 * ## Event Flow
 *
 * 1. Connection established (validated via [AudioStreamStart])
 * 2. [Connected] event with stream metadata
 * 3. [Audio] events with audio data from the caller
 * 4. Optional [Dtmf] events if caller presses keypad
 * 5. [Stop] event when stream ends
 *
 * @see AudioStreamCommand
 */
@Serializable
public sealed class AudioStreamEvent {
    /**
     * Stream connected and ready for audio.
     *
     * This is sent after the WebSocket connection is established and
     * the provider is ready to start streaming audio.
     *
     * @property callId The call this stream belongs to
     * @property streamId Provider-specific stream identifier
     * @property customParameters Custom parameters passed when stream was initiated
     */
    @Serializable
    public data class Connected(
        val callId: String,
        val streamId: String,
        val customParameters: Map<String, String> = emptyMap()
    ) : AudioStreamEvent()

    /**
     * Audio data from the caller.
     *
     * Contains a chunk of audio from the call. The format is typically:
     * - Encoding: μ-law (PCMU)
     * - Sample rate: 8000 Hz
     * - Channels: Mono
     * - Payload: Base64-encoded
     *
     * @property callId The call this audio belongs to
     * @property streamId Provider-specific stream identifier
     * @property payload Base64-encoded audio data
     * @property timestamp Milliseconds from stream start
     * @property sequenceNumber Sequence number for ordering/gap detection
     */
    @Serializable
    public data class Audio(
        val callId: String,
        val streamId: String,
        val payload: String,
        val timestamp: Long,
        val sequenceNumber: Long
    ) : AudioStreamEvent()

    /**
     * DTMF digit pressed during the stream.
     *
     * When the caller presses a key on their phone keypad while
     * the stream is active, this event is sent.
     *
     * @property callId The call this event belongs to
     * @property streamId Provider-specific stream identifier
     * @property digit The digit pressed (0-9, *, #)
     */
    @Serializable
    public data class Dtmf(
        val callId: String,
        val streamId: String,
        val digit: String
    ) : AudioStreamEvent()

    /**
     * Stream is stopping.
     *
     * Sent when the audio stream is ending, either because the call
     * ended or the stream was explicitly stopped.
     *
     * @property callId The call this stream belonged to
     * @property streamId Provider-specific stream identifier
     */
    @Serializable
    public data class Stop(
        val callId: String,
        val streamId: String
    ) : AudioStreamEvent()

    /**
     * A no-op event that should be ignored by handlers.
     *
     * Used for provider-specific events (like Twilio "mark" acknowledgments)
     * that don't map to our event model and should be silently dropped.
     */
    @Serializable
    public data object NoOp : AudioStreamEvent()
}

/**
 * Commands to send to an audio stream.
 *
 * Use these commands to send audio back to the caller or control
 * the stream. Audio should be in the same format as received:
 * μ-law 8kHz mono, base64-encoded.
 *
 * @see AudioStreamEvent
 */
@Serializable
public sealed class AudioStreamCommand {
    /**
     * Send audio to the caller.
     *
     * The audio should be:
     * - Encoding: μ-law (PCMU)
     * - Sample rate: 8000 Hz
     * - Channels: Mono
     * - Payload: Base64-encoded
     *
     * Audio is queued and played in order. Use [Clear] to interrupt.
     *
     * @property streamId The stream to send audio to
     * @property payload Base64-encoded audio data
     */
    @Serializable
    public data class Audio(
        val streamId: String,
        val payload: String
    ) : AudioStreamCommand()

    /**
     * Clear any queued audio.
     *
     * Use this to interrupt currently playing audio, for example
     * when the user starts speaking (barge-in).
     *
     * @property streamId The stream to clear
     */
    @Serializable
    public data class Clear(
        val streamId: String
    ) : AudioStreamCommand()

    /**
     * Insert a marker in the audio stream.
     *
     * Markers can be used to track when specific audio has finished
     * playing. The provider will send an event when the marker is reached.
     *
     * @property streamId The stream to mark
     * @property name A name for this marker (for identification in callbacks)
     */
    @Serializable
    public data class Mark(
        val streamId: String,
        val name: String
    ) : AudioStreamCommand()
}

/**
 * Which audio track(s) to stream.
 */
@Serializable
public enum class AudioTrack {
    /** Stream only the inbound audio (from the caller) */
    INBOUND,
    /** Stream only the outbound audio (what the caller hears) */
    OUTBOUND,
    /** Stream both inbound and outbound audio */
    BOTH
}
