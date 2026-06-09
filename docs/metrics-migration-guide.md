# Metrics Migration Guide — coroutine-first metrics API

Migrate services off the old telemetry helpers (`OpenTelemetrySub.span(...)`, `RedMetrics`/`redMetrics`/
`recordOperation`) onto the coroutine-first metrics API in
`basis/src/commonMain/kotlin/com/lightningkite/services/newmetrics.kt`. Redis is the exemplar; this
guide generalizes that diff.

## The core API (what you call)

A service is a `Namespaced` (has `name` + `context`). On it:

- `metricsTrace(opName, attributes = ..., dimensions = ...) { span -> ... }`
  Opens a span named `"<owner.name>.<opName>"`, parents it via the coroutine context, applies the
  full `attributes` bag to the span, records RED `{system=name, operation=opName, outcome}` plus any
  promoted `dimensions`, and ends it. Replaces both `otel.span(...)` and `metrics.recordOperation(...)`.
- `span.enrich(MetricAttributes(mapOf(...)))` — attach attributes discovered *during* the op (rows
  returned, `cache.hit`, sanitized query). Lands on the span; also readable as a RED dimension if its
  key is listed in `dimensions`.
- `metricsAttributes(attributes) { ... }` — enrich the ambient bag without opening a span (rare).
- Instruments (only if the service used counters/gauges/in-flight — see below):
  `metricsHistogram(name, unit, defaultDimensions)`, `metricsCounter(...)`, `metricsInFlight(...)`,
  `metricsGauge(name, unit, defaultDimensions) { sampleLong }`.
- `MetricAttributes(mapOf("k" to v))`, `MetricUnit.{Seconds,Bytes,Occurrences}`.

All of these are no-ops when `context.metricsBackend == null`, so no null-checks at call sites.

## Before → after (from the Redis diff)

### Direct span user (`otel.span`)

```kotlin
// BEFORE
private val otel: OpenTelemetrySub? = context.openTelemetry?.get("cache-redis")
...
override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? =
    otel.span("cache.get", configure = {
        setSpanKind(SpanKind.CLIENT)
        setAttribute("cache.system", "redis")
        setAttribute("cache.key", TelemetrySanitization.hashCacheKey(key))
    }) { span ->
        val result = lettuceConnection.get(key).awaitFirstOrNull()?.let { json.decodeFromString(serializer, it) }
        span?.setAttribute("cache.hit", result != null)
        result
    }
```

```kotlin
// AFTER
private fun spanAttributes(key: String): MetricAttributes = MetricAttributes(
    mapOf(
        "cache.system" to "redis",
        "cache.key" to TelemetrySanitization.hashCacheKey(key),
    )
)

override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? =
    metricsTrace("get", attributes = spanAttributes(key), dimensions = setOf("cache.hit")) { span ->
        val raw = lettuceConnection.get(key).awaitFirstOrNull()
        span.enrich(MetricAttributes(mapOf("cache.hit" to (raw != null))))
        raw?.let { json.decodeFromString(serializer, it) }
    }
```

Notes on the transform:
- Static `configure {}` attributes become the `attributes =` bag (a plain map, no `SpanBuilder`).
- `span?.setAttribute(...)` for result-derived values becomes `span.enrich(MetricAttributes(...))`
  (`span` is now non-null, no `?`).
- `setSpanKind(SpanKind.CLIENT)` has no direct equivalent and is dropped (the backend owns span kind);
  do not try to re-add it.

### RedMetrics user (`recordOperation`)

```kotlin
// BEFORE
private val metrics: RedMetrics = otel.redMetrics(system = "mongodb")
...
override suspend fun insert(models): List<Model> = metrics.recordOperation("insert") { span -> ... }
```

```kotlin
// AFTER  (no per-service instrument field; the backend owns RED instruments keyed by owner.name)
override suspend fun insert(models): List<Model> = metricsTrace("insert") { span -> ... }
```

- Drop the `private val metrics = otel.redMetrics(system = ...)` field entirely — `metricsTrace`
  records RED automatically using `owner.name` as `system`.
- `recordOperation("op", metricAttributes = ...)` → `metricsTrace("op", attributes = ...)` and/or
  `dimensions = setOf(...)` for the low-card keys that were in `metricAttributes`.
- `configureSpan = { setAttribute(...) }` static attrs → `attributes =`.
- `cacheHit(hit)` helper → `dimensions = setOf("cache.hit")` + `span.enrich("cache.hit" to hit)`.

## Conventions to follow

- **Keep operation names.** Pass the same op string you used before (`"insert"`, `"get"`, `"publish"`,
  `"healthCheck"`, etc.). Many old call sites used a dotted span name (`"cache.get"`, `"pubsub.publish"`,
  `"file.list"`). Drop the prefix and pass just the operation (`"get"`, `"publish"`, `"list"`) — the
  backend produces the full span name as `"<owner.name>.<op>"`.
- **Accept the span-name change.** Span/metric names now derive from the owner instance name, e.g.
  `"telemetry-test.get"` instead of `"cache.get"`. Tests assert the new form (see RedisTelemetryTest).
- **Full attributes via `attributes =` / `enrich`.** Anything that used to be a span attribute goes in
  the `attributes` bag (known up front) or `span.enrich(...)` (discovered during the op). High
  cardinality is fine on the span.
- **Only low-card keys in `dimensions`.** `dimensions` promotes a key onto the RED counter/histogram —
  keep this set tiny and bounded (`cache.hit`, `outcome`-like flags). Never put ids/keys/queries there.
- **Hash/sanitize high-card values** exactly as before via `TelemetrySanitization.*` (still in otel-jvm,
  keep using it).
- **Remove now-unused imports** of `com.lightningkite.services.otel.*`: `OpenTelemetrySub`,
  `otel.span`/`spanBlocking`, `redMetrics`/`RedMetrics`/`cacheHit`, `get`/`otelGet`, and stray
  `io.opentelemetry.api.trace.*` (`SpanKind`, `SpanBuilder`, `Span`). Add
  `com.lightningkite.services.MetricAttributes` and `com.lightningkite.services.metricsTrace`.
- **KEEP driver/SDK instrumentation as-is.** Anything that passes `context.openTelemetry` *into* a
  third-party client (Lettuce `LettuceTelemetry.create(...).newTracing()`, AWS `AwsSdkTelemetry`,
  Mongo `OtelMongoCommandListener`/command-listener parenting) stays. It is the SDK wiring that makes
  driver command spans parent under your `metricsTrace` span; do not remove it.
- **KEEP `context.reportException(...)`.** That API is unchanged and out of scope. Leave every
  `reportException` call exactly where it is.

## Counters / gauges / in-flight

Most migrating files only used spans + RED, so they need nothing here. If a service used the old
`RedMetrics.poolGauge(...)` or a raw `OpenTelemetrySub` counter/gauge:

- Pool/connection gauge → `metricsGauge(name, MetricUnit.Occurrences, defaultDimensions = emptySet()) { currentValue }`.
  Returns `AutoCloseable`; close it on `disconnect()`. The sample lambda runs outside coroutines, so it
  carries no ambient attributes.
- A standalone counter → hold `val c = metricsCounter(name, unit, defaultDimensions)` in a `val`, call
  `c.increment()`. (Note `increment`/`record` are `suspend`.)
- A histogram for sizes/counts (not durations — durations come free from `metricsTrace`) →
  `val h = metricsHistogram(name, MetricUnit.Bytes, defaultDimensions)`, `h.record(amount)`.
- In-flight / concurrency gauge → `val g = metricsInFlight(name, defaultDimensions)`;
  `val lease = g.lease()` then `lease.release()` in a `finally`.

`defaultDimensions` is the cardinality firewall: only the ambient keys named here are projected onto
the instrument. Keep it small.

## Running a module's tests

```bash
# JVM tests for one module (fastest)
./gradlew :<module>:jvmTest

# A single telemetry test class
./gradlew :cache-redis:jvmTest --tests "RedisTelemetryTest"

# Whole module build (compile check across platforms)
./gradlew :<module>:build
```

Modules that have dedicated telemetry tests to run after migrating them:
- `cache-redis` → `RedisTelemetryTest` (the exemplar; already migrated)
- `pubsub-redis` → `RedisPubSubTelemetryTest`
- `database-mongodb` → `SpanParentingTest`, `PoolListenerTest`

Most other modules assert telemetry only indirectly (or not at all); for those, `:module:jvmTest`
plus a `:module:build` compile check is the bar. After migrating, also run `./gradlew :basis:build`
if you touched shared code.
