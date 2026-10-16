package com.lightningkite.services.sentry

import com.lightningkite.services.SettingContext
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryBackend
import io.opentelemetry.api.trace.Span
import io.sentry.Scope
import io.sentry.SentryClient
import io.sentry.SentryOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wraps another [TelemetryBackend], also sending everything passed to [reportError] to Sentry.
 *
 * Call [register] at startup, then use your DSN with `https` replaced by `sentry`, followed by the
 * wrapped backend's URL: `sentry://<key>@o0.ingest.sentry.io/<projectId>/otlp-grpc://localhost:4317`.
 */
public class SentryTelemetryBackend(
    private val wrapped: TelemetryBackend,
    dsn: String,
    context: SettingContext,
) : TelemetryBackend by wrapped {
    internal val options = SentryOptions().apply {
        setDsn(dsn)
        environment = context.projectName
        setBeforeSend { event, _ ->
            event.exceptions?.forEach { it.value = it.value?.let(context.telemetrySanitization::sanitizeExceptionMessage) }
            event
        }
    }

    // A standalone client rather than Sentry.init, so we don't take over process-wide Sentry state.
    // Sentry.init would also flush on shutdown; we do that ourselves.
    private val client = SentryClient(options).also { client ->
        Runtime.getRuntime().addShutdownHook(Thread { client.close() })
    }

    override fun reportError(throwable: Throwable, attributes: TelemetryAttributes) {
        wrapped.reportError(throwable, attributes)
        val scope = Scope(options)
        for ((key, value) in attributes.map) scope.setTag(key.name, value.toString())
        val span = Span.current().spanContext
        if (span.isValid) scope.setTag("trace_id", span.traceId)
        client.captureException(throwable, scope)
    }

    override suspend fun flush() {
        wrapped.flush()
        withContext(Dispatchers.IO) { client.flush(10_000) }
    }

    public companion object {
        init {
            TelemetryBackend.Settings.register("sentry") { name, settings, context ->
                // A DSN ends at its numeric project ID; everything after that is the wrapped backend's URL.
                val segments = settings.url.substringAfter("://").split("/")
                val projectIdIndex = segments.indexOfFirst { it.isNotEmpty() && it.all(Char::isDigit) }
                require(projectIdIndex > 0 && projectIdIndex < segments.lastIndex) {
                    "Expected sentry://<key>@<host>/<projectId>/<wrapped telemetry URL>"
                }
                val dsn = "https://" + segments.take(projectIdIndex + 1).joinToString("/")
                val wrappedUrl = segments.drop(projectIdIndex + 1).joinToString("/")
                SentryTelemetryBackend(settings.copy(url = wrappedUrl)(name, context), dsn, context)
            }
        }

        public fun register() {}
    }
}
