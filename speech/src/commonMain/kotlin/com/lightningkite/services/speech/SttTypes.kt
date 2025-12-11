package com.lightningkite.services.speech

import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Options for speech-to-text transcription.
 *
 * @property language BCP-47 language code hint (e.g., "en-US"). If null, auto-detect.
 * @property model Provider-specific model ID (e.g., "scribe_v1", "whisper-1")
 * @property wordTimestamps Include word-level timing information in results
 * @property speakerDiarization Identify and label different speakers
 * @property maxSpeakers Hint for maximum number of speakers (improves diarization accuracy)
 * @property audioEvents Detect non-speech audio events (laughter, applause, music)
 * @property prompt Optional prompt to guide transcription (context, spelling hints)
 */
@Serializable
public data class TranscriptionOptions(
    val language: String? = null,
    val model: String? = null,
    val wordTimestamps: Boolean = true,
    val speakerDiarization: Boolean = false,
    val maxSpeakers: Int? = null,
    val audioEvents: Boolean = false,
    val prompt: String? = null
)

/**
 * Result of a transcription operation.
 *
 * @property text Full transcribed text
 * @property language Detected language code (BCP-47)
 * @property languageConfidence Confidence in language detection (0.0-1.0)
 * @property words Word-level transcription with timestamps (if requested)
 * @property speakers Speaker segments with attributed text (if diarization enabled)
 * @property audioEvents Detected audio events (if requested)
 * @property duration Total audio duration
 */
@Serializable
public data class TranscriptionResult(
    val text: String,
    val language: String? = null,
    val languageConfidence: Float? = null,
    val words: List<TranscribedWord> = emptyList(),
    val speakers: List<SpeakerSegment> = emptyList(),
    val audioEvents: List<AudioEvent> = emptyList(),
    val duration: Duration? = null
)

/**
 * A transcribed word with timing information.
 *
 * @property text The transcribed word
 * @property startTime When the word starts in the audio
 * @property endTime When the word ends in the audio
 * @property confidence Transcription confidence for this word (0.0-1.0)
 * @property speakerId Speaker ID if diarization was enabled
 */
@Serializable
public data class TranscribedWord(
    val text: String,
    val startTime: Duration,
    val endTime: Duration,
    val confidence: Float? = null,
    val speakerId: String? = null
)

/**
 * A segment of audio attributed to a specific speaker.
 *
 * Used when speaker diarization is enabled to group consecutive
 * words by the same speaker.
 *
 * @property speakerId Identifier for the speaker (e.g., "speaker_0", "speaker_1")
 * @property startTime When this speaker segment starts
 * @property endTime When this speaker segment ends
 * @property text Combined text spoken in this segment
 */
@Serializable
public data class SpeakerSegment(
    val speakerId: String,
    val startTime: Duration,
    val endTime: Duration,
    val text: String
)

/**
 * Detected audio event (non-speech).
 *
 * @property type Type of audio event detected
 * @property startTime When the event starts
 * @property endTime When the event ends
 */
@Serializable
public data class AudioEvent(
    val type: AudioEventType,
    val startTime: Duration,
    val endTime: Duration
)

/**
 * Types of non-speech audio events that can be detected.
 */
@Serializable
public enum class AudioEventType {
    /** Laughter detected */
    LAUGHTER,

    /** Applause or clapping */
    APPLAUSE,

    /** Background music */
    MUSIC,

    /** Silence or pause */
    SILENCE,

    /** Background noise */
    NOISE,

    /** Other unclassified audio event */
    OTHER
}
