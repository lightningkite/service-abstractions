package com.lightningkite.services.http

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.json
import org.crac.Context
import org.crac.Core
import org.crac.Resource
import java.util.concurrent.atomic.AtomicReference

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
 *
 * ## AWS Lambda SnapStart (CRaC) Support
 *
 * This client properly handles CRaC checkpoint/restore cycles:
 * - Before checkpoint: Closes the HTTP client to release resources
 * - After restore: Recreates the HTTP client for the new execution environment
 *
 * This allows the client to be used both before and after snapshots without issues.
 */
actual val client: HttpClient
    get() = HttpClientHolder.client

/**
 * Holder for HTTP client that supports CRaC checkpoint/restore.
 */
private object HttpClientHolder : Resource {
    private val clientRef = AtomicReference<HttpClient?>()

    init {
        Core.getGlobalContext().register(this)
    }

    override fun beforeCheckpoint(context: Context<out Resource>) {
        // Close the client before checkpoint
        clientRef.getAndSet(null)?.close()
    }

    override fun afterRestore(context: Context<out Resource>) {
        // Client will be recreated on next access
    }

    val client: HttpClient
        get() = clientRef.get() ?: synchronized(this) {
            clientRef.get() ?: createClient().also { clientRef.set(it) }
        }

    private fun createClient() = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
        install(WebSockets)
        install(OpenTelemetryPlugin)
        engine {
            this.requestTimeout = 60000
        }
    }
}
