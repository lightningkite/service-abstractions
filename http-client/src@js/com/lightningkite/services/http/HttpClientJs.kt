package com.lightningkite.services.http

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*

/**
 * JS-specific HTTP client.
 *
 * This is a basic HTTP client without OpenTelemetry instrumentation,
 * as OpenTelemetry support is currently limited to JVM and Android platforms.
 *
 * Note: Lazy initialization to avoid eager instantiation issues in serverless environments.
 */
public actual val client: HttpClient by lazy {
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
        install(WebSockets)
        // Streaming responses (LLM/SSE) can legitimately run for minutes; a total-request
        // timeout silently truncates them. Use an idle/socket timeout instead — fail only when
        // NO bytes flow for the window, which still catches dead connections without capping
        // healthy long streams.
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
        }
        engine {
            this.requestTimeout = 0  // disable CIO's own total-request cap; HttpTimeout governs
        }
    }
}
