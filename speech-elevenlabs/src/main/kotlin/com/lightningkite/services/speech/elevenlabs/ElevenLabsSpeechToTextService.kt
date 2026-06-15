package com.lightningkite.services.speech.elevenlabs

import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.*
import com.lightningkite.services.telemetry.telemetryTrace
import com.lightningkite.services.speech.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger("ElevenLabsSpeechToTextService")

// Minimal response models — only fields actually used.

@Serializable
private data class ElevenLabsTranscriptionResponse(
    val text: String = "",
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("language_probability") val languageProbability: Float? = null,
    val words: List<ElevenLabsWord>? = null,
    val speakers: List<ElevenLabsSpeakerSegment>? = null,
    @SerialName("audio_events") val audioEvents: List<ElevenLabsAudioEvent>? = null,
)

@Serializable
private data class ElevenLabsWord(
    val text: String = "",
    val start: Double = 0.0,
    val end: Double = 0.0,
    @SerialName("speaker_id") val speakerId: String? = null,
)

@Serializable
private data class ElevenLabsSpeakerSegment(
    val speaker: String = "",
    val start: Double = 0.0,
    val end: Double = 0.0,
    val text: String = "",
)

@Serializable
private data class ElevenLabsAudioEvent(
    val type: String = "",
    val start: Double = 0.0,
    val end: Double = 0.0,
)

// Minimal request model for URL-based transcription.
@Serializable
private data class ElevenLabsUrlTranscriptionRequest(
    @SerialName("cloud_storage_url") val cloudStorageUrl: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("language_code") val languageCode: String? = null,
    val diarize: Boolean? = null,
    @SerialName("num_speakers") val numSpeakers: Int? = null,
    @SerialName("timestamps_granularity") val timestampsGranularity: String? = null,
)

/**
 * ElevenLabs Speech-to-Text (Scribe) implementation.
 *
 * ElevenLabs Scribe provides high-accuracy transcription with support for
 * 99 languages, word-level timestamps, and speaker diarization.
 *
 * ## URL Format
 *
 * Uses standard URI format with auth before the host/model:
 *
 * ```
 * elevenlabs-stt://apiKey@model
 * elevenlabs-stt://apiKey@scribe_v1
 * elevenlabs-stt://xi-abc123@scribe_v1
 * ```
 *
 * Components:
 * - `apiKey` - ElevenLabs API key (before @). Use `${ELEVENLABS_API_KEY}` for env var.
 * - `model` - Model name (after @). Defaults to "scribe_v1" if omitted.
 *
 * Legacy format (still supported):
 * ```
 * elevenlabs-stt://?apiKey=xxx&model=yyy
 * ```
 *
 * ## Models
 *
 * - `scribe_v1` - Batch transcription, 99 languages, highest accuracy
 *
 * ## Features
 *
 * - 99 language support with automatic detection
 * - Word-level timestamps
 * - Speaker diarization (up to 32 speakers)
 * - Audio event detection (laughter, applause, etc.)
 * - Files up to 3GB and 10 hours duration
 *
 * ## Pricing (approximate, as of 2024)
 *
 * - ~$0.10 per minute of audio
 * - Free tier: 30 minutes/month
 *
 * @see SpeechToTextService
 */
public class ElevenLabsSpeechToTextService(
    override val name: String,
    override val context: SettingContext,
    private val apiKey: String,
    private val defaultModel: String = "scribe_v1",
) : SpeechToTextService {

    private val baseUrl = "https://api.elevenlabs.io/v1"

    private val responseJson = Json { ignoreUnknownKeys = true }
    private val requestJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val client = com.lightningkite.services.http.client.config {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    override suspend fun transcribe(
        audio: TypedData,
        options: TranscriptionOptions,
    ): TranscriptionResult {
        val model = options.model ?: defaultModel
        val audioBytes = audio.data.bytes()

        return telemetryTrace(
            "transcribe",
            attributes = TelemetryAttributes(
                mapOf(
                    "ai.provider" to "elevenlabs",
                    "ai.model" to model,
                    "audio.size_bytes" to audioBytes.size.toLong(),
                )
            )
        ) {
            logger.debug { "[$name] Transcribing ${audioBytes.size} bytes with model=$model" }

            val response = client.submitFormWithBinaryData(
                url = "$baseUrl/speech-to-text",
                formData = formData {
                    append("file", audioBytes, Headers.build {
                        append(HttpHeaders.ContentType, audio.mediaType.toString())
                        append(HttpHeaders.ContentDisposition, "filename=\"audio.${getExtension(audio.mediaType)}\"")
                    })
                    append("model_id", model)
                    options.language?.let { append("language_code", it) }
                    if (options.speakerDiarization) {
                        append("diarize", "true")
                        options.maxSpeakers?.let { append("num_speakers", it.toString()) }
                    }
                    if (options.wordTimestamps) {
                        append("timestamps_granularity", "word")
                    }
                }
            ) {
                header("xi-api-key", apiKey)
            }

            if (!response.status.isSuccess()) {
                val error = response.bodyAsText()
                logger.error { "[$name] STT failed: $error" }
                throw SpeechToTextException("Transcription failed: $error")
            }

            val body = response.bodyAsText()
            logger.debug { "[$name] Transcription complete" }

            parseTranscriptionResponse(body, options)
        }
    }

    override suspend fun transcribeUrl(
        audioUrl: String,
        options: TranscriptionOptions,
    ): TranscriptionResult {
        val model = options.model ?: defaultModel

        return telemetryTrace(
            "transcribe_url",
            attributes = TelemetryAttributes(
                mapOf(
                    "ai.provider" to "elevenlabs",
                    "ai.model" to model,
                )
            )
        ) {
            logger.debug { "[$name] Transcribing from URL: $audioUrl with model=$model" }

            val requestBody = ElevenLabsUrlTranscriptionRequest(
                cloudStorageUrl = audioUrl,
                modelId = model,
                languageCode = options.language,
                diarize = if (options.speakerDiarization) true else null,
                numSpeakers = options.maxSpeakers,
                timestampsGranularity = if (options.wordTimestamps) "word" else null,
            )

            val response = client.post("$baseUrl/speech-to-text") {
                header("xi-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(requestJson.encodeToString(requestBody))
            }

            if (!response.status.isSuccess()) {
                val error = response.bodyAsText()
                logger.error { "[$name] STT failed: $error" }
                throw SpeechToTextException("Transcription failed: $error")
            }

            val body = response.bodyAsText()
            logger.debug { "[$name] Transcription complete" }

            parseTranscriptionResponse(body, options)
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            // Use /models endpoint for health check - it doesn't require special permissions
            val response = client.get("$baseUrl/models") {
                header("xi-api-key", apiKey)
            }
            if (response.status.isSuccess()) {
                HealthStatus(HealthStatus.Level.OK, additionalMessage = "ElevenLabs STT API accessible")
            } else {
                val body = response.bodyAsText()
                logger.warn { "[$name] Health check failed: ${response.status} - $body" }
                HealthStatus(
                    HealthStatus.Level.ERROR,
                    additionalMessage = "ElevenLabs API returned ${response.status}: $body"
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "[$name] Health check error" }
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "ElevenLabs API error: ${e.message}")
        }
    }

    private fun parseTranscriptionResponse(body: String, options: TranscriptionOptions): TranscriptionResult {
        val parsed = responseJson.decodeFromString<ElevenLabsTranscriptionResponse>(body)

        val words = if (options.wordTimestamps) {
            parsed.words?.map { w ->
                TranscribedWord(
                    text = w.text,
                    startTime = (w.start * 1000).toLong().milliseconds,
                    endTime = (w.end * 1000).toLong().milliseconds,
                    confidence = null,  // ElevenLabs doesn't provide per-word confidence
                    speakerId = w.speakerId,
                )
            } ?: emptyList()
        } else {
            emptyList()
        }

        val speakers = if (options.speakerDiarization) {
            parsed.speakers?.map { s ->
                SpeakerSegment(
                    speakerId = s.speaker,
                    startTime = (s.start * 1000).toLong().milliseconds,
                    endTime = (s.end * 1000).toLong().milliseconds,
                    text = s.text,
                )
            } ?: emptyList()
        } else {
            emptyList()
        }

        val audioEvents = if (options.audioEvents) {
            parsed.audioEvents?.map { e ->
                AudioEvent(
                    type = when (e.type.lowercase()) {
                        "laughter" -> AudioEventType.LAUGHTER
                        "applause" -> AudioEventType.APPLAUSE
                        "music" -> AudioEventType.MUSIC
                        "silence" -> AudioEventType.SILENCE
                        "noise" -> AudioEventType.NOISE
                        else -> AudioEventType.OTHER
                    },
                    startTime = (e.start * 1000).toLong().milliseconds,
                    endTime = (e.end * 1000).toLong().milliseconds,
                )
            } ?: emptyList()
        } else {
            emptyList()
        }

        return TranscriptionResult(
            text = parsed.text,
            language = parsed.languageCode,
            languageConfidence = parsed.languageProbability,
            words = words,
            speakers = speakers,
            audioEvents = audioEvents,
            duration = null,  // ElevenLabs returns this in alignment info
        )
    }

    private fun getExtension(mediaType: MediaType): String {
        return when {
            mediaType.toString().contains("mpeg") -> "mp3"
            mediaType.toString().contains("wav") -> "wav"
            mediaType.toString().contains("webm") -> "webm"
            mediaType.toString().contains("ogg") -> "ogg"
            mediaType.toString().contains("mp4") || mediaType.toString().contains("m4a") -> "m4a"
            else -> "audio"
        }
    }

    public companion object {
        init {
            SpeechToTextService.Settings.register("elevenlabs") { name, url, context ->
                val (apiKey, model) = parseElevenLabsSttUrl(url)
                ElevenLabsSpeechToTextService(name, context, apiKey, model)
            }
        }

        /**
         * Creates ElevenLabs STT settings URL.
         *
         * @param apiKey ElevenLabs API key (or use env var reference like "\${ELEVENLABS_API_KEY}")
         * @param model Model to use (optional)
         */
        public fun SpeechToTextService.Settings.Companion.elevenlabs(
            apiKey: String,
            model: String = "scribe_v1",
        ): SpeechToTextService.Settings = SpeechToTextService.Settings("elevenlabs://$apiKey@$model")
    }
}

/**
 * Parses ElevenLabs STT URL in either standard or legacy format.
 *
 * Standard format: `elevenlabs-stt://apiKey@model`
 * Legacy format: `elevenlabs-stt://?apiKey=xxx&model=yyy`
 *
 * @return Pair of (apiKey, model)
 */
private fun parseElevenLabsSttUrl(url: String): Pair<String, String> {
    val defaultModel = "scribe_v1"

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
                        "Format: elevenlabs-stt://apiKey@model or elevenlabs-stt://\${ELEVENLABS_API_KEY}@model"
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
                    "Format: elevenlabs-stt://apiKey@model or set ELEVENLABS_API_KEY environment variable."
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
