package com.lightningkite.services.speech.openai

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.speech.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger("OpenAITextToSpeechService")

/**
 * OpenAI Text-to-Speech implementation.
 *
 * OpenAI TTS provides high-quality voice synthesis with natural intonation.
 * Available voices are optimized for different use cases.
 *
 * ## URL Format
 *
 * Uses standard URI format with auth before the host/model:
 *
 * ```
 * openai://apiKey@model
 * openai://apiKey@tts-1
 * openai://sk-abc123@tts-1-hd
 * ```
 *
 * Components:
 * - `apiKey` - OpenAI API key (before @). Use `${OPENAI_API_KEY}` for env var.
 * - `model` - Model name (after @). Defaults to "tts-1" if omitted.
 *
 * Legacy format (still supported):
 * ```
 * openai://?apiKey=xxx&model=yyy
 * ```
 *
 * ## Models
 *
 * - `tts-1` - Standard quality, lower latency, cost-effective
 * - `tts-1-hd` - Higher quality audio, higher latency
 *
 * ## Available Voices
 *
 * - `alloy` - Neutral, balanced
 * - `echo` - Male, warm
 * - `fable` - British accent, expressive
 * - `onyx` - Male, deep
 * - `nova` - Female, warm
 * - `shimmer` - Female, clear
 *
 * ## Pricing (approximate, as of 2024)
 *
 * - tts-1: $15 per 1M characters (~$0.015 per 1K chars)
 * - tts-1-hd: $30 per 1M characters (~$0.030 per 1K chars)
 *
 * @see TextToSpeechService
 */
public class OpenAITextToSpeechService(
    override val name: String,
    override val context: SettingContext,
    private val apiKey: String,
    private val defaultModel: String = "tts-1"
) : TextToSpeechService {

    private val baseUrl = "https://api.openai.com/v1"

    private val client = com.lightningkite.services.http.client.config {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    /**
     * OpenAI's available voices.
     */
    private val availableVoices = listOf(
        VoiceInfo(
            voiceId = "alloy",
            name = "Alloy",
            language = "en-US",
            gender = TtsGender.NEUTRAL,
            description = "Neutral, balanced voice"
        ),
        VoiceInfo(
            voiceId = "echo",
            name = "Echo",
            language = "en-US",
            gender = TtsGender.MALE,
            description = "Male, warm voice"
        ),
        VoiceInfo(
            voiceId = "fable",
            name = "Fable",
            language = "en-GB",
            gender = TtsGender.NEUTRAL,
            description = "British accent, expressive"
        ),
        VoiceInfo(
            voiceId = "onyx",
            name = "Onyx",
            language = "en-US",
            gender = TtsGender.MALE,
            description = "Male, deep voice"
        ),
        VoiceInfo(
            voiceId = "nova",
            name = "Nova",
            language = "en-US",
            gender = TtsGender.FEMALE,
            description = "Female, warm voice"
        ),
        VoiceInfo(
            voiceId = "shimmer",
            name = "Shimmer",
            language = "en-US",
            gender = TtsGender.FEMALE,
            description = "Female, clear voice"
        )
    )

    override suspend fun listVoices(language: String?): List<VoiceInfo> {
        // OpenAI voices are multilingual, so we return all of them
        // They support 57+ languages with the same voices
        return if (language != null) {
            // All OpenAI voices support multiple languages
            availableVoices.map { it.copy(language = language) }
        } else {
            availableVoices
        }
    }

    override suspend fun synthesize(
        text: String,
        voice: TtsVoiceConfig,
        options: TtsSynthesisOptions
    ): TypedData {
        val voiceId = voice.voiceId ?: getDefaultVoice(voice.gender)
        val model = options.model ?: defaultModel
        val responseFormat = mapOutputFormat(options.outputFormat)

        logger.debug { "[$name] Synthesizing ${text.length} chars with voice=$voiceId, model=$model" }

        val response = client.post("$baseUrl/audio/speech") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)

            setBody(buildSynthesisRequest(text, voiceId, model, responseFormat, options.speed))
        }

        if (!response.status.isSuccess()) {
            val error = response.bodyAsText()
            logger.error { "[$name] TTS failed: $error" }
            throw TextToSpeechException("TTS synthesis failed: $error")
        }

        val audioBytes = response.readBytes()
        val mediaType = mapFormatToMediaType(options.outputFormat)

        logger.debug { "[$name] Synthesized ${audioBytes.size} bytes of audio" }
        return TypedData(Data.Bytes(audioBytes), mediaType)
    }

    override fun synthesizeStream(
        text: String,
        voice: TtsVoiceConfig,
        options: TtsSynthesisOptions
    ): Flow<TypedData> = flow {
        val voiceId = voice.voiceId ?: getDefaultVoice(voice.gender)
        val model = options.model ?: defaultModel
        val responseFormat = mapOutputFormat(options.outputFormat)

        logger.debug { "[$name] Streaming TTS for ${text.length} chars with voice=$voiceId" }

        val response = client.post("$baseUrl/audio/speech") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)

            setBody(buildSynthesisRequest(text, voiceId, model, responseFormat, options.speed))
        }

        if (!response.status.isSuccess()) {
            val error = response.bodyAsText()
            logger.error { "[$name] TTS stream failed: $error" }
            throw TextToSpeechException("TTS streaming failed: $error")
        }

        val mediaType = mapFormatToMediaType(options.outputFormat)
        val channel = response.bodyAsChannel()

        // Read chunks from the stream
        val buffer = ByteArray(4096)
        while (!channel.isClosedForRead) {
            val bytesRead = channel.readAvailable(buffer)
            if (bytesRead > 0) {
                emit(TypedData(Data.Bytes(buffer.copyOf(bytesRead)), mediaType))
            }
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            val response = client.get("$baseUrl/models") {
                header("Authorization", "Bearer $apiKey")
            }
            if (response.status.isSuccess()) {
                HealthStatus(HealthStatus.Level.OK, additionalMessage = "OpenAI TTS API accessible")
            } else {
                HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "OpenAI API returned ${response.status}")
            }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "OpenAI API error: ${e.message}")
        }
    }

    private fun buildSynthesisRequest(
        text: String,
        voice: String,
        model: String,
        responseFormat: String,
        speed: Float
    ): String {
        return buildString {
            append("{")
            append("\"model\":\"$model\",")
            append("\"input\":\"${escapeJson(text)}\",")
            append("\"voice\":\"$voice\",")
            append("\"response_format\":\"$responseFormat\",")
            append("\"speed\":$speed")
            append("}")
        }
    }

    private fun getDefaultVoice(gender: TtsGender): String {
        return when (gender) {
            TtsGender.MALE -> "onyx"
            TtsGender.FEMALE -> "nova"
            TtsGender.NEUTRAL -> "alloy"
        }
    }

    private fun mapOutputFormat(format: AudioFormat): String {
        return when (format) {
            AudioFormat.MP3_44100_128, AudioFormat.MP3_44100_192 -> "mp3"
            AudioFormat.OGG_OPUS -> "opus"
            AudioFormat.WAV_44100 -> "wav"
            AudioFormat.PCM_24000 -> "pcm"  // OpenAI returns 24kHz PCM
            else -> "mp3"  // Default to MP3 for unsupported formats
        }
    }

    private fun mapFormatToMediaType(format: AudioFormat): MediaType {
        return when (format) {
            AudioFormat.MP3_44100_128, AudioFormat.MP3_44100_192 -> MediaType.Audio.MPEG
            AudioFormat.OGG_OPUS -> MediaType.Audio.OGG
            AudioFormat.WAV_44100 -> MediaType.Audio.WAV
            AudioFormat.PCM_24000 -> MediaType("audio", "pcm")
            else -> MediaType.Audio.MPEG
        }
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    public companion object {
        init {
            TextToSpeechService.Settings.register("openai") { name, url, context ->
                val (apiKey, model) = parseOpenAITtsUrl(url)
                OpenAITextToSpeechService(name, context, apiKey, model)
            }
        }

        /**
         * Creates OpenAI TTS settings URL.
         *
         * @param apiKey OpenAI API key (or use env var reference like "\${OPENAI_API_KEY}")
         * @param model Default model (optional, "tts-1" or "tts-1-hd")
         */
        public fun TextToSpeechService.Settings.Companion.openai(
            apiKey: String,
            model: String = "tts-1"
        ): TextToSpeechService.Settings = TextToSpeechService.Settings("openai://$apiKey@$model")
    }
}

/**
 * Parses OpenAI TTS URL in either standard or legacy format.
 *
 * Standard format: `openai://apiKey@model`
 * Legacy format: `openai://?apiKey=xxx&model=yyy`
 *
 * @return Pair of (apiKey, model)
 */
private fun parseOpenAITtsUrl(url: String): Pair<String, String> {
    val defaultModel = "tts-1"

    // Remove scheme prefix
    val withoutScheme = url.substringAfter("://")

    // Check for standard URI format: apiKey@model
    if (withoutScheme.contains("@") && !withoutScheme.startsWith("?")) {
        val apiKeyPart = withoutScheme.substringBefore("@")
        val modelPart = withoutScheme.substringAfter("@").substringBefore("?").ifEmpty { defaultModel }

        val apiKey = resolveEnvVars(apiKeyPart)
        if (apiKey.isBlank() || apiKey.startsWith("\${")) {
            throw IllegalArgumentException(
                "OpenAI API key required. " +
                    "Format: openai://apiKey@model or openai://\${OPENAI_API_KEY}@model"
            )
        }

        return apiKey to modelPart
    }

    // Fall back to legacy query parameter format: ?apiKey=xxx&model=yyy
    val params = parseUrlParams(url)
    val apiKey = params["apiKey"]?.let(::resolveEnvVars)
        ?: System.getenv("OPENAI_API_KEY")
        ?: throw IllegalArgumentException(
            "OpenAI API key required. " +
                "Format: openai://apiKey@model or set OPENAI_API_KEY environment variable."
        )
    val model = params["model"] ?: defaultModel

    return apiKey to model
}

private fun parseUrlParams(url: String): Map<String, String> {
    val queryString = url.substringAfter("?", "")
    if (queryString.isEmpty()) return emptyMap()

    return queryString.split("&")
        .mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        .toMap()
}

private fun resolveEnvVars(value: String): String {
    val envVarPattern = """\$\{([^}]+)\}""".toRegex()
    return envVarPattern.replace(value) { matchResult ->
        val envVar = matchResult.groupValues[1]
        System.getenv(envVar) ?: matchResult.value
    }
}
