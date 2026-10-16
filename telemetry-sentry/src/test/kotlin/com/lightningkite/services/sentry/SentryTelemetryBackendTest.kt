package com.lightningkite.services.sentry

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.telemetry.TelemetryKeys
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.sentry.SentryEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SentryTelemetryBackendTest {
    object Wrapped : TelemetryBackend by TelemetryBackend.Noop {
        val reported = ArrayList<Throwable>()

        init {
            TelemetryBackend.Settings.register("recording") { _, _, _ -> Wrapped }
        }

        override fun reportError(throwable: Throwable, attributes: TelemetryAttributes) {
            reported += throwable
        }
    }

    @Test
    fun reportsToSentryAndTheWrappedBackend() = runTest {
        SentryTelemetryBackend.register()
        Wrapped
        val backend = TelemetryBackend.Settings("sentry://key@localhost/1/recording://")("telemetry", TestSettingContext())
            as SentryTelemetryBackend

        // Run our beforeSend, then keep the event instead of sending it.
        val options = backend.options
        val ours = options.beforeSend!!
        val sent = ArrayList<SentryEvent>()
        options.setBeforeSend { event, hint -> ours.execute(event, hint)?.let { sent += it }; null }

        val error = IllegalStateException("Failed calling https://user:hunter2@example.com/path")
        val span = SdkTracerProvider.builder().build().get("test").spanBuilder("request").startSpan()
        span.makeCurrent().use {
            backend.reportError(error, TelemetryAttributes { put(TelemetryKeys.Http.route, "/things/{id}") })
        }
        backend.flush()

        assertEquals(listOf<Throwable>(error), Wrapped.reported)
        val event = sent.single()
        assertEquals("/things/{id}", event.getTag("http.route"))
        assertEquals(span.spanContext.traceId, event.getTag("trace_id"))
        val message = event.exceptions!!.single().value!!
        assertFalse("hunter2" in message, message)
    }

    @Test
    fun urlWithoutWrappedBackendIsRejected() {
        SentryTelemetryBackend.register()
        assertFailsWith<IllegalArgumentException> {
            TelemetryBackend.Settings("sentry://key@localhost/1")("telemetry", TestSettingContext())
        }
    }
}
