package com.lightningkite.services.speech

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.TypedData
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger("ConsoleSpeechToTextService")

/**
 * Console implementation of [SpeechToTextService] for development.
 *
 * Logs all STT requests to the console/logger and returns mock transcription.
 * Useful for development when you want to see what audio would be transcribed
 * without actually calling an STT provider.
 *
 * ```kotlin
 * val stt = ConsoleSpeechToTextService("dev-stt", context)
 * stt.transcribe(audioData)
 * // Logs: [dev-stt] STT transcribe: 12345 bytes (audio/mpeg), lang=null
 * ```
 */
public class ConsoleSpeechToTextService(
    override val name: String,
    override val context: SettingContext
) : SpeechToTextService {

    /**
     * Mock transcription text to return.
     */
    public var mockTranscriptionText: String = "[Console STT mock transcription]"

    override suspend fun transcribe(
        audio: TypedData,
        options: TranscriptionOptions
    ): TranscriptionResult {
        logger.info {
            "[$name] STT transcribe: ${audio.data.size} bytes (${audio.mediaType}), " +
                    "lang=${options.language}, diarization=${options.speakerDiarization}"
        }
        return TranscriptionResult(
            text = mockTranscriptionText,
            language = options.language ?: "en-US",
            languageConfidence = 0.99f,
            duration = 5.seconds
        )
    }

    override suspend fun transcribeUrl(
        audioUrl: String,
        options: TranscriptionOptions
    ): TranscriptionResult {
        logger.info {
            "[$name] STT transcribeUrl: $audioUrl, " +
                    "lang=${options.language}, diarization=${options.speakerDiarization}"
        }
        return TranscriptionResult(
            text = mockTranscriptionText,
            language = options.language ?: "en-US",
            languageConfidence = 0.99f,
            duration = 5.seconds
        )
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Console STT service")
    }
}
