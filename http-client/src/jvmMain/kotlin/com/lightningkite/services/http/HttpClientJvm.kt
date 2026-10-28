package com.lightningkite.services.http

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.crac.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

// OkHttp defaults to h2 + http/1.1, so HTTP/2 is used automatically over TLS via ALPN.
// Its dispatcher, however, gates concurrent calls at tiny defaults (maxRequests=64,
// maxRequestsPerHost=5). With HTTP/2 those calls multiplex over a few connections, so the
// low cap only throttles fanout for no benefit — raise both. Per-host is the one that
// matters for services that hammer a single API (FCM, OpenAI, S3).
internal val okHttp: OkHttpClient = OkHttpClient.Builder()
    .dispatcher(Dispatcher().apply {
        maxRequests = 1024
        maxRequestsPerHost = 1024
    })
    // On OkHttp, Ktor's `WebSockets { pingIntervalMillis }` sends no pings; only this does.
    // A missed pong fails the socket, so a silently dropped peer is noticed.
    .pingInterval(30.seconds.toJavaDuration())
    .build()

/**
 * JVM-specific HTTP client, backed by the OkHttp engine for HTTP/2 support and with OpenTelemetry
 * instrumentation.
 *
 * The engine is OkHttp rather than CIO specifically for **HTTP/2**: over TLS, OkHttp negotiates h2
 * via ALPN and multiplexes many concurrent requests over a small number of connections. This lets
 * high-fanout callers (e.g. FCM push, which sends one request per device token now that FCM's batch
 * endpoint is gone) achieve high throughput without opening thousands of sockets, and improves
 * connection reuse for every other service on this shared client.
 *
 * This overrides the common `client` definition to add automatic OpenTelemetry tracing on JVM. The
 * plugin checks the coroutine context for a SettingContext with OpenTelemetry configured and
 * automatically creates spans for HTTP requests.
 *
 * Services don't need to be modified - they continue using `client.config { }` as before.
 * OpenTelemetry instrumentation happens automatically when the SettingContext is available
 * in the coroutine context.
 *
 * ## AWS Lambda SnapStart (CRaC)
 *
 * This client is never closed, so services may keep it or a `client.config { }` copy (copies share
 * its engine).  Only its pooled connections are closed before a checkpoint.
 */
public actual val client: HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true })
    }
    install(WebSockets)
    install(OpenTelemetryPlugin)
    // Streaming responses (LLM/SSE) can legitimately run for minutes; a total-request
    // timeout silently truncates them (and previously produced butchered LLM responses).
    // Use an idle/socket timeout instead — fail only when NO bytes flow for the window,
    // which still catches dead connections without capping healthy long streams.
    install(HttpTimeout) {
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        socketTimeoutMillis = 60_000
        connectTimeoutMillis = 30_000
    }
    engine {
        // Ktor builds its OkHttpClients from this one, so they all share its connection pool.
        preconfigured = okHttp
    }
}

// Connections from before a snapshot are dead after the restore.  Closing the whole client instead
// would break every service that kept it (`JobCancellationException: Parent job is Completed`).
// CRaC holds resources weakly, so this must stay referenced.
internal val dropConnectionsBeforeCheckpoint: Resource = object : Resource {
    override fun beforeCheckpoint(context: Context<out Resource>) = okHttp.connectionPool.evictAll()
    override fun afterRestore(context: Context<out Resource>) {}
}.also { Core.getGlobalContext().register(it) }
