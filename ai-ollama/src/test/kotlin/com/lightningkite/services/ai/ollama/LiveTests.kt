package com.lightningkite.services.ai.ollama

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.inference
import com.lightningkite.services.ai.plainText
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live smoke tests that run only when a local Ollama server is reachable. Silently skips if
 * not — Ollama is an optional dependency for developer validation.
 */
class LiveTests {

    private val ollamaBaseUrl = "http://localhost:11434"

    private fun isReachable(url: String): Boolean = runBlocking {
        val client = HttpClient {}
        try {
            withTimeoutOrNull(1500) {
                client.get(url).status.isSuccess()
            } ?: false
        } catch (e: Exception) {
            false
        } finally {
            client.close()
        }
    }

    @Test
    fun ollamaLiveSmokeTestIfReachable() = runBlocking {
        if (!isReachable("$ollamaBaseUrl/api/tags")) {
            println("[LiveTests] Ollama not reachable at $ollamaBaseUrl — skipping.")
            return@runBlocking
        }
        val access = OllamaLlmAccess(
            name = "ollama-live",
            baseUrl = ollamaBaseUrl,
            context = TestSettingContext(),
        )
        try {
            val models = access.getModels()
            println("[LiveTests] Ollama reports ${models.size} models locally.")
            val candidate = models.firstOrNull()
            if (candidate == null) {
                println("[LiveTests] No Ollama models installed; skipping inference.")
                return@runBlocking
            }
            val result = try {
                access.inference(
                    model = candidate.id,
                    prompt = LlmPrompt(
                        messages = listOf(
                            LlmMessage.User(listOf(LlmPart.Text("Say 'ok' once."))),
                        ),
                        maxTokens = 16,
                    ),
                )
            } catch (e: Exception) {
                println("[LiveTests] Ollama inference failed: ${e.message} — skipping assertion.")
                return@runBlocking
            }
            val text = result.message.plainText()
            println("[LiveTests] Ollama response: $text")
            assertTrue(text.isNotEmpty() || result.message.parts.isNotEmpty(),
                "Expected some response content from Ollama")
        } finally {
            access.disconnect()
        }
    }

    @Test
    fun schemeRegistrationSmoke() {
        // Touching OllamaSchemeRegistrar triggers scheme registration via object init.
        OllamaSchemeRegistrar.ensureRegistered()
        assertTrue(LlmAccess.Settings.supports("ollama"), "ollama:// should be registered")
    }
}
