package com.lightningkite.services.ai.koog

import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests for [OllamaManager].
 *
 * Note: These tests are designed to work whether or not Ollama is installed.
 * Integration tests that require Ollama are marked with appropriate conditions.
 */
class OllamaManagerTest {

    @Test
    fun testFindOllamaExecutable() {
        val manager = OllamaManager()
        val executable = manager.findOllamaExecutable()

        // This test just verifies the method doesn't throw
        // The result depends on whether Ollama is installed
        println("Ollama executable: ${executable ?: "not found"}")
    }

    @Test
    fun testIsInstalled() {
        val manager = OllamaManager()
        val installed = manager.isInstalled()

        // This test just verifies the method works
        println("Ollama installed: $installed")
    }

    @Test
    fun testIsServerRunning() = runBlocking {
        val manager = OllamaManager()
        val running = manager.isServerRunning()

        // This test just verifies the method works
        println("Ollama server running: $running")
    }

    @Test
    fun testNormalizeModelName() {
        // Test via the isModelPulled behavior indirectly
        // The normalize function is private but we can verify behavior

        val manager = OllamaManager()

        // Just verify the manager can be created with different base URLs
        val customManager = OllamaManager("http://localhost:11435")
        assertEquals("http://localhost:11435", customManager.baseUrl)
    }

    @Test
    fun testProgressPercent() {
        // Test OllamaPullProgress calculations
        val progress1 = OllamaPullProgress(
            status = "downloading",
            total = 1000,
            completed = 500
        )
        assertEquals(50.0, progress1.progressPercent)

        val progress2 = OllamaPullProgress(
            status = "downloading",
            total = 1000,
            completed = 1000
        )
        assertEquals(100.0, progress2.progressPercent)

        val progress3 = OllamaPullProgress(
            status = "pulling manifest",
            total = null,
            completed = null
        )
        assertNull(progress3.progressPercent)

        val progress4 = OllamaPullProgress(
            status = "downloading",
            total = 0,
            completed = 0
        )
        assertNull(progress4.progressPercent) // Avoid division by zero
    }

    @Test
    fun testProgressString() {
        val progress1 = OllamaPullProgress(
            status = "downloading digestXYZ",
            total = 1000,
            completed = 250
        )
        assertTrue(progress1.progressString.contains("downloading"))
        assertTrue(progress1.progressString.contains("25.0%"))

        val progress2 = OllamaPullProgress(
            status = "pulling manifest"
        )
        assertEquals("pulling manifest", progress2.progressString)

        val progress3 = OllamaPullProgress()
        assertEquals("Unknown status", progress3.progressString)
    }

    @Test
    fun testOllamaModelInfo() {
        // Test data class construction
        val info = OllamaModelInfo(
            name = "llama3.2:latest",
            size = 2_000_000_000,
            digest = "sha256:abc123"
        )
        assertEquals("llama3.2:latest", info.name)
        assertEquals(2_000_000_000, info.size)
    }

    // by Claude - Additional tests for better coverage

    @Test
    fun testOllamaModelInfoWithDetails() {
        // Test OllamaModelInfo with full OllamaModelDetails
        val details = OllamaModelDetails(
            format = "gguf",
            family = "llama",
            families = listOf("llama", "llama3"),
            parameter_size = "8B",
            quantization_level = "Q4_0"
        )
        val info = OllamaModelInfo(
            name = "llama3.2:8b-q4_0",
            modified_at = "2024-01-15T10:30:00Z",
            size = 4_500_000_000,
            digest = "sha256:def456",
            details = details
        )
        assertEquals("llama3.2:8b-q4_0", info.name)
        assertEquals("2024-01-15T10:30:00Z", info.modified_at)
        assertEquals(4_500_000_000, info.size)
        assertEquals("sha256:def456", info.digest)
        assertNotNull(info.details)
        assertEquals("gguf", info.details?.format)
        assertEquals("llama", info.details?.family)
        assertEquals(listOf("llama", "llama3"), info.details?.families)
        assertEquals("8B", info.details?.parameter_size)
        assertEquals("Q4_0", info.details?.quantization_level)
    }

    @Test
    fun testOllamaModelInfoMinimal() {
        // Test OllamaModelInfo with only required name field
        val info = OllamaModelInfo(name = "codellama")
        assertEquals("codellama", info.name)
        assertNull(info.modified_at)
        assertNull(info.size)
        assertNull(info.digest)
        assertNull(info.details)
    }

    @Test
    fun testOllamaModelDetailsMinimal() {
        // Test OllamaModelDetails with all null fields
        val details = OllamaModelDetails()
        assertNull(details.format)
        assertNull(details.family)
        assertNull(details.families)
        assertNull(details.parameter_size)
        assertNull(details.quantization_level)
    }

    @Test
    fun testProgressPercentNegativeTotal() {
        // Edge case: negative total should return null (defensive check)
        val progress = OllamaPullProgress(
            status = "downloading",
            total = -100,
            completed = 50
        )
        // total <= 0 returns null per the implementation
        assertNull(progress.progressPercent)
    }

    @Test
    fun testProgressPercentCompletedGreaterThanTotal() {
        // Edge case: completed > total (can happen briefly during downloads)
        val progress = OllamaPullProgress(
            status = "downloading",
            total = 1000,
            completed = 1050
        )
        // Should still calculate percentage even if > 100%
        assertEquals(105.0, progress.progressPercent)
    }

    @Test
    fun testProgressPercentCompletedNullButTotalNonNull() {
        // Edge case: total is set but completed is null
        val progress = OllamaPullProgress(
            status = "downloading",
            total = 1000,
            completed = null
        )
        assertNull(progress.progressPercent)
    }

    @Test
    fun testProgressPercentTotalNullButCompletedNonNull() {
        // Edge case: completed is set but total is null
        val progress = OllamaPullProgress(
            status = "downloading",
            total = null,
            completed = 500
        )
        assertNull(progress.progressPercent)
    }

    @Test
    fun testProgressStringWithError() {
        // Test progressString when there's an error
        val progress = OllamaPullProgress(
            status = "download failed",
            error = "Connection refused"
        )
        assertEquals("download failed", progress.progressString)
        assertEquals("Connection refused", progress.error)
    }

    @Test
    fun testOllamaManagerWithCustomBaseUrl() {
        // Test creating manager with various custom base URLs
        val manager1 = OllamaManager("http://192.168.1.100:11434")
        assertEquals("http://192.168.1.100:11434", manager1.baseUrl)

        val manager2 = OllamaManager("http://localhost:9999")
        assertEquals("http://localhost:9999", manager2.baseUrl)

        // HTTPS URL
        val manager3 = OllamaManager("https://ollama.example.com")
        assertEquals("https://ollama.example.com", manager3.baseUrl)
    }

    @Test
    fun testOllamaManagerClose() {
        // Test that close can be called without errors
        val manager = OllamaManager()
        manager.close()
        // Should not throw
    }

    @Test
    fun testPullProgressWithDigest() {
        // Test progress with digest field populated (during layer download)
        val progress = OllamaPullProgress(
            status = "downloading sha256:abc123...",
            digest = "sha256:abc123def456",
            total = 2_000_000_000,
            completed = 1_000_000_000
        )
        assertEquals("sha256:abc123def456", progress.digest)
        assertEquals(50.0, progress.progressPercent)
        assertTrue(progress.progressString.contains("50.0%"))
    }

    @Test
    fun testPullProgressVerySmallPercentage() {
        // Test very small progress percentage
        val progress = OllamaPullProgress(
            status = "downloading",
            total = 10_000_000_000,
            completed = 1_000_000
        )
        val pct = progress.progressPercent
        assertNotNull(pct)
        assertTrue(pct in 0.009..0.011, "Expected ~0.01%, got $pct%")
    }

    // =========================================================================
    // Integration tests - require Ollama to be installed
    // =========================================================================

    @Test
    fun testListModelsWhenServerRunning() = runBlocking {
        val manager = OllamaManager()

        if (!manager.isServerRunning()) {
            println("Skipping test: Ollama server not running")
            return@runBlocking
        }

        val models = manager.listModels()
        println("Available models: ${models.map { it.name }}")

        // Just verify we got a valid response
        assertNotNull(models)
    }

    @Test
    fun testIsModelPulledWhenServerRunning() = runBlocking {
        val manager = OllamaManager()

        if (!manager.isServerRunning()) {
            println("Skipping test: Ollama server not running")
            return@runBlocking
        }

        // Check for a model that's unlikely to exist
        val hasNonexistent = manager.isModelPulled("nonexistent-model-xyz-12345")
        assertFalse(hasNonexistent)

        // Check actual models
        val models = manager.listModels()
        if (models.isNotEmpty()) {
            val firstModel = models.first().name
            val hasPulled = manager.isModelPulled(firstModel)
            assertTrue(hasPulled, "Model $firstModel should be reported as pulled")
        }
    }

    @Test
    fun testStartAndStopServer() = runBlocking {
        val manager = OllamaManager()

        if (!manager.isInstalled()) {
            println("Skipping test: Ollama not installed")
            return@runBlocking
        }

        // If server is already running, we can't test start/stop
        if (manager.isServerRunning()) {
            println("Skipping test: Ollama server already running (can't test start/stop)")
            return@runBlocking
        }

        try {
            // Start the server
            val process = manager.startServer(registerShutdownHook = false)
            assertNotNull(process, "Should return process when starting server")

            // Verify it's running
            assertTrue(manager.isServerRunning(), "Server should be running after start")

            // Stop the server
            manager.stopServer()

            // Give it a moment to shut down
            kotlinx.coroutines.delay(1000)

            // Verify it's stopped
            assertFalse(manager.isServerRunning(), "Server should not be running after stop")
        } finally {
            // Clean up in case of test failure
            manager.stopServer()
        }
    }

    /**
     * This test actually pulls a small model. Only run manually.
     * Uncomment and run when you want to test the full flow.
     */
    // @Test
    fun testPullSmallModel() = runBlocking {
        val manager = OllamaManager()

        if (!manager.isServerRunning()) {
            println("Skipping test: Ollama server not running")
            return@runBlocking
        }

        // tinyllama is a very small model (~600MB)
        val modelName = "tinyllama"

        val progressUpdates = mutableListOf<String>()
        manager.pullModel(modelName) { progress ->
            progressUpdates.add(progress.progressString)
            println(progress.progressString)
        }

        assertTrue(progressUpdates.isNotEmpty(), "Should have received progress updates")
        assertTrue(manager.isModelPulled(modelName), "Model should be available after pull")
    }
}

/**
 * Tests for URL-based settings integration.
 */
class OllamaSettingsIntegrationTest {

    @Test
    fun testOllamaUrlParsing() {
        // Test that the URL format is correct
        val settings1 = LLMClientAndModel.Settings("ollama://llama3.2:latest")
        assertEquals("ollama://llama3.2:latest", settings1.url)

        val settings2 = LLMClientAndModel.Settings("ollama://llama3.2?autoStart=true")
        assertEquals("ollama://llama3.2?autoStart=true", settings2.url)

        val settings3 = LLMClientAndModel.Settings("ollama://llama3.2?autoStart=true&autoPull=true")
        assertEquals("ollama://llama3.2?autoStart=true&autoPull=true", settings3.url)

        val settings4 = LLMClientAndModel.Settings("ollama-auto://llama3.2")
        assertEquals("ollama-auto://llama3.2", settings4.url)
    }

    @Test
    fun testOllamaAutoFactoryMethod() {
        val settings = LLMClientAndModel.Settings.ollamaAuto(
            model = ai.koog.prompt.llm.OllamaModels.Meta.LLAMA_3_2
        )
        assertTrue(settings.url.startsWith("ollama-auto://"))
        assertTrue(settings.url.contains("llama3.2"))

        val settingsWithUrl = LLMClientAndModel.Settings.ollamaAuto(
            model = ai.koog.prompt.llm.OllamaModels.Meta.LLAMA_3_2,
            baseUrl = "http://localhost:11435"
        )
        assertTrue(settingsWithUrl.url.contains("baseUrl=http://localhost:11435"))
    }

    @Test
    fun testEmbedderOllamaAutoFactoryMethod() {
        // Note: This would require an embedding-capable Ollama model
        // Most Ollama models listed in knownModels don't support embedding
        // This just tests the URL construction
        val url = "ollama-auto://nomic-embed-text"
        val settings = com.lightningkite.services.ai.koog.rag.EmbedderSettings(url)
        assertEquals(url, settings.url)
    }
}
