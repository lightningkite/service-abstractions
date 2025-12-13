package com.lightningkite.services.http

import com.lightningkite.services.SettingContext
import com.lightningkite.services.otel.TelemetrySanitization
import io.ktor.client.*
import io.ktor.client.plugins.api.*
import io.ktor.util.*
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context key for passing SettingContext through the coroutine context.
 *
 * Services should add this to their coroutine context when making HTTP calls
 * to enable automatic OpenTelemetry instrumentation.
 */
val SettingContextKey = object : CoroutineContext.Key<SettingContextElement> {}

/**
 * Coroutine context element that carries a SettingContext.
 */
data class SettingContextElement(val settingContext: SettingContext) : CoroutineContext.Element {
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
 * This allows the base `client` to support OpenTelemetry without requiring
 * services to be modified - they just need to ensure their coroutine context
 * includes the SettingContextElement (which Service implementations can do automatically).
 */
val OpenTelemetryPlugin = createClientPlugin("OpenTelemetryPlugin") {
    var tracer: Tracer? = null

    onRequest { request, content ->
        // Try to get OpenTelemetry from coroutine context
        val settingContext = try {
            currentCoroutineContext()[SettingContextKey]?.settingContext
        } catch (e: Exception) {
            null
        }

        val otel = settingContext?.openTelemetry
        if (otel != null) {
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

            val scope = span.makeCurrent()

            // Store span and scope in request attributes so we can close them later
            request.attributes.put(OtelSpanKey, span)
            request.attributes.put(OtelScopeKey, scope)
        }
    }

    onResponse { response ->
        val span = response.call.request.attributes.getOrNull(OtelSpanKey)
        val scope = response.call.request.attributes.getOrNull(OtelScopeKey)

        span?.let {
            it.setAttribute("http.status_code", response.status.value.toLong())

            if (response.status.value >= 400) {
                it.setStatus(StatusCode.ERROR, "HTTP ${response.status.value}")
            } else {
                it.setStatus(StatusCode.OK)
            }

            it.end()
        }

        scope?.close()
    }

    onClose {
        // Clean up any remaining spans on client close
    }
}

/**
 * Attribute key for storing the OpenTelemetry span in request attributes.
 */
private val OtelSpanKey = AttributeKey<Span>("OtelSpan")

/**
 * Attribute key for storing the OpenTelemetry scope in request attributes.
 */
private val OtelScopeKey = AttributeKey<io.opentelemetry.context.Scope>("OtelScope")
