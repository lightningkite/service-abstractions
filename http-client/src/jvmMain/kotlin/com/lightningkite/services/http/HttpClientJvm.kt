package com.lightningkite.services.http

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.json

/**
 * JVM-specific HTTP client with OpenTelemetry instrumentation.
 *
 * This overrides the common `client` definition to add automatic OpenTelemetry tracing
 * on JVM platforms. The plugin checks the coroutine context for a SettingContext with
 * OpenTelemetry configured and automatically creates spans for HTTP requests.
 *
 * Services don't need to be modified - they continue using `client.config { }` as before.
 * OpenTelemetry instrumentation happens automatically when the SettingContext is available
 * in the coroutine context.
 */
actual val client: HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json()
    }
    install(WebSockets)
    install(OpenTelemetryPlugin)
    engine {
        this.requestTimeout = 60000
    }
}
