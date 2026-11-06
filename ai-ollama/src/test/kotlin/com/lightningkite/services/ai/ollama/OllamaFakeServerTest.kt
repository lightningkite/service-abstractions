package com.lightningkite.services.ai.ollama

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.inference
import com.lightningkite.services.ai.plainText
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaFakeServerTest {
    private var chatRequest: String? = null

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/tags") { exchange ->
            val reply = """{"models":[{"name":"llama3:8b","details":{"parameter_size":"8B"},"unknown":1}]}""".toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        createContext("/api/chat") { exchange ->
            chatRequest = exchange.requestBody.readBytes().decodeToString()
            val reply = """
                {"message":{"role":"assistant","content":"Hel"},"done":false}
                {"message":{"role":"assistant","content":"lo"},"done":false}
                {"done":true,"done_reason":"stop","prompt_eval_count":5,"eval_count":2}
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        start()
    }
    private val access = OllamaLlmAccess("ollama", "http://127.0.0.1:${server.address.port}", TestSettingContext())

    @AfterTest fun stop() = server.stop(0)

    @Test fun listsModelsAndStreamsChat() = runBlocking {
        val model = access.getModels().single()
        assertEquals("llama3:8b", model.name)

        val result = access.inference(
            model = model.id,
            prompt = LlmPrompt(messages = listOf(LlmMessage.User(listOf(LlmPart.Text("Hi"))))),
        )

        assertEquals("Hello", result.message.plainText())
        assertEquals("llama3:8b", Json.parseToJsonElement(chatRequest!!).jsonObject["model"]?.jsonPrimitive?.content)
    }
}
