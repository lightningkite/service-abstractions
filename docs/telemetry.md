# Telemetry

Every service in this library records traces, RED metrics (rate, errors, duration) and errors through one vendor-neutral interface, `TelemetryBackend`, found on the `SettingContext`.  The default backend discards everything; `otel-jvm` exports through OpenTelemetry, and `telemetry-sentry` adds Sentry error reporting.

## Modules

| Module | Add it for |
|---|---|
| `basis` | `TelemetryBackend`, the `telemetryTrace`/metric functions, `noop`, and `logging` (JVM). |
| `telemetry-shared` | Client-safe telemetry value types (`TelemetryAttributes`, `TelemetryKey`, `TelemetryBatch`) for uploading client spans and logs.  `basis` exposes it. |
| `otel-jvm` | `OtelTelemetryBackend`: console, OTLP (gRPC/HTTP) and `dev` exporters.  JVM. |
| `telemetry-sentry` | `sentry://`: wraps another backend and also sends errors to Sentry.  JVM. |

## Quick start

```kotlin
OtelTelemetryBackend.register()   // registers log, console, otlp-*, dev
val telemetry = TelemetryBackend.Settings("otlp-grpc://localhost:4317")("telemetry", bootstrapContext)

val context = object : SettingContext {
    override val projectName = "my-app"
    override val publicUrl = "https://api.example.com"
    override val internalSerializersModule = EmptySerializersModule()
    override val sharedResources = SharedResources()
    override val telemetryBackend = telemetry
}
// Services built with this context now report spans and metrics.
```

Lightning Server builds this context for you from its `telemetry` setting.  Call `telemetry.flush()` before a serverless invocation ends.

`TelemetryBackend.Settings` is a data class, so beyond the URL it takes `sampling` (`ratio`, `parentBased`), `batching`, `spanLimits`, `logLimits`, `maxSpansPerSecond` and `maxLogsPerSecond`:

```json
{ "url": "otlp-grpc://collector:4317", "sampling": { "ratio": 0.1 } }
```

## Common tasks

### Instrument your own code

Anything `Namespaced` (every `Service`) can open spans and record metrics:

```kotlin
class InvoiceMailer(override val name: String, override val context: SettingContext) : Namespaced {
    private val sent = telemetryCounter("invoices.sent", MetricUnit.Occurrences, defaultDimensions = setOf())

    suspend fun send(invoice: Invoice) = telemetryTrace("send", TelemetryAttributes { put(invoiceId, invoice.id) }) { span ->
        mail(invoice)
        span.enrich(TelemetryAttributes { put(recipientCount, invoice.recipients.size.toLong()) })
        sent.increment()
    }
}
```

- `telemetryTrace` opens a span named `<name>.<opName>`, parented through the coroutine context, and records RED metrics tagged with the service, operation and outcome.
- `attributes` go on the span.  Only keys listed in `dimensions` also tag the metrics: keep that set small, since every distinct value is a new time series.
- `span.enrich(...)` adds attributes found during the operation; `span.log(level) { ... }` writes a log record on the span.
- Other instruments: `telemetryHistogram`, `telemetryInFlight`, `telemetryGauge`.
- `telemetryAttributes(attrs) { ... }` adds attributes to everything recorded inside, without a span.
- `context.reportException(e)` logs an error you caught and reports it to the backend.

### Continue or suppress traces

- `withContext(TraceParent(headerValue)) { ... }`: the next root span continues the caller's W3C `traceparent`.
- `withContext(Untraced) { ... }`: nothing beneath is traced (health checks, noisy polling).

### Accept client telemetry

Clients record `TelemetryBatch`es (spans and logs, no metrics) with the `telemetry-shared` types and upload them; the server passes each to `telemetryBackend.ingest(batch)`.  OpenTelemetry exports them with their own ids and clock-corrected timestamps.

### Keep personal data out

`SettingContext.telemetrySanitization` (default `TelemetrySanitization.Strict`) hashes cache keys, trims file paths and URLs, and redacts phone numbers and exception messages before they're recorded.  Email subjects and addresses are reduced to domains.

## Settings URLs

| Scheme | Module | Register with | Example |
|---|---|---|---|
| `noop` | `basis` | (built in) | `noop` (the default) |
| `logging`, `logging-nocolor` | `basis` (JVM) | `LoggingTelemetryBackend.register()` | `logging` (span trees on stdout) |
| `log`, `console` | `otel-jvm` | `OtelTelemetryBackend.register()` | `console` |
| `otlp-grpc`, `otlp-http`, `otlp-https` | `otel-jvm` | `OtelTelemetryBackend.register()` | `otlp-http://otel-collector:4318` |
| `dev`, `debounced-dev` | `otel-jvm` | `OtelTelemetryBackend.register()` | `dev://./local/telemetry.log?color=false` |
| `sentry` | `telemetry-sentry` | `SentryTelemetryBackend.register()` | `sentry://<key>@o0.ingest.sentry.io/<projectId>/otlp-grpc://localhost:4317` |

Details: [Settings URL reference: Telemetry](settings-url-reference.md#telemetry).

- `otlp-grpc` and `otlp-http` are plaintext; use `otlp-https` for TLS.  None take auth headers: send through a local collector that adds them.
- `dev` writes readable traces for local work and samples everything.
- `sentry://` is your DSN with `https` replaced by `sentry`, then the URL of the backend it wraps.  Attributes become Sentry tags.

## Testing

`TestSettingContext` uses `noop`.  To assert on telemetry, implement `TelemetryBackend` with a recording fake and return it from your own `SettingContext`.

## Gotchas

- **`"dev"` with no module**: the `dev`, `otlp-*` and `console` schemes live in `otel-jvm`; without `OtelTelemetryBackend.register()` they fail with "No handler".
- **Remote parents don't decide sampling**: an incoming `traceparent` links the trace, but your `sampling` settings still apply.
- **Custom backends** must implement `flush()` and `ingest()`.
- **OpenTelemetry stays in `otel-jvm`**: the build's `verifyNoDirectOtel` check fails if production code elsewhere imports `io.opentelemetry`.
