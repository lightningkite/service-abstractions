package com.lightningkite.services.speech

import com.lightningkite.services.*
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Service abstraction for converting text to speech audio.
 *
 * TextToSpeechService provides a unified interface for generating audio from text
 * across different providers (ElevenLabs, OpenAI, Google, etc.). Applications can
 * switch TTS providers via configuration without code changes.
 *
 * ## Available Implementations
 *
 * - **TestTextToSpeechService** (`test`) - Returns mock audio for testing
 * - **ConsoleTextToSpeechService** (`console`) - Logs TTS requests (development)
 * - **ElevenLabsTextToSpeechService** (`elevenlabs://`) - ElevenLabs TTS (requires speech-elevenlabs)
 * - **OpenAITextToSpeechService** (`openai-tts://`) - OpenAI TTS (requires speech-openai)
 *
 * ## Configuration
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val tts: TextToSpeechService.Settings = TextToSpeechService.Settings(
 *         "elevenlabs://?apiKey=\${ELEVENLABS_API_KEY}"
 *     )
 * )
 *
 * val context = SettingContext(...)
 * val ttsService: TextToSpeechService = settings.tts("tts", context)
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * // List available voices
 * val voices = ttsService.listVoices(language = "en-US")
 *
 * // Generate speech audio
 * val audio = ttsService.synthesize(
 *     text = "Hello, how can I help you?",
 *     voice = TtsVoiceConfig(voiceId = "rachel", language = "en-US")
 * )
 *
 * // Stream audio for real-time playback
 * ttsService.synthesizeStream(text, voice).collect { chunk ->
 *     audioPlayer.play(chunk)
 * }
 * ```
 *
 * ## Phone Call Integration
 *
 * For phone calls, you can either:
 * 1. Use [PhoneCallService.speak] which uses the provider's built-in TTS
 * 2. Generate audio with this service and use [PhoneCallService.playAudioUrl]
 *
 * Option 2 is useful when you need specific voices (like ElevenLabs voices)
 * that aren't available through the phone provider's TTS.
 *
 * ## Important Gotchas
 *
 * - **Cost per character**: TTS is typically charged per character (~$0.015-0.30 per 1K chars)
 * - **Latency**: First byte latency varies (50-500ms depending on provider/model)
 * - **Audio format**: Default is MP3, but phone calls may need μ-law 8kHz
 * - **Voice availability**: Not all voices support all languages
 * - **Rate limits**: Providers enforce rate limits on concurrent requests
 * - **SSML support**: Some providers support SSML for advanced speech control
 *
 * @see SpeechToTextService for the reverse operation
 * @see TtsVoiceConfig for voice configuration options
 */
public interface TextToSpeechService : Service {

    /**
     * Configuration for instantiating a TextToSpeechService.
     *
     * The URL scheme determines the TTS provider:
     * - `test` - Return mock audio for testing (default)
     * - `console` - Log TTS requests for development
     * - `elevenlabs://?apiKey=xxx` - ElevenLabs (requires speech-elevenlabs module)
     * - `openai-tts://?apiKey=xxx` - OpenAI (requires speech-openai module)
     *
     * @property url Connection string defining the TTS provider and credentials
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "test"
    ) : Setting<TextToSpeechService> {
        public companion object : UrlSettingParser<TextToSpeechService>() {
            init {
                register("test") { name, _, context -> TestTextToSpeechService(name, context) }
                register("console") { name, _, context -> ConsoleTextToSpeechService(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): TextToSpeechService {
            return parse(name, url, context)
        }
    }

    /**
     * Lists available voices for this TTS provider.
     *
     * @param language Optional BCP-47 language code filter (e.g., "en-US", "es-MX")
     * @return List of available voice configurations
     */
    public suspend fun listVoices(language: String? = null): List<VoiceInfo>

    /**
     * Synthesizes text into audio.
     *
     * @param text Text to convert to speech (plain text or SSML if voice.ssml=true)
     * @param voice Voice configuration (voice ID, language, settings)
     * @param options Additional synthesis options (model, format, speed)
     * @return Audio data with appropriate media type
     * @throws TextToSpeechException if synthesis fails
     */
    public suspend fun synthesize(
        text: String,
        voice: TtsVoiceConfig = TtsVoiceConfig(),
        options: TtsSynthesisOptions = TtsSynthesisOptions()
    ): TypedData

    /**
     * Synthesizes text into streaming audio chunks.
     *
     * Use this for real-time audio playback where you want to start
     * playing before the full audio is generated. This reduces perceived
     * latency for long text.
     *
     * @param text Text to convert to speech
     * @param voice Voice configuration
     * @param options Additional synthesis options
     * @return Flow of audio chunks (each chunk is playable audio)
     */
    public fun synthesizeStream(
        text: String,
        voice: TtsVoiceConfig = TtsVoiceConfig(),
        options: TtsSynthesisOptions = TtsSynthesisOptions()
    ): Flow<TypedData>

    override val healthCheckFrequency: Duration get() = 5.minutes

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "TTS service ready")
    }
}

/**
 * Exception thrown when TTS operations fail.
 *
 * Common causes:
 * - Invalid voice ID
 * - Unsupported language
 * - Text too long
 * - Rate limit exceeded
 * - Invalid credentials
 * - Provider error
 */
public class TextToSpeechException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
