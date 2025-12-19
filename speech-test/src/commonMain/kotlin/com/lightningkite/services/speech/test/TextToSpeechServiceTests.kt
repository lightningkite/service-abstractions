package com.lightningkite.services.speech.test

import com.lightningkite.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.speech.*
import com.lightningkite.services.test.runTestWithClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Abstract test suite for [TextToSpeechService] implementations.
 *
 * Extend this class and provide your TTS service instance to run
 * the standard test suite against your implementation.
 *
 * ```kotlin
 * class ElevenLabsTtsTests : TextToSpeechServiceTests() {
 *     override val ttsService: TextToSpeechService by lazy {
 *         ElevenLabsTextToSpeechService("test", context, apiKey)
 *     }
 * }
 * ```
 */
public abstract class TextToSpeechServiceTests {

    /**
     * The TTS service instance to test.
     */
    public abstract val ttsService: TextToSpeechService

    public open fun runSuspendingTest(body: suspend CoroutineScope.() -> Unit) = runTestWithClock { body() }

    @Test
    public fun listVoicesReturnsNonEmpty() = runSuspendingTest {
        val voices = ttsService.listVoices()
        assertTrue(voices.isNotEmpty(), "listVoices should return at least one voice")
    }

    @Test
    public fun listVoicesWithLanguageFilter() = runSuspendingTest {
        val voices = ttsService.listVoices(language = "en")
        // Should either return voices or empty list (if language not supported)
        // This test just verifies no exception is thrown
        assertNotNull(voices)
    }

    @Test
    public fun synthesizeReturnsAudioData() = runSuspendingTest {
        val result = ttsService.synthesize(
            text = "Hello, this is a test.",
            voice = TtsVoiceConfig(language = "en-US"),
            options = TtsSynthesisOptions()
        )

        assertTrue(result.data.size!! > 0, "synthesize should return non-empty audio data")
        assertNotNull(result.mediaType, "synthesize should return audio with media type")
    }

    @Test
    public fun synthesizeWithVoiceId() = runSuspendingTest {
        val voices = ttsService.listVoices()
        if (voices.isNotEmpty()) {
            val result = ttsService.synthesize(
                text = "Testing specific voice.",
                voice = TtsVoiceConfig(voiceId = voices.first().voiceId),
                options = TtsSynthesisOptions()
            )

            assertTrue(result.data.size!! > 0, "synthesize with voice ID should return audio")
        }
    }

    @Test
    public fun synthesizeStreamReturnsChunks() = runSuspendingTest {
        val chunks = ttsService.synthesizeStream(
            text = "This is a streaming test with multiple words.",
            voice = TtsVoiceConfig(language = "en-US"),
            options = TtsSynthesisOptions()
        ).toList()

        assertTrue(chunks.isNotEmpty(), "synthesizeStream should return at least one chunk")
        chunks.forEach { chunk ->
            assertTrue(chunk.data.size!! > 0, "each chunk should have data")
        }
    }

    @Test
    public fun synthesizeLongText() = runSuspendingTest {
        val longText = "This is a longer piece of text. ".repeat(20)

        val result = ttsService.synthesize(
            text = longText,
            voice = TtsVoiceConfig(language = "en-US"),
            options = TtsSynthesisOptions()
        )

        assertTrue(result.data.size!! > 0, "synthesize should handle long text")
    }

    @Test
    public fun synthesizeWithSpeedOption() = runSuspendingTest {
        val normalResult = ttsService.synthesize(
            text = "Speed test.",
            options = TtsSynthesisOptions(speed = 1.0f)
        )

        val fastResult = ttsService.synthesize(
            text = "Speed test.",
            options = TtsSynthesisOptions(speed = 1.5f)
        )

        assertTrue(normalResult.data.size!! > 0, "normal speed should work")
        assertTrue(fastResult.data.size!! > 0, "fast speed should work")
    }

    @Test
    public fun healthCheckReturnsStatus() = runSuspendingTest {
        val status = ttsService.healthCheck()
        assertNotNull(status, "healthCheck should return a status")
        assertNotNull(status.level, "health status should have a level")
    }
}
