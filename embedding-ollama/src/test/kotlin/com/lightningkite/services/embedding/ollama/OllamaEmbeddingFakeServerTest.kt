package com.lightningkite.services.embedding.ollama

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.embedding.EmbeddingModelId
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaEmbeddingFakeServerTest {
    private var embedRequest: String? = null

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/embed") { exchange ->
            embedRequest = exchange.requestBody.readBytes().decodeToString()
            val reply = """{"model":"nomic-embed-text","embeddings":[[0.5,0.25]],"prompt_eval_count":3}""".toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        start()
    }
    private val service = OllamaEmbeddingService("ollama", "http://127.0.0.1:${server.address.port}", TestSettingContext())

    @AfterTest fun stop() = server.stop(0)

    @Test fun embeds() = runBlocking {
        val result = service.embed(listOf("hello"), EmbeddingModelId("nomic-embed-text", "ollama"))

        assertEquals("""{"model":"nomic-embed-text","input":["hello"]}""", embedRequest)
        assertEquals(listOf(0.5f, 0.25f), result.embeddings.single().values.toList())
        assertEquals(3, result.usage.inputTokens)
    }
}
