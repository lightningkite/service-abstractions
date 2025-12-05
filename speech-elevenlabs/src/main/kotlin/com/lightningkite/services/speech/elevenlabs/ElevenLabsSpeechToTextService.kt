package com.lightningkite.services.speech.elevenlabs

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.speech.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger("ElevenLabsSpeechToTextService")

/**
 * ElevenLabs Speech-to-Text (Scribe) implementation.
 *
 * ElevenLabs Scribe provides high-accuracy transcription with support for
 * 99 languages, word-level timestamps, and speaker diarization.
 *
 * ## URL Format
 *
 * ```
 * elevenlabs-stt://?apiKey=xxx&model=scribe_v1
 * ```
 *
 * Query parameters:
 * - `apiKey` - ElevenLabs API key (required, or set ELEVENLABS_API_KEY env var)
 * - `model` - Model to use (optional, defaults to "scribe_v1")
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
    private val defaultModel: String = "scribe_v1"
) : SpeechToTextService {

    private val baseUrl = "https://api.elevenlabs.io/v1"

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
        options: TranscriptionOptions
    ): TranscriptionResult {
        val model = options.model ?: defaultModel

        val audioBytes = audio.data.bytes()
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

        return parseTranscriptionResponse(body, options)
    }

    override suspend fun transcribeUrl(
        audioUrl: String,
        options: TranscriptionOptions
    ): TranscriptionResult {
        val model = options.model ?: defaultModel

        logger.debug { "[$name] Transcribing from URL: $audioUrl with model=$model" }

        val response = client.post("$baseUrl/speech-to-text") {
            header("xi-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildUrlTranscriptionRequest(audioUrl, model, options))
        }

        if (!response.status.isSuccess()) {
            val error = response.bodyAsText()
            logger.error { "[$name] STT failed: $error" }
            throw SpeechToTextException("Transcription failed: $error")
        }

        val body = response.bodyAsText()
        logger.debug { "[$name] Transcription complete" }

        return parseTranscriptionResponse(body, options)
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
                HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "ElevenLabs API returned ${response.status}: $body")
            }
        } catch (e: Exception) {
            logger.error(e) { "[$name] Health check error" }
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "ElevenLabs API error: ${e.message}")
        }
    }

    private fun buildUrlTranscriptionRequest(
        audioUrl: String,
        model: String,
        options: TranscriptionOptions
    ): String {
        return buildString {
            append("{")
            append("\"cloud_storage_url\":\"$audioUrl\",")
            append("\"model_id\":\"$model\"")
            options.language?.let { append(",\"language_code\":\"$it\"") }
            if (options.speakerDiarization) {
                append(",\"diarize\":true")
                options.maxSpeakers?.let { append(",\"num_speakers\":$it") }
            }
            if (options.wordTimestamps) {
                append(",\"timestamps_granularity\":\"word\"")
            }
            append("}")
        }
    }

    private fun parseTranscriptionResponse(json: String, options: TranscriptionOptions): TranscriptionResult {
        // Parse text
        val text = Regex(""""text"\s*:\s*"([^"]*(?:\\.[^"]*)*)"""")
            .find(json)?.groupValues?.get(1)?.unescapeJson() ?: ""

        // Parse language
        val language = Regex(""""language_code"\s*:\s*"([^"]+)"""")
            .find(json)?.groupValues?.get(1)

        val languageConfidence = Regex(""""language_probability"\s*:\s*([0-9.]+)""")
            .find(json)?.groupValues?.get(1)?.toFloatOrNull()

        // Parse words if present
        val words = if (options.wordTimestamps) {
            parseWords(json, options.speakerDiarization)
        } else {
            emptyList()
        }

        // Parse speakers if diarization was enabled
        val speakers = if (options.speakerDiarization) {
            parseSpeakers(json)
        } else {
            emptyList()
        }

        // Parse audio events
        val audioEvents = if (options.audioEvents) {
            parseAudioEvents(json)
        } else {
            emptyList()
        }

        return TranscriptionResult(
            text = text,
            language = language,
            languageConfidence = languageConfidence,
            words = words,
            speakers = speakers,
            audioEvents = audioEvents,
            duration = null  // ElevenLabs returns this in alignment info
        )
    }

    private fun parseWords(json: String, includeSpeaker: Boolean): List<TranscribedWord> {
        val words = mutableListOf<TranscribedWord>()

        // Look for words array in alignment or words field
        val wordsArrayMatch = Regex(""""words"\s*:\s*\[([^\]]*)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(json) ?: return words

        val wordsJson = wordsArrayMatch.groupValues[1]

        // Parse each word object
        val wordPattern = Regex(
            """\{\s*"text"\s*:\s*"([^"]*)"[^}]*"start"\s*:\s*([0-9.]+)[^}]*"end"\s*:\s*([0-9.]+)[^}]*\}""",
            RegexOption.DOT_MATCHES_ALL
        )

        wordPattern.findAll(wordsJson).forEach { match ->
            val wordText = match.groupValues[1].unescapeJson()
            val startMs = (match.groupValues[2].toDoubleOrNull() ?: 0.0) * 1000
            val endMs = (match.groupValues[3].toDoubleOrNull() ?: 0.0) * 1000

            // Try to extract speaker_id if present
            val speakerId = if (includeSpeaker) {
                Regex(""""speaker_id"\s*:\s*"([^"]+)"""").find(match.value)?.groupValues?.get(1)
            } else null

            words.add(
                TranscribedWord(
                    text = wordText,
                    startTime = startMs.toLong().milliseconds,
                    endTime = endMs.toLong().milliseconds,
                    confidence = null,  // ElevenLabs doesn't provide per-word confidence
                    speakerId = speakerId
                )
            )
        }

        return words
    }

    private fun parseSpeakers(json: String): List<SpeakerSegment> {
        val segments = mutableListOf<SpeakerSegment>()

        // Look for speaker segments - this varies by ElevenLabs response format
        val speakersMatch = Regex(""""speakers"\s*:\s*\[([^\]]*)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(json) ?: return segments

        val speakersJson = speakersMatch.groupValues[1]

        val segmentPattern = Regex(
            """\{\s*"speaker"\s*:\s*"([^"]+)"[^}]*"start"\s*:\s*([0-9.]+)[^}]*"end"\s*:\s*([0-9.]+)[^}]*"text"\s*:\s*"([^"]*)"\s*\}""",
            RegexOption.DOT_MATCHES_ALL
        )

        segmentPattern.findAll(speakersJson).forEach { match ->
            segments.add(
                SpeakerSegment(
                    speakerId = match.groupValues[1],
                    startTime = (match.groupValues[2].toDoubleOrNull()?.times(1000) ?: 0.0).toLong().milliseconds,
                    endTime = (match.groupValues[3].toDoubleOrNull()?.times(1000) ?: 0.0).toLong().milliseconds,
                    text = match.groupValues[4].unescapeJson()
                )
            )
        }

        return segments
    }

    private fun parseAudioEvents(json: String): List<AudioEvent> {
        val events = mutableListOf<AudioEvent>()

        // Look for audio_events array
        val eventsMatch = Regex(""""audio_events"\s*:\s*\[([^\]]*)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(json) ?: return events

        val eventsJson = eventsMatch.groupValues[1]

        val eventPattern = Regex(
            """\{\s*"type"\s*:\s*"([^"]+)"[^}]*"start"\s*:\s*([0-9.]+)[^}]*"end"\s*:\s*([0-9.]+)[^}]*\}"""
        )

        eventPattern.findAll(eventsJson).forEach { match ->
            val type = when (match.groupValues[1].lowercase()) {
                "laughter" -> AudioEventType.LAUGHTER
                "applause" -> AudioEventType.APPLAUSE
                "music" -> AudioEventType.MUSIC
                "silence" -> AudioEventType.SILENCE
                "noise" -> AudioEventType.NOISE
                else -> AudioEventType.OTHER
            }

            events.add(
                AudioEvent(
                    type = type,
                    startTime = (match.groupValues[2].toDoubleOrNull()?.times(1000) ?: 0.0).toLong().milliseconds,
                    endTime = (match.groupValues[3].toDoubleOrNull()?.times(1000) ?: 0.0).toLong().milliseconds
                )
            )
        }

        return events
    }

    private fun getExtension(mediaType: com.lightningkite.MediaType): String {
        return when {
            mediaType.toString().contains("mpeg") -> "mp3"
            mediaType.toString().contains("wav") -> "wav"
            mediaType.toString().contains("webm") -> "webm"
            mediaType.toString().contains("ogg") -> "ogg"
            mediaType.toString().contains("mp4") || mediaType.toString().contains("m4a") -> "m4a"
            else -> "audio"
        }
    }

    private fun String.unescapeJson(): String {
        return this
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    public companion object {
        init {
            SpeechToTextService.Settings.register("elevenlabs-stt") { name, url, context ->
                val params = parseUrlParams(url)
                val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                    ?: System.getenv("ELEVENLABS_API_KEY")
                    ?: throw IllegalArgumentException("ElevenLabs API key required. Provide via URL parameter or ELEVENLABS_API_KEY environment variable.")
                val model = params["model"] ?: "scribe_v1"

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
            model: String = "scribe_v1"
        ): SpeechToTextService.Settings = SpeechToTextService.Settings("elevenlabs-stt://?apiKey=$apiKey&model=$model")
    }
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
