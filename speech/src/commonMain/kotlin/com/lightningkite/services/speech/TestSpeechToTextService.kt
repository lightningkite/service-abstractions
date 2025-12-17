package com.lightningkite.services.speech

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.TypedData
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Test implementation of [SpeechToTextService] that returns mock transcriptions.
 *
 * Use this for unit tests and development. Transcription operations return
 * configurable mock results.
 *
 * All transcription requests are recorded in [transcriptionHistory] for verification.
 *
 * ```kotlin
 * val stt = TestSpeechToTextService("test-stt", context)
 * stt.mockTranscriptionText = "Hello world from test"
 *
 * val result = stt.transcribe(audioData)
 *
 * assertEquals("Hello world from test", result.text)
 * assertEquals(1, stt.transcriptionHistory.size)
 * ```
 */
public class TestSpeechToTextService(
    override val name: String,
    override val context: SettingContext
) : SpeechToTextService {

    /**
     * History of all transcription requests for test verification.
     */
    public val transcriptionHistory: MutableList<TranscriptionRequest> = mutableListOf()

    /**
     * Mock transcription text to return.
     */
    public var mockTranscriptionText: String = "This is a test transcription."

    /**
     * Mock language to return.
     */
    public var mockLanguage: String = "en-US"

    /**
     * Mock language confidence to return.
     */
    public var mockLanguageConfidence: Float = 0.98f

    /**
     * Whether to include mock word timestamps.
     */
    public var includeMockWords: Boolean = true

    /**
     * Mock speaker segments to return when diarization is requested.
     */
    public var mockSpeakers: List<SpeakerSegment> = listOf(
        SpeakerSegment(
            speakerId = "speaker_0",
            startTime = 0.seconds,
            endTime = 3.seconds,
            text = "This is a test transcription."
        )
    )

    override suspend fun transcribe(
        audio: TypedData,
        options: TranscriptionOptions
    ): TranscriptionResult {
        transcriptionHistory.add(TranscriptionRequest(audio.data.size?.toInt(), null, options))
        return buildMockResult(options)
    }

    override suspend fun transcribeUrl(
        audioUrl: String,
        options: TranscriptionOptions
    ): TranscriptionResult {
        transcriptionHistory.add(TranscriptionRequest(null, audioUrl, options))
        return buildMockResult(options)
    }

    private fun buildMockResult(options: TranscriptionOptions): TranscriptionResult {
        val words = if (includeMockWords && options.wordTimestamps) {
            mockTranscriptionText.split(" ").mapIndexed { index, word ->
                TranscribedWord(
                    text = word,
                    startTime = (index * 500).milliseconds,
                    endTime = ((index + 1) * 500).milliseconds,
                    confidence = 0.95f,
                    speakerId = if (options.speakerDiarization) "speaker_0" else null
                )
            }
        } else {
            emptyList()
        }

        return TranscriptionResult(
            text = mockTranscriptionText,
            language = options.language ?: mockLanguage,
            languageConfidence = mockLanguageConfidence,
            words = words,
            speakers = if (options.speakerDiarization) mockSpeakers else emptyList(),
            audioEvents = emptyList(),
            duration = 3.seconds
        )
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Test STT service")
    }

    /**
     * Clears transcription history for test isolation.
     */
    public fun clearHistory() {
        transcriptionHistory.clear()
    }

    /**
     * Record of a transcription request for test verification.
     */
    public data class TranscriptionRequest(
        val audioSize: Int?,
        val audioUrl: String?,
        val options: TranscriptionOptions
    )
}
