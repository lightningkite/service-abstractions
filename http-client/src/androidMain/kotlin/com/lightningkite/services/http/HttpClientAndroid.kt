package com.lightningkite.services.http

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import java.util.concurrent.TimeUnit

/**
 * Android-specific HTTP client, backed by OkHttp, Android's recommended engine.
 *
 * This is a basic HTTP client without OpenTelemetry instrumentation,
 * as OpenTelemetry support is currently limited to JVM platforms.
 *
 * Note: Lazy initialization to avoid eager instantiation issues in serverless environments.
 */
public actual val client: HttpClient by lazy {
    HttpClient(OkHttp) {
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
            config {
                // On OkHttp, Ktor's `WebSockets { pingIntervalMillis }` sends no pings; only this does.
                pingInterval(30, TimeUnit.SECONDS)
            }
        }
    }
}
