package com.lightningkite.services.http

import com.lightningkite.services.Namespaced
import com.lightningkite.services.SettingContext
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.telemetryAttributesOf
import com.lightningkite.services.telemetry.telemetryTrace
import io.ktor.client.plugins.api.*
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context key for passing SettingContext through the coroutine context.
 *
 * Services should add this to their coroutine context when making HTTP calls
 * to enable automatic telemetry instrumentation.
 */
private val SettingContextKey = object : CoroutineContext.Key<SettingContextElement> {}

private val responseStatusClass = TelemetryKey.OfString("http.response.status_class")

/**
 * Coroutine context element that carries a SettingContext.
 */
public data class SettingContextElement(val settingContext: SettingContext) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = SettingContextKey
}

/**
 * Minimal [Namespaced] owner for the shared HTTP client. The base client is a shared resource with no
 * owning service, so we synthesize a stable, low-cardinality owner from the ambient [SettingContext].
 * Its fixed [name] (`"http-client"`) becomes the metric `system`/span prefix — never per-request.
 */
private class HttpClientOwner(override val context: SettingContext) : Namespaced {
    override val name: String get() = "http-client"
}

/**
 * Telemetry plugin for the Ktor HTTP client that automatically traces outbound requests.
 *
 * This plugin is installed in the base HTTP client and creates a span (via the coroutine-first
 * metrics API) for each request when a [SettingContextElement] is present in the coroutine context.
 *
 * Each request is wrapped in [telemetryTrace] with op `"request"`, producing a span named
 * `"http-client.request"` plus the RED `{system, operation, outcome}` metrics. HTTP semantic-convention
 * attributes are attached manually: the request method/target up front via `attributes`, and the
 * response status code afterward via [com.lightningkite.services.telemetry.TelemetryTrace.enrich].
 *
 * ## How It Works
 *
 * 1. Checks the coroutine context for a [SettingContextElement]
 * 2. If found, wraps the request in [telemetryTrace] (a no-op when no metrics backend is configured)
 * 3. If not found, the request proceeds normally without tracing
 *
 * The span lifecycle is owned by [telemetryTrace]: both successful responses and transport-level errors
 * (timeouts, connection refused, cancellation) flow through it — error outcomes are recorded and
 * cancellation is treated as non-error, with the span always ended.
 *
 * This lets the base `client` support telemetry without modifying services — they only need their
 * coroutine context to include the [SettingContextElement] (which Service implementations can do
 * automatically).
 */
internal val OpenTelemetryPlugin = createClientPlugin("OpenTelemetryPlugin") {
    on(Send) { request ->
        // Try to get the SettingContext from the coroutine context
        val settingContext = try {
            currentCoroutineContext()[SettingContextKey]?.settingContext
        } catch (e: Exception) {
            null
        }

        if (settingContext == null) {
            // No SettingContext available — proceed without tracing
            proceed(request)
        } else {
            val url = request.url
            // HTTP semantic-convention attributes known up front (span-only; high cardinality on
            // `url.full` is fine — these are NOT metric dimensions).
            val upFront = TelemetryAttributes {
                put(TelemetryKeys.Http.requestMethod, request.method.value)
                put(TelemetryKeys.Url.full, settingContext.telemetrySanitization.sanitizeUrl(url.buildString()))
                put(TelemetryKeys.Server.address, url.host)
                if (url.port > 0) put(TelemetryKeys.Server.port, url.port)
            }

            HttpClientOwner(settingContext).telemetryTrace(
                opName = "request",
                attributes = upFront,
                // Low-cardinality dimensions promoted onto the RED metrics: the method, and a
                // status-class flag enriched after the response for error-rate-by-class.
                dimensions = setOf(TelemetryKeys.Http.requestMethod, TelemetryKeys.Http.responseStatusCode),
            ) { span ->
                val call = proceed(request)
                val status = call.response.status.value
                span.enrich(telemetryAttributesOf(
                    TelemetryKeys.Http.responseStatusCode to status.toLong(),
                    responseStatusClass to "${status / 100}xx",
                ))
                call
            }
        }
    }

    onClose {
        // Nothing to clean up; spans are scoped per-request by telemetryTrace.
    }
}
