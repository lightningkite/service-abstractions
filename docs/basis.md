# Basis: settings, services and context

Every service in this library is built the same way: a serializable `Settings` value holding a URL, turned into a running `Service` by calling it with a name and a `SettingContext`.  `basis` defines those pieces.  Read this first.

## Modules

| Module | Add it for |
|---|---|
| `basis` | `Setting`, `Service`, `SettingContext`, `TestSettingContext`, `SharedResources`, telemetry API, Terraform primitives.  Multiplatform; every other module depends on it. |
| `http-client` | The shared Ktor `client` every HTTP-based provider uses (CIO on JVM/Android/native, `Js` in browsers), plus `statusFailing()`. |
| `aws-client` | `AwsConnections`: the shared AWS SDK HTTP clients and concurrency limit used by every AWS module.  JVM. |
| `webhook-subservice` | `WebhookAdapter`, `HttpAdapter`, `WebSocketAdapter`: how services expose inbound webhooks (see [email.md](email.md#receive-email)). |
| `test` | `TerraformEmitterAwsTest`, a harness for testing Terraform helpers.  JVM. |

## Quick start

```kotlin
@Serializable
data class AppSettings(
    val database: Database.Settings = Database.Settings("ram"),
    val cache: Cache.Settings = Cache.Settings("ram"),
    val email: EmailService.Settings = EmailService.Settings("console"),
)

suspend fun main() {
    MongoDatabase; RedisCache            // register the schemes the config uses
    val settings = Json.decodeFromString<AppSettings>(File("settings.json").readText())
    val context = TestSettingContext()   // or your own SettingContext in production

    val database = settings.database("database", context)
    val cache = settings.cache("cache", context)
    val email = settings.email("email", context)
}
```

```json
{ "database": "mongodb://localhost/app", "cache": "redis://localhost:6379", "email": "console" }
```

Each `Settings` serializes as a bare string, so a settings file is a flat map of URLs.  Changing a URL swaps the implementation with no code change: `ram` in tests, `mongodb://...` in production.

## Common tasks

### Register the schemes you use

A URL's scheme (`mongodb` in `mongodb://...`) picks the implementation.  Implementations register their schemes when their class initializes, and being on the classpath isn't enough.  Reference each one once at startup, before parsing settings:

```kotlin
MongoDatabase                               // touching the class (its companion) registers mongodb://, mongodb+srv://, ...
AnthropicLlmSettings.ensureRegistered()     // AI and embedding modules expose an explicit call
OtelTelemetryBackend.register()             // so do the telemetry backends
```

Without it, parsing fails with `No handler mongodb ... available handlers are ram, ram-preload, delay`.  Each chapter lists what to reference; the [Settings URL reference](settings-url-reference.md) has every scheme.

- A URL without `://` uses the whole string as the scheme, so `ram`, `test` and `console` work bare.
- Registering a scheme twice throws, so a library can't hijack one.
- `X.Settings.supports("mongodb")` checks registration; `X.Settings.options` lists every registered scheme.

### Provide a SettingContext

`SettingContext` carries what every service shares:

| Member | Purpose |
|---|---|
| `projectName` | Names resources and telemetry |
| `publicUrl` | Base URL for links the service generates (files, webhooks) |
| `internalSerializersModule` | Serializers for your types (contextual and polymorphic) |
| `sharedResources` | Lazily created, shared clients and pools |
| `telemetryBackend` | Where traces and metrics go (default: discarded).  See [telemetry.md](telemetry.md) |
| `telemetrySanitization` | How personal data is redacted from telemetry (default `Strict`) |
| `clock` | Time source (default `Clock.System`) |

`TestSettingContext(internalSerializersModule = ..., clock = ...)` is a ready-made one: project `"Test"`, public URL `http://localhost:8080`, no telemetry.  Its `clock` is a `var`, so tests can swap it.  Lightning Server provides the production context; without it, implement the interface (see [telemetry.md](telemetry.md#quick-start)).

Use one context for all services: they share connection pools through it.

### Manage the lifecycle

```kotlin
database.connect()        // optional: warm up and fail fast at startup
val health: HealthStatus = database.healthCheck()   // level OK / WARNING / URGENT / ERROR, plus a message
database.disconnect()     // before a serverless runtime freezes, and on shutdown
```

Services connect lazily on first use, so `connect()` is optional.  On AWS Lambda or SnapStart, call `disconnect()` before the runtime freezes, so no half-open connections survive the thaw.  `healthCheckFrequency` says how often a monitor should call `healthCheck()`; some health checks do real work (email sends a message), so they run rarely.

### Share expensive objects

```kotlin
object PdfRenderer : SharedResources.Key<Renderer> {
    override fun setup(context: SettingContext): Renderer = Renderer(threads = 4)
}

val renderer: Renderer = context[PdfRenderer]   // created once per context
```

### Control time in tests

```kotlin
val context = TestSettingContext(clock = fixedClock)
withClock(fixedClock) { Clock.default().now() }   // coroutine-scoped clock for code without a context
```

### Write your own service

```kotlin
interface Thumbnailer : Service {
    suspend fun thumbnail(image: TypedData): TypedData

    @Serializable @JvmInline
    value class Settings(val url: String = "noop") : Setting<Thumbnailer> {
        companion object : UrlSettingParser<Thumbnailer>() {
            init { register("noop") { name, _, context -> NoopThumbnailer(name, context) } }
        }
        override fun invoke(name: String, context: SettingContext): Thumbnailer = parse(name, url, context)
    }
}
```

Each implementation module then registers its scheme from its own companion `init` (`Thumbnailer.Settings.register("imgproxy") { ... }`).  Wrap operations in `telemetryTrace` to get spans and metrics, as the built-in services do.

## Settings URLs

The `url` is parsed by the implementation that registered its scheme: hosts, userinfo and query parameters mean whatever that implementation says.  A few conventions hold across most of them:
- Credentials go in userinfo (`scheme://key:secret@host`) or query parameters.
- `${VAR}` expands an environment variable where the reference says so; elsewhere it stays literal.
- AWS modules without credentials in the URL use the default AWS credential chain.

The full list: [Settings URL reference](settings-url-reference.md).

## Testing

Build services from `TestSettingContext()` with their in-memory scheme (`ram`, `test`, `local`, `console`); each chapter's Testing section shows how to read back what a fake captured.  Most areas also ship a `-test` module of shared contract suites for implementation authors.

## Gotchas

- **Unregistered schemes**: see above; this is the most common startup failure.
- **Every setting is constructed at startup**, so an empty string (`"llm": ""`) fails with "No handler".  Omit settings you don't use instead.
- **`@Untested`** marks implementations not yet run against the live provider; using them needs an opt-in.
- **`AwsConnections.total`** caps concurrent AWS calls per context (default 1000); health checks report WARNING or ERROR as usage nears it.
