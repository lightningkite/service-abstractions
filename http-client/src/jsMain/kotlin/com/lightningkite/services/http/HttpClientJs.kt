package com.lightningkite.services.http

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.json

/**
 * JS-specific HTTP client.
 *
 * This is a basic HTTP client without OpenTelemetry instrumentation,
 * as OpenTelemetry support is currently limited to JVM and Android platforms.
 *
 * Note: Lazy initialization to avoid eager instantiation issues in serverless environments.
 */
actual val client: HttpClient by lazy {
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
        install(WebSockets)
        engine {
            this.requestTimeout = 60000
        }
    }
}
