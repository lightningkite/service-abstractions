package com.lightningkite.services.http

import com.sun.net.httpserver.HttpServer
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.crac.Core
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SnapStartTest {
    // Replies with the caller's port, which identifies the connection the request came over.
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val reply = "${exchange.requestHeaders.getFirst("x-api-key")}@${exchange.remoteAddress.port}".toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}/"

    @AfterTest fun stop() = server.stop(0)

    private fun snapshot() {
        dropConnectionsBeforeCheckpoint.beforeCheckpoint(Core.getGlobalContext())
        dropConnectionsBeforeCheckpoint.afterRestore(Core.getGlobalContext())
    }

    @Test fun keptClientAndCopyWorkOnNewConnectionsAfterRestore() = runBlocking {
        val kept = client
        val copy = client.config { defaultRequest { header("x-api-key", "k") } }

        val first = copy.get(url).bodyAsText()
        assertEquals(first, copy.get(url).bodyAsText(), "the connection should be reused before the snapshot")
        snapshot()
        val afterRestore = copy.get(url).bodyAsText()

        assertEquals("k", afterRestore.substringBefore('@'))
        assertNotEquals(first, afterRestore, "the pre-snapshot connection should have been closed")
        assertEquals("null", kept.get(url).bodyAsText().substringBefore('@'))
    }
}
