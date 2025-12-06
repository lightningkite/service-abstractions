package com.lightningkite.services.speech.local

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.speech.SpeechToTextService
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VoskSpeechToTextServiceTest {

    private val context = TestSettingContext()

    init {
        // Ensure companion object init block runs to register the URL handler
        VoskSpeechToTextService
    }

    @Test
    fun `URL scheme vosk is registered`() = runTest {
        val settings = SpeechToTextService.Settings("vosk://")
        val service = settings("test", context)

        assertNotNull(service)
        assertTrue(service is VoskSpeechToTextService)
    }

    @Test
    fun `URL scheme local is registered for STT`() = runTest {
        val settings = SpeechToTextService.Settings("local://")
        val service = settings("test", context)

        assertNotNull(service)
        assertTrue(service is VoskSpeechToTextService)
    }

    @Test
    fun `healthCheck returns appropriate status`() = runTest {
        val service = VoskSpeechToTextService(
            name = "test-stt",
            context = context,
            modelPath = null,
            modelName = VoskModelManager.DEFAULT_MODEL_NAME,
            modelsDirectory = File("./local/vosk-models")
        )

        val status = service.healthCheck()

        // Should be OK if model exists, WARNING if not downloaded yet
        assertTrue(
            status.level == com.lightningkite.services.HealthStatus.Level.OK ||
            status.level == com.lightningkite.services.HealthStatus.Level.WARNING,
            "Health check should return OK or WARNING"
        )
    }

    @Test
    fun `VoskModelManager tracks available models`() {
        val manager = VoskModelManager()

        // Check that the available models map contains expected entries
        assertTrue(VoskModelManager.AVAILABLE_MODELS.containsKey("en-us"))
        assertTrue(VoskModelManager.AVAILABLE_MODELS.containsKey("de"))
        assertTrue(VoskModelManager.AVAILABLE_MODELS.containsKey("es"))
    }
}
