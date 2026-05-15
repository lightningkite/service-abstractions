package com.lightningkite.services.http

import com.lightningkite.services.SettingContext
import com.lightningkite.services.otel.TelemetrySanitization
import com.lightningkite.services.recordExceptionWithFingerprint
import io.ktor.client.plugins.api.*
import io.ktor.util.*
import io.opentelemetry.api.trace.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context key for passing SettingContext through the coroutine context.
 *
 * Services should add this to their coroutine context when making HTTP calls
 * to enable automatic OpenTelemetry instrumentation.
 */
private val SettingContextKey = object : CoroutineContext.Key<SettingContextElement> {}

/**
 * Coroutine context element that carries a SettingContext.
 */
public data class SettingContextElement(val settingContext: SettingContext) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = SettingContextKey
}

/**
 * OpenTelemetry plugin for Ktor HTTP client that automatically traces requests.
 *
 * This plugin is installed in the base HTTP client and automatically creates spans
 * for HTTP requests when OpenTelemetry is available in the coroutine context via [SettingContextElement].
 *
 * The plugin creates a span for each HTTP request with:
 * - **Name**: `HTTP {method} {path}`
 * - **Kind**: CLIENT
 * - **Attributes**: http.method, http.url, http.status_code, http.host
 *
 * ## How It Works
 *
 * 1. Checks the coroutine context for a [SettingContextElement]
 * 2. If found and OpenTelemetry is configured, creates a span for the request
 * 3. If not found, request proceeds normally without tracing
 *
 * The span lifecycle is managed entirely within the [Send] hook so that both
 * successful responses and transport-level errors (timeouts, connection refused,
 * cancellation) are correctly recorded and the span is always ended.
 *
 * This allows the base `client` to support OpenTelemetry without requiring
 * services to be modified - they just need to ensure their coroutine context
 * includes the SettingContextElement (which Service implementations can do automatically).
 */
internal val OpenTelemetryPlugin = createClientPlugin("OpenTelemetryPlugin") {
    var tracer: Tracer? = null

    on(Send) { request ->
        // Try to get OpenTelemetry from coroutine context
        val settingContext = try {
            currentCoroutineContext()[SettingContextKey]?.settingContext
        } catch (e: Exception) {
            null
        }

        val otel = settingContext?.openTelemetry
        if (otel == null) {
            // No telemetry configured — proceed without tracing
            proceed(request)
        } else {
            if (tracer == null) {
                tracer = otel.getTracer("http-client")
            }

            val url = request.url
            val path = url.pathSegments.joinToString("/", prefix = "/")
            val span = tracer!!.spanBuilder("HTTP ${request.method.value} $path")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.method", request.method.value)
                .setAttribute("http.url", TelemetrySanitization.sanitizeUrl(url.toString()))
                .setAttribute("http.host", url.host)
                .startSpan()
            // Manual span lifecycle: the otel-jvm `useBlocking` helper is an `inline` function
            // built for JVM 17, and this module compiles for JVM 1.8, so we can't inline it.
            // Semantics mirror the helper: UNSET on success (only set ERROR for 4xx+), ERROR +
            // recordExceptionWithFingerprint on throw, "Cancelled" event for CancellationException.
            val scope = span.makeCurrent()
            try {
                val call = proceed(request)
                val status = call.response.status.value
                span.setAttribute("http.status_code", status.toLong())
                if (status >= 400) span.setStatus(StatusCode.ERROR, "HTTP $status")
                call
            } catch (t: CancellationException) {
                span.addEvent("Cancelled")
                throw t
            } catch (t: Throwable) {
                span.setStatus(StatusCode.ERROR)
                span.recordExceptionWithFingerprint(t)
                throw t
            } finally {
                scope.close()
                span.end()
            }
        }
    }

    onClose {
        // Clean up any remaining spans on client close
    }
}
