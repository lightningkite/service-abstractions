package com.lightningkite.services.speech

import com.lightningkite.services.*
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Service abstraction for converting speech audio to text.
 *
 * SpeechToTextService provides a unified interface for transcribing audio
 * across different providers (ElevenLabs Scribe, OpenAI Whisper, Google, etc.).
 * Applications can switch STT providers via configuration without code changes.
 *
 * ## Available Implementations
 *
 * - **TestSpeechToTextService** (`test`) - Returns mock transcriptions for testing
 * - **ConsoleSpeechToTextService** (`console`) - Logs STT requests (development)
 * - **VoskSpeechToTextService** (`vosk://`, `local://`) - Local/offline (requires speech-local)
 * - **ElevenLabsSpeechToTextService** (`elevenlabs-stt://`) - ElevenLabs Scribe (requires speech-elevenlabs)
 * - **OpenAISpeechToTextService** (`openai-stt://`) - OpenAI Whisper (requires speech-openai)
 *
 * ## URL Format
 *
 * All implementations use standard URI format: `scheme://auth@host[/path][?query]`
 *
 * The auth (API key) goes **before** the @ symbol, following standard URI conventions:
 *
 * ```
 * elevenlabs-stt://apiKey@model
 * openai-stt://apiKey@model
 * vosk://?modelName=vosk-model-small-en-us-0.15
 * ```
 *
 * Use environment variable references for credentials:
 * ```
 * elevenlabs-stt://${ELEVENLABS_API_KEY}@scribe_v1
 * openai-stt://${OPENAI_API_KEY}@whisper-1
 * ```
 *
 * ## Configuration Example
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val stt: SpeechToTextService.Settings = SpeechToTextService.Settings(
 *         "elevenlabs-stt://\${ELEVENLABS_API_KEY}@scribe_v1"
 *     )
 * )
 *
 * val context = SettingContext(...)
 * val sttService: SpeechToTextService = settings.stt("stt", context)
 * ```
 *
 * ## Batch Transcription
 *
 * ```kotlin
 * // Transcribe an audio file
 * val result = sttService.transcribe(audioData)
 * println("Transcription: ${result.text}")
 *
 * // With word-level timestamps
 * result.words.forEach { word ->
 *     println("${word.text} at ${word.startTime}")
 * }
 *
 * // With speaker diarization
 * val result = sttService.transcribe(
 *     audio = audioData,
 *     options = TranscriptionOptions(speakerDiarization = true)
 * )
 * result.speakers.forEach { segment ->
 *     println("Speaker ${segment.speakerId}: ${segment.text}")
 * }
 * ```
 *
 * ## Phone Call Integration
 *
 * For phone call transcription, you can either:
 * 1. Use [PhoneCallService.onTranscription] webhook for real-time transcription
 * 2. Use [CallInstructions.Record] to record audio, then transcribe with this service
 *
 * Option 2 is useful when you need features not available through the phone
 * provider's transcription (like speaker diarization or specific languages).
 *
 * ## Important Gotchas
 *
 * - **Cost per minute**: STT is typically charged per audio minute (~$0.006-0.02/min)
 * - **File size limits**: Most providers limit file size (e.g., 25MB for Whisper)
 * - **Audio format**: MP3, WAV, WebM, M4A typically supported
 * - **Language detection**: Most providers auto-detect language if not specified
 * - **Accuracy varies**: Accuracy depends on audio quality, accents, background noise
 * - **Processing time**: Batch transcription can take seconds to minutes for long files
 *
 * @see TextToSpeechService for the reverse operation
 * @see TranscriptionOptions for transcription configuration
 */
public interface SpeechToTextService : Service {

    /**
     * Configuration for instantiating a SpeechToTextService.
     *
     * ## URL Format
     *
     * Uses standard URI format: `scheme://auth@host[?query]`
     *
     * The URL scheme determines the STT provider:
     * - `test` - Return mock transcriptions for testing (default)
     * - `console` - Log STT requests for development
     * - `vosk://` or `local://` - Local Vosk (requires speech-local module)
     * - `elevenlabs-stt://apiKey@model` - ElevenLabs Scribe (requires speech-elevenlabs module)
     * - `openai-stt://apiKey@model` - OpenAI Whisper (requires speech-openai module)
     *
     * ## Examples
     *
     * ```
     * test                                    // Mock for testing
     * vosk://                                 // Local with default model
     * elevenlabs-stt://xi-abc123@scribe_v1   // ElevenLabs with API key
     * openai-stt://${OPENAI_API_KEY}@whisper-1  // OpenAI with env var
     * ```
     *
     * @property url Connection string defining the STT provider and credentials
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "test"
    ) : Setting<SpeechToTextService> {
        public companion object : UrlSettingParser<SpeechToTextService>() {
            init {
                register("test") { name, _, context -> TestSpeechToTextService(name, context) }
                register("console") { name, _, context -> ConsoleSpeechToTextService(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): SpeechToTextService {
            return parse(name, url, context)
        }
    }

    /**
     * Transcribes audio to text.
     *
     * @param audio Audio data (mp3, wav, webm, m4a, etc.)
     * @param options Transcription options (language, timestamps, diarization)
     * @return Transcription result with text and optional metadata
     * @throws SpeechToTextException if transcription fails
     */
    public suspend fun transcribe(
        audio: TypedData,
        options: TranscriptionOptions = TranscriptionOptions()
    ): TranscriptionResult

    /**
     * Transcribes audio from a URL.
     *
     * Some providers support fetching audio directly from a URL,
     * which can be more efficient for large files.
     *
     * @param audioUrl URL of the audio file (must be publicly accessible)
     * @param options Transcription options
     * @return Transcription result
     * @throws SpeechToTextException if transcription fails
     */
    public suspend fun transcribeUrl(
        audioUrl: String,
        options: TranscriptionOptions = TranscriptionOptions()
    ): TranscriptionResult

    override val healthCheckFrequency: Duration get() = 5.minutes

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "STT service ready")
    }
}

/**
 * Exception thrown when STT operations fail.
 *
 * Common causes:
 * - Unsupported audio format
 * - Audio too long or file too large
 * - Audio quality too poor for transcription
 * - Unsupported language
 * - Rate limit exceeded
 * - Invalid credentials
 * - Provider error
 */
public class SpeechToTextException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
