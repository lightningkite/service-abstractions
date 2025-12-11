package com.lightningkite.services.speech.test

import com.lightningkite.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.speech.*
import com.lightningkite.services.test.runTestWithClock
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Abstract test suite for [SpeechToTextService] implementations.
 *
 * Extend this class and provide your STT service instance to run
 * the standard test suite against your implementation.
 *
 * You must also provide test audio data that contains speech for
 * the transcription tests to work properly.
 *
 * ```kotlin
 * class ElevenLabsSttTests : SpeechToTextServiceTests() {
 *     override val sttService: SpeechToTextService by lazy {
 *         ElevenLabsSpeechToTextService("test", context, apiKey)
 *     }
 *
 *     override val testAudioData: TypedData by lazy {
 *         // Load test audio file
 *         TypedData(loadTestAudio(), MediaType.Audio.Mpeg)
 *     }
 *
 *     override val expectedTranscriptionContains: String = "hello"
 * }
 * ```
 */
public abstract class SpeechToTextServiceTests {

    /**
     * The STT service instance to test.
     */
    public abstract val sttService: SpeechToTextService

    /**
     * Test audio data containing speech to transcribe.
     * This should be a short audio clip (5-15 seconds) with clear speech.
     */
    public abstract val testAudioData: TypedData

    /**
     * A word or phrase that should appear in the transcription of [testAudioData].
     * Used to verify transcription accuracy.
     */
    public abstract val expectedTranscriptionContains: String

    public open fun runSuspendingTest(body: suspend CoroutineScope.() -> Unit) = runTestWithClock { body() }

    @Test
    public fun transcribeReturnsText() = runSuspendingTest {
        val result = sttService.transcribe(
            audio = testAudioData,
            options = TranscriptionOptions()
        )

        assertTrue(result.text.isNotEmpty(), "transcribe should return non-empty text")
    }

    @Test
    public fun transcribeContainsExpectedText() = runSuspendingTest {
        val result = sttService.transcribe(
            audio = testAudioData,
            options = TranscriptionOptions()
        )

        assertTrue(
            result.text.lowercase().contains(expectedTranscriptionContains.lowercase()),
            "transcription should contain '$expectedTranscriptionContains', but was: ${result.text}"
        )
    }

    @Test
    public fun transcribeWithWordTimestamps() = runSuspendingTest {
        val result = sttService.transcribe(
            audio = testAudioData,
            options = TranscriptionOptions(wordTimestamps = true)
        )

        assertTrue(result.text.isNotEmpty(), "transcription text should not be empty")

        // Word timestamps are optional - some providers may not support them
        // Just verify no exception is thrown
    }

    @Test
    public fun transcribeWithLanguageHint() = runSuspendingTest {
        val result = sttService.transcribe(
            audio = testAudioData,
            options = TranscriptionOptions(language = "en-US")
        )

        assertTrue(result.text.isNotEmpty(), "transcription with language hint should work")
    }

    @Test
    public fun transcribeDetectsLanguage() = runSuspendingTest {
        val result = sttService.transcribe(
            audio = testAudioData,
            options = TranscriptionOptions(language = null)  // Auto-detect
        )

        // Language detection is optional - just verify transcription works
        assertTrue(result.text.isNotEmpty(), "transcription with auto language detection should work")
    }

    @Test
    public fun transcribeWithSpeakerDiarization() = runSuspendingTest {
        val result = sttService.transcribe(
            audio = testAudioData,
            options = TranscriptionOptions(speakerDiarization = true)
        )

        // Diarization is optional - just verify no exception
        assertTrue(result.text.isNotEmpty(), "transcription with diarization enabled should work")
    }

    @Test
    public fun healthCheckReturnsStatus() = runSuspendingTest {
        val status = sttService.healthCheck()
        assertNotNull(status, "healthCheck should return a status")
        assertNotNull(status.level, "health status should have a level")
    }
}

/**
 * Simple test implementation that doesn't require real audio.
 * Use this to verify your test setup works before integrating real audio.
 */
public abstract class SimpleSpeechToTextServiceTests {

    public abstract val sttService: SpeechToTextService

    public open fun runSuspendingTest(body: suspend CoroutineScope.() -> Unit) = runTestWithClock { body() }

    @Test
    public fun healthCheckWorks() = runSuspendingTest {
        val status = sttService.healthCheck()
        assertNotNull(status)
    }
}
