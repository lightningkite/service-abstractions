package com.lightningkite.services.ai.anthropic

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectBrowserAccessTest {
    private suspend fun headersSentWith(directBrowserAccess: Boolean): Headers {
        var sent: Headers? = null
        val mock = HttpClient(MockEngine { request ->
            sent = request.headers
            respond("""{"data":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        AnthropicLlmAccess(
            name = "test",
            context = TestSettingContext(),
            apiKey = "test-key",
            directBrowserAccess = directBrowserAccess,
            httpClient = mock,
        ).getModels()
        return sent!!
    }

    @Test
    fun headerIsOffByDefault() = runTest {
        assertNull(headersSentWith(directBrowserAccess = false)["anthropic-dangerous-direct-browser-access"])
    }

    @Test
    fun headerIsSentWhenEnabled() = runTest {
        assertEquals("true", headersSentWith(directBrowserAccess = true)["anthropic-dangerous-direct-browser-access"])
    }

    @Test
    fun urlSettingEnablesIt() {
        AnthropicLlmSettings.ensureRegistered()
        fun access(settings: LlmAccess.Settings) = settings("test", TestSettingContext()) as AnthropicLlmAccess
        assertFalse(access(LlmAccess.Settings("anthropic://?apiKey=k")).directBrowserAccess)
        assertTrue(access(LlmAccess.Settings("anthropic://?apiKey=k&directBrowserAccess=true")).directBrowserAccess)
        assertTrue(access(LlmAccess.Settings.anthropic(apiKey = "k", directBrowserAccess = true)).directBrowserAccess)
    }
}
