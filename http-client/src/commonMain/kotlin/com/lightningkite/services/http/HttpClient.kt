package com.lightningkite.services.http

import com.lightningkite.services.SettingContext
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json


/**
 * Base HTTP client for making external calls.
 *
 * This client is configured with:
 * - **ContentNegotiation**: JSON serialization/deserialization
 * - **WebSockets**: WebSocket support
 * - **Request timeout**: 60 seconds
 * - **OpenTelemetry** (JVM only): Automatic tracing when SettingContext is in coroutine context
 *
 * ## Usage in Services
 *
 * Services use `client.config { }` to create customized instances:
 * ```kotlin
 * private val httpClient = com.lightningkite.services.http.client.config {
 *     install(Auth) { ... }
 * }
 * ```
 *
 * ## OpenTelemetry (JVM Only)
 *
 * On JVM, this client automatically instruments HTTP calls with OpenTelemetry when:
 * - A SettingContext with OpenTelemetry is in the coroutine context
 * - The SettingContext has a non-null `openTelemetry` property
 *
 * Services don't need modification - instrumentation happens automatically.
 */
expect val client: HttpClient

/**
 * HttpResponseException is an exception that handles external request error messages.
 */
class HttpResponseException(val response: HttpResponse, val body: String) :
    Exception("Got response ${response.status}: ${body.take(300)}")

/**
 * Checks the HttpResponse code and if it is not a success code it will throw an HttpResponseException
 */
suspend fun HttpResponse.statusFailing(): HttpResponse {
    if (!this.status.isSuccess()) throw HttpResponseException(this, bodyAsText())
    return this
}

fun HttpMessageBuilder.json() {
    accept(ContentType.Application.Json)
    contentType(ContentType.Application.Json)
}