package com.lightningkite.services.speech.elevenlabs

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

private val logger = KotlinLogging.logger("ElevenLabsTextToSpeechService")

/**
 * ElevenLabs Text-to-Speech implementation.
 *
 * ElevenLabs provides high-quality, natural-sounding voices with emotional
 * expressiveness. Their TTS models are particularly good for conversational
 * AI, audiobooks, and content creation.
 *
 * ## URL Format
 *
 * Uses standard URI format with auth before the host/model:
 *
 * ```
 * elevenlabs://apiKey@model
 * elevenlabs://apiKey@eleven_multilingual_v2
 * elevenlabs://xi-abc123@eleven_flash_v2_5
 * ```
 *
 * Components:
 * - `apiKey` - ElevenLabs API key (before @). Use `${ELEVENLABS_API_KEY}` for env var.
 * - `model` - Model name (after @). Defaults to "eleven_multilingual_v2" if omitted.
 *
 * Legacy format (still supported):
 * ```
 * elevenlabs://?apiKey=xxx&model=yyy
 * ```
 *
 * ## Models
 *
 * - `eleven_multilingual_v2` - Highest quality, 32 languages, best for pre-recorded
 * - `eleven_flash_v2_5` - Ultra-low latency (~75ms), best for real-time/streaming
 * - `eleven_turbo_v2_5` - Fast, good balance of quality and speed
 *
 * ## Voice IDs
 *
 * Use [listVoices] to get available voices. Some well-known voice IDs:
 * - Pre-made voices: Use the voice ID from the ElevenLabs voice library
 * - Cloned voices: Use the voice ID from your voice cloning
 *
 * ## Pricing (approximate, as of 2024)
 *
 * - ~$0.30 per 1,000 characters (Starter plan)
 * - Volume discounts available
 * - Free tier: 10,000 characters/month
 *
 * @see TextToSpeechService
 */
public class ElevenLabsTextToSpeechService(
    override val name: String,
    override val context: SettingContext,
    private val apiKey: String,
    private val defaultModel: String = "eleven_multilingual_v2"
) : TextToSpeechService {

    private val baseUrl = "https://api.elevenlabs.io/v1"

    private val client = com.lightningkite.services.http.client.config {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    override suspend fun listVoices(language: String?): List<VoiceInfo> {
        val response = client.get("$baseUrl/voices") {
            header("xi-api-key", apiKey)
        }

        if (!response.status.isSuccess()) {
            val error = response.bodyAsText()
            logger.error { "[$name] Failed to list voices: $error" }
            throw TextToSpeechException("Failed to list voices: $error")
        }

        val body = response.bodyAsText()
        logger.debug { "[$name] Voices response length: ${body.length}" }
        val voices = parseVoicesResponse(body, language)
        logger.debug { "[$name] Parsed ${voices.size} voices" }
        return voices
    }

    override suspend fun synthesize(
        text: String,
        voice: TtsVoiceConfig,
        options: TtsSynthesisOptions
    ): TypedData {
        val voiceId = voice.voiceId ?: getDefaultVoiceId(voice.language, voice.gender)
        val model = options.model ?: defaultModel
        val outputFormat = mapOutputFormat(options.outputFormat)

        logger.debug { "[$name] Synthesizing ${text.length} chars with voice=$voiceId, model=$model" }

        val response = client.post("$baseUrl/text-to-speech/$voiceId") {
            header("xi-api-key", apiKey)
            contentType(ContentType.Application.Json)
            parameter("output_format", outputFormat)

            setBody(buildSynthesisRequest(text, voice, model))
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
        val voiceId = voice.voiceId ?: getDefaultVoiceId(voice.language, voice.gender)
        val model = options.model ?: defaultModel
        val outputFormat = mapOutputFormat(options.outputFormat)

        logger.debug { "[$name] Streaming TTS for ${text.length} chars with voice=$voiceId" }

        val response = client.post("$baseUrl/text-to-speech/$voiceId/stream") {
            header("xi-api-key", apiKey)
            contentType(ContentType.Application.Json)
            parameter("output_format", outputFormat)

            setBody(buildSynthesisRequest(text, voice, model))
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
            // Use /models endpoint for health check - it doesn't require special permissions
            val response = client.get("$baseUrl/models") {
                header("xi-api-key", apiKey)
            }
            if (response.status.isSuccess()) {
                HealthStatus(HealthStatus.Level.OK, additionalMessage = "ElevenLabs TTS API accessible")
            } else {
                val body = response.bodyAsText()
                logger.warn { "[$name] Health check failed: ${response.status} - $body" }
                HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "ElevenLabs API returned ${response.status}: $body")
            }
        } catch (e: Exception) {
            logger.error(e) { "[$name] Health check error" }
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "ElevenLabs API error: ${e.message}")
        }
    }

    private fun buildSynthesisRequest(text: String, voice: TtsVoiceConfig, model: String): String {
        // Build JSON request body
        return buildString {
            append("{")
            append("\"text\":\"${escapeJson(text)}\",")
            append("\"model_id\":\"$model\",")
            append("\"voice_settings\":{")
            append("\"stability\":${voice.stability},")
            append("\"similarity_boost\":${voice.similarityBoost},")
            append("\"style\":${voice.style},")
            append("\"use_speaker_boost\":${voice.speakerBoost}")
            append("}")
            append("}")
        }
    }

    private fun getDefaultVoiceId(language: String, gender: TtsGender): String {
        // ElevenLabs default voices - these are common pre-made voices
        return when {
            language.startsWith("en") && gender == TtsGender.FEMALE -> "21m00Tcm4TlvDq8ikWAM"  // Rachel
            language.startsWith("en") && gender == TtsGender.MALE -> "pNInz6obpgDQGcFmaJgB"    // Adam
            language.startsWith("es") -> "AZnzlk1XvdvUeBnXmlld"  // Spanish voice
            language.startsWith("fr") -> "ODq5zmih8GrVes37Dizd"  // French voice
            language.startsWith("de") -> "zcAOhNBS3c14rBihAFp1"  // German voice
            else -> "21m00Tcm4TlvDq8ikWAM"  // Default to Rachel
        }
    }

    private fun mapOutputFormat(format: AudioFormat): String {
        return when (format) {
            AudioFormat.MP3_44100_128 -> "mp3_44100_128"
            AudioFormat.MP3_44100_192 -> "mp3_44100_192"
            AudioFormat.PCM_16000 -> "pcm_16000"
            AudioFormat.PCM_22050 -> "pcm_22050"
            AudioFormat.PCM_24000 -> "pcm_24000"
            AudioFormat.PCM_44100 -> "pcm_44100"
            AudioFormat.MULAW_8000 -> "ulaw_8000"
            AudioFormat.OGG_OPUS -> "opus_48000"  // ElevenLabs uses opus format name
            AudioFormat.WAV_44100 -> "pcm_44100"  // Return PCM, caller can wrap in WAV
        }
    }

    private fun mapFormatToMediaType(format: AudioFormat): MediaType {
        return when (format) {
            AudioFormat.MP3_44100_128, AudioFormat.MP3_44100_192 -> MediaType.Audio.MPEG
            AudioFormat.PCM_16000, AudioFormat.PCM_22050, AudioFormat.PCM_24000, AudioFormat.PCM_44100 ->
                MediaType("audio", "pcm")
            AudioFormat.MULAW_8000 -> MediaType("audio", "basic")
            AudioFormat.OGG_OPUS -> MediaType.Audio.OGG
            AudioFormat.WAV_44100 -> MediaType.Audio.WAV
        }
    }

    private fun parseVoicesResponse(json: String, languageFilter: String?): List<VoiceInfo> {
        val voices = mutableListOf<VoiceInfo>()

        // Find the start of the voices array
        val voicesStart = json.indexOf("\"voices\"")
        if (voicesStart == -1) return voices

        val arrayStart = json.indexOf('[', voicesStart)
        if (arrayStart == -1) return voices

        // Parse each voice object manually by tracking brace depth
        var depth = 0
        var objectStart = -1
        var i = arrayStart + 1

        while (i < json.length) {
            when (json[i]) {
                '{' -> {
                    if (depth == 0) objectStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objectStart != -1) {
                        val voiceJson = json.substring(objectStart, i + 1)
                        parseVoiceObject(voiceJson, languageFilter)?.let { voices.add(it) }
                        objectStart = -1
                    }
                }
                ']' -> if (depth == 0) break
            }
            i++
        }

        return voices
    }

    private fun parseVoiceObject(voiceJson: String, languageFilter: String?): VoiceInfo? {
        // Extract voice_id
        val voiceIdMatch = Regex(""""voice_id"\s*:\s*"([^"]+)"""").find(voiceJson)
        val voiceId = voiceIdMatch?.groupValues?.get(1) ?: return null

        // Extract name
        val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(voiceJson)
        val name = nameMatch?.groupValues?.get(1) ?: return null

        // Extract labels object and parse it
        val labels = mutableMapOf<String, String>()
        val labelsStart = voiceJson.indexOf("\"labels\"")
        if (labelsStart != -1) {
            val labelsObjStart = voiceJson.indexOf('{', labelsStart)
            if (labelsObjStart != -1) {
                var braceDepth = 1
                var labelsObjEnd = labelsObjStart + 1
                while (labelsObjEnd < voiceJson.length && braceDepth > 0) {
                    when (voiceJson[labelsObjEnd]) {
                        '{' -> braceDepth++
                        '}' -> braceDepth--
                    }
                    labelsObjEnd++
                }
                val labelsJson = voiceJson.substring(labelsObjStart, labelsObjEnd)
                Regex(""""([^"]+)"\s*:\s*"([^"]+)"""").findAll(labelsJson).forEach { labelMatch ->
                    labels[labelMatch.groupValues[1]] = labelMatch.groupValues[2]
                }
            }
        }

        val gender = when (labels["gender"]?.lowercase()) {
            "male" -> TtsGender.MALE
            "female" -> TtsGender.FEMALE
            else -> TtsGender.NEUTRAL
        }

        // Try to determine language from labels
        val voiceLanguage = labels["language"] ?: labels["accent"] ?: "en-US"

        // Filter by language if specified
        if (languageFilter != null && !voiceLanguage.startsWith(languageFilter.substringBefore("-"))) {
            return null
        }

        return VoiceInfo(
            voiceId = voiceId,
            name = name,
            language = voiceLanguage,
            gender = gender,
            labels = labels
        )
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
            TextToSpeechService.Settings.register("elevenlabs") { name, url, context ->
                val (apiKey, model) = parseElevenLabsUrl(url, "eleven_multilingual_v2")
                ElevenLabsTextToSpeechService(name, context, apiKey, model)
            }
        }

        /**
         * Creates ElevenLabs TTS settings URL.
         *
         * @param apiKey ElevenLabs API key (or use env var reference like "\${ELEVENLABS_API_KEY}")
         * @param model Default model (optional)
         */
        public fun TextToSpeechService.Settings.Companion.elevenlabs(
            apiKey: String,
            model: String = "eleven_multilingual_v2"
        ): TextToSpeechService.Settings = TextToSpeechService.Settings("elevenlabs://$apiKey@$model")
    }
}

/**
 * Parses ElevenLabs URL in either standard or legacy format.
 *
 * Standard format: `elevenlabs://apiKey@model`
 * Legacy format: `elevenlabs://?apiKey=xxx&model=yyy`
 *
 * @return Pair of (apiKey, model)
 */
private fun parseElevenLabsUrl(url: String, defaultModel: String): Pair<String, String> {
    // Remove scheme prefix
    val withoutScheme = url.substringAfter("://")

    // Check for standard URI format: apiKey@model
    if (withoutScheme.contains("@") && !withoutScheme.startsWith("?")) {
        val apiKeyPart = withoutScheme.substringBefore("@")
        val modelPart = withoutScheme.substringAfter("@").substringBefore("?").ifEmpty { defaultModel }

        val apiKey = resolveEnvVars(apiKeyPart)
        if (apiKey.isBlank() || apiKey.startsWith("\${")) {
            throw IllegalArgumentException(
                "ElevenLabs API key required. " +
                    "Format: elevenlabs://apiKey@model or elevenlabs://\${ELEVENLABS_API_KEY}@model"
            )
        }

        return apiKey to modelPart
    }

    // Fall back to legacy query parameter format: ?apiKey=xxx&model=yyy
    val params = parseUrlParams(url)
    val apiKey = params["apiKey"]?.let(::resolveEnvVars)
        ?: System.getenv("ELEVENLABS_API_KEY")
        ?: throw IllegalArgumentException(
            "ElevenLabs API key required. " +
                "Format: elevenlabs://apiKey@model or set ELEVENLABS_API_KEY environment variable."
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
