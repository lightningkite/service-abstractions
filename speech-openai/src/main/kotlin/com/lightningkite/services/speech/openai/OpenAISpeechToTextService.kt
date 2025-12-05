package com.lightningkite.services.speech.openai

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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger("OpenAISpeechToTextService")

/**
 * OpenAI Whisper Speech-to-Text implementation.
 *
 * OpenAI Whisper is a general-purpose speech recognition model that supports
 * transcription in multiple languages and translation to English.
 *
 * ## URL Format
 *
 * ```
 * openai-stt://?apiKey=xxx&model=whisper-1
 * ```
 *
 * Query parameters:
 * - `apiKey` - OpenAI API key (required, or set OPENAI_API_KEY env var)
 * - `model` - Model to use (optional, defaults to "whisper-1")
 *
 * ## Models
 *
 * - `whisper-1` - General-purpose speech recognition
 *
 * ## Features
 *
 * - 57+ language support
 * - Automatic language detection
 * - Word-level timestamps (verbose mode)
 * - Translation to English
 * - File size limit: 25MB
 *
 * ## Pricing (approximate, as of 2024)
 *
 * - $0.006 per minute of audio
 *
 * @see SpeechToTextService
 */
public class OpenAISpeechToTextService(
    override val name: String,
    override val context: SettingContext,
    private val apiKey: String,
    private val defaultModel: String = "whisper-1"
) : SpeechToTextService {

    private val baseUrl = "https://api.openai.com/v1"

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

        // Check file size limit (25MB for Whisper)
        if (audioBytes.size > 25 * 1024 * 1024) {
            throw SpeechToTextException("Audio file too large. OpenAI Whisper has a 25MB limit.")
        }

        val responseFormat = if (options.wordTimestamps) "verbose_json" else "json"

        val response = client.submitFormWithBinaryData(
            url = "$baseUrl/audio/transcriptions",
            formData = formData {
                append("file", audioBytes, Headers.build {
                    append(HttpHeaders.ContentType, audio.mediaType.toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"audio.${getExtension(audio.mediaType)}\"")
                })
                append("model", model)
                append("response_format", responseFormat)
                options.language?.let { append("language", it.substringBefore("-")) }
                options.prompt?.let { append("prompt", it) }
                if (options.wordTimestamps) {
                    append("timestamp_granularities[]", "word")
                }
            }
        ) {
            header("Authorization", "Bearer $apiKey")
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
        // OpenAI Whisper doesn't support URL-based transcription directly
        // We would need to download the file first
        throw SpeechToTextException(
            "OpenAI Whisper does not support URL-based transcription. " +
                    "Download the audio file and use transcribe() instead."
        )
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            val response = client.get("$baseUrl/models") {
                header("Authorization", "Bearer $apiKey")
            }
            if (response.status.isSuccess()) {
                HealthStatus(HealthStatus.Level.OK, additionalMessage = "OpenAI STT API accessible")
            } else {
                HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "OpenAI API returned ${response.status}")
            }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "OpenAI API error: ${e.message}")
        }
    }

    private fun parseTranscriptionResponse(json: String, options: TranscriptionOptions): TranscriptionResult {
        // Parse text
        val text = Regex(""""text"\s*:\s*"([^"]*(?:\\.[^"]*)*)"""")
            .find(json)?.groupValues?.get(1)?.unescapeJson() ?: ""

        // Parse language
        val language = Regex(""""language"\s*:\s*"([^"]+)"""")
            .find(json)?.groupValues?.get(1)

        // Parse duration
        val durationSeconds = Regex(""""duration"\s*:\s*([0-9.]+)""")
            .find(json)?.groupValues?.get(1)?.toDoubleOrNull()
        val duration = durationSeconds?.seconds

        // Parse words if verbose_json response
        val words = if (options.wordTimestamps) {
            parseWords(json)
        } else {
            emptyList()
        }

        return TranscriptionResult(
            text = text,
            language = language,
            languageConfidence = null,  // Whisper doesn't provide confidence
            words = words,
            speakers = emptyList(),  // Whisper doesn't support diarization
            audioEvents = emptyList(),  // Whisper doesn't detect audio events
            duration = duration
        )
    }

    private fun parseWords(json: String): List<TranscribedWord> {
        val words = mutableListOf<TranscribedWord>()

        // Look for words array in verbose_json response
        val wordsArrayMatch = Regex(""""words"\s*:\s*\[([^\]]*)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(json) ?: return words

        val wordsJson = wordsArrayMatch.groupValues[1]

        // Parse each word object
        val wordPattern = Regex(
            """\{\s*"word"\s*:\s*"([^"]*)"[^}]*"start"\s*:\s*([0-9.]+)[^}]*"end"\s*:\s*([0-9.]+)[^}]*\}""",
            RegexOption.DOT_MATCHES_ALL
        )

        wordPattern.findAll(wordsJson).forEach { match ->
            val wordText = match.groupValues[1].unescapeJson()
            val startSeconds = match.groupValues[2].toDoubleOrNull() ?: 0.0
            val endSeconds = match.groupValues[3].toDoubleOrNull() ?: 0.0

            words.add(
                TranscribedWord(
                    text = wordText,
                    startTime = (startSeconds * 1000).toLong().milliseconds,
                    endTime = (endSeconds * 1000).toLong().milliseconds,
                    confidence = null,  // Whisper doesn't provide per-word confidence
                    speakerId = null
                )
            )
        }

        return words
    }

    private fun getExtension(mediaType: com.lightningkite.MediaType): String {
        return when {
            mediaType.toString().contains("mpeg") -> "mp3"
            mediaType.toString().contains("wav") -> "wav"
            mediaType.toString().contains("webm") -> "webm"
            mediaType.toString().contains("ogg") -> "ogg"
            mediaType.toString().contains("mp4") || mediaType.toString().contains("m4a") -> "m4a"
            mediaType.toString().contains("flac") -> "flac"
            else -> "mp3"
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
            SpeechToTextService.Settings.register("openai-stt") { name, url, context ->
                val params = parseUrlParams(url)
                val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                    ?: System.getenv("OPENAI_API_KEY")
                    ?: throw IllegalArgumentException("OpenAI API key required. Provide via URL parameter or OPENAI_API_KEY environment variable.")
                val model = params["model"] ?: "whisper-1"

                OpenAISpeechToTextService(name, context, apiKey, model)
            }
        }

        /**
         * Creates OpenAI STT (Whisper) settings URL.
         *
         * @param apiKey OpenAI API key (or use env var reference like "\${OPENAI_API_KEY}")
         * @param model Model to use (optional)
         */
        public fun SpeechToTextService.Settings.Companion.openai(
            apiKey: String,
            model: String = "whisper-1"
        ): SpeechToTextService.Settings = SpeechToTextService.Settings("openai-stt://?apiKey=$apiKey&model=$model")
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
