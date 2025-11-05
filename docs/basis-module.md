# Basis Module

The `basis` module provides core abstractions and infrastructure for the service-abstractions library. This module defines the fundamental interfaces and patterns used across all service implementations.

## Core Concepts

### Service Interface

Every infrastructure service (database, cache, file storage, etc.) implements the `Service` interface:

```kotlin
interface Service {
    val name: String
    val context: SettingContext
    suspend fun connect()
    suspend fun disconnect()
    val healthCheckFrequency: Duration
    suspend fun healthCheck(): HealthStatus
}
```

**Key Features:**
- **Lifecycle Management**: `connect()` and `disconnect()` methods support serverless environments
- **Health Monitoring**: Built-in health check support for monitoring systems
- **Context Access**: Access to shared configuration and resources

**Example Implementation:**
```kotlin
class MyDatabaseService(
    override val name: String,
    override val context: SettingContext,
    private val connectionString: String
) : Service {
    override suspend fun healthCheck(): HealthStatus {
        return try {
            // Perform lightweight health check
            database.ping()
            HealthStatus(level = HealthStatus.Level.OK)
        } catch (e: Exception) {
            HealthStatus(
                level = HealthStatus.Level.ERROR,
                additionalMessage = e.message
            )
        }
    }
}
```

### Setting Interface

The `Setting<T>` interface represents a factory for creating service instances from configuration:

```kotlin
interface Setting<T> {
    operator fun invoke(name: String, context: SettingContext): T
}
```

**Usage:**
```kotlin
@Serializable
data class ServerSettings(
    val database: Database.Settings = Database.Settings("mongodb://localhost"),
    val cache: Cache.Settings = Cache.Settings("redis://localhost")
)

// Later, instantiate services:
val context = createSettingContext()
val db = settings.database("main-db", context)
val cache = settings.cache("app-cache", context)
```

### SettingContext

`SettingContext` provides shared configuration and resources to all services:

```kotlin
interface SettingContext {
    val projectName: String                            // Application name
    val publicUrl: String                              // Public-facing URL
    val internalSerializersModule: SerializersModule   // Custom serializers
    val openTelemetry: OpenTelemetry?                  // Optional telemetry
    val clock: Clock                                    // Time source (mockable)
    val sharedResources: SharedResources               // Resource pool
}
```

**Creating a Context:**
```kotlin
val context = object : SettingContext {
    override val projectName = "my-application"
    override val publicUrl = "https://api.example.com"
    override val internalSerializersModule = SerializersModule {
        polymorphic(MyInterface::class) {
            subclass(MyImpl::class)
        }
    }
    override val openTelemetry = OpenTelemetry.get()
    override val sharedResources = SharedResources()
}
```

### SharedResources

`SharedResources` provides a lazy-initialized resource pool for expensive objects:

```kotlin
// Define a resource key
object HttpClientKey : SharedResources.Key<HttpClient> {
    override fun setup(context: SettingContext): HttpClient {
        return HttpClient {
            install(Timeout) { requestTimeoutMillis = 30_000 }
        }
    }
}

// Access from services
class MyService(override val context: SettingContext) : Service {
    private val httpClient = context[HttpClientKey]
    // httpClient is shared across all services
}
```

**Common Use Cases:**
- HTTP client instances (connection pooling)
- Thread pools for blocking I/O
- SSL/TLS contexts
- Rate limiters and circuit breakers

**⚠️ Thread Safety:** The implementation uses `ConcurrentMutableMap` which is thread-safe on JVM/Android but may allow `setup()` to be called multiple times concurrently during the getOrPut operation.

### ConcurrentMutableMap

Platform-specific concurrent map factory for thread-safe collections:

```kotlin
val serviceRegistry = ConcurrentMutableMap<String, Service>()

// Thread-safe on JVM/Android
serviceRegistry["user-service"] = userService
val service = serviceRegistry["user-service"]
```

**Platform Behavior:**
- **JVM/Android**: Returns `java.util.concurrent.ConcurrentHashMap` (fully thread-safe)
- **JavaScript**: Returns regular `HashMap` (single-threaded, no concurrency needed)
- **Native**: Currently returns non-thread-safe `HashMap` ⚠️ (see implementation TODO)

**Use Cases:**
- Service registries with concurrent access
- Shared caches accessed from multiple threads
- Any map that needs thread-safe operations

**⚠️ Native Platform:** The Kotlin/Native implementation currently returns a regular HashMap which is NOT thread-safe. Use with caution on Native platforms with multi-threading.

### URL-Based Configuration

`UrlSettingParser` enables URL-based service selection:

```kotlin
object CacheParser : UrlSettingParser<Cache>()

init {
    CacheParser.register("redis") { name, url, context ->
        RedisCache(name, url, context)
    }
    CacheParser.register("ram") { name, url, context ->
        InMemoryCache(name, context)
    }
}

// Parse URLs to create services
val cache = CacheParser.parse("session-cache", "redis://localhost:6379/0", context)
```

**URL Schemes:**
- `ram://` or `ram` - In-memory implementation
- `mongodb://host:port/database` - MongoDB
- `redis://host:port/db` - Redis
- `postgresql://user:pass@host/db` - PostgreSQL
- `s3://bucket/prefix` - AWS S3

## Testing Support

### TestSettingContext

Minimal context implementation for testing:

```kotlin
@Test
fun testMyService() = runTest {
    val context = TestSettingContext()
    val service = MyService("test", context)

    // Test service operations
}
```

**With Custom Clock:**
```kotlin
@Test
fun testTimeDependent() = runTest {
    val fixedClock = object : Clock {
        override fun now() = Instant.parse("2025-01-01T00:00:00Z")
    }
    val context = TestSettingContext(clock = fixedClock)

    // Service will use fixed time
}
```

### Clock Testing

Test time-dependent code with custom clocks:

```kotlin
@Test
fun testExpiration() = runTest {
    val testClock = TestClock(Instant.parse("2025-01-01T00:00:00Z"))

    withClock(testClock) {
        val token = createToken(expiresIn = 1.hours)
        assertFalse(token.isExpired())

        testClock.advance(2.hours)
        assertTrue(token.isExpired())
    }
}
```

## Terraform Support

The basis module includes infrastructure-as-code support for generating Terraform configuration:

### TerraformJsonObject

DSL for building Terraform JSON configurations:

```kotlin
val config = terraformJsonObject {
    "resource.aws_instance.example" {
        "ami" - "ami-12345678"
        "instance_type" - "t2.micro"
        "tags" {
            "Name" - "example-instance"
        }
    }
}
```

### TerraformEmitter

Interface for services to declare their infrastructure needs:

```kotlin
interface TerraformEmitter {
    fun require(provider: TerraformProviderImport)
    fun emit(context: String?, action: TerraformJsonObject.()->Unit)
    fun fulfillSetting(settingName: String, element: JsonElement)
}
```

Services can implement Terraform generation to automate infrastructure provisioning.

## Platform Support

- **JVM**: Full support including OpenTelemetry integration
- **JavaScript**: Core functionality, no OpenTelemetry
- **Native (iOS, macOS)**: Core functionality, no OpenTelemetry
- **Android**: Full support

## Best Practices

1. **Always use SettingContext.clock** instead of `Clock.System.now()` for testability
2. **Implement health checks** that are lightweight and test actual connectivity
3. **Call connect()** during app startup in serverless environments
4. **Call disconnect()** before Lambda returns to prevent connection issues
5. **Share expensive resources** via SharedResources (HTTP clients, connection pools)
6. **Use URL schemes** for configuration to enable easy environment switching

## API Stability

The basis module forms the foundation of the library and is designed for stability. However:

- `@Untested` annotation marks experimental features
- Thread-safety issues in `SharedResources` are documented
- Future versions may add (but not remove) functionality

## See Also

- [Database Module](database-module.md) - Database abstraction
- [Cache Module](cache-module.md) - Cache abstraction
- [Architecture Overview](../CLAUDE.md) - High-level architecture
