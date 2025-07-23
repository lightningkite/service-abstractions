# Migration Guide: Transitioning from Lightning Server to Service Abstractions

This guide provides detailed instructions for migrating interfaces from Lightning Server to the Service Abstractions project. It builds upon the principles outlined in [transition.md](transition.md) with concrete examples and patterns based on the successful migration of the `Cache` interface.

## Core Migration Principles

1. **Interface Evolution**: Move from traditional interfaces to a more structured approach that leverages Kotlin's modern features
2. **Settings Transformation**: Replace serializable objects with value classes implementing `Setting<T>`
3. **Metrics Integration**: Implement metrics tracking through base abstract classes
4. **Dependency Injection**: Move away from static implementations to constructor-based dependency injection
5. **Explicit Visibility**: Use explicit visibility modifiers for all declarations

## Step-by-Step Migration Process

### 1. Interface Migration

#### Before (Lightning Server):
```kotlin
interface Cache : HealthCheckable, Metricable<Cache> {
    suspend fun <T> get(key: String, serializer: KSerializer<T>): T?
    suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration? = null)
    // Other methods...
}
```

#### After (Service Abstractions):
```kotlin
public interface Cache : Service {
    public val serializersModule: SerializersModule
    
    public suspend fun <T> get(key: String, serializer: KSerializer<T>): T?
    public suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration? = null)
    // Other methods...
}
```

**Key Changes:**
- Add `public` visibility modifier to the interface and all its members
- Replace `HealthCheckable` and `Metricable<T>` with `Service` interface
- Add any necessary properties (like `serializersModule`) that implementations will need
- Move default implementations to abstract base classes when possible

### 2. Settings Transformation

#### Before (Lightning Server):
```kotlin
@Serializable
data class CacheSettings(
    val url: String = "local",
    @SerialName("uri") val legacyUri: String? = null,
    val connectionString: String? = null,
    val databaseNumber: Int? = null
) : () -> Cache {
    companion object : Pluggable<CacheSettings, Cache>() {
        init {
            register("local") { LocalCache }
        }
    }
    
    override fun invoke(): Cache = parse(url.substringBefore("://"), this)
}
```

#### After (Service Abstractions):
```kotlin
@Serializable
@JvmInline
public value class Settings(
    public val url: String = "ram"
) : Setting<Cache> {
    public companion object : UrlSettingParser<Cache>() {
        init {
            register("ram-unsafe") { url, context -> MapCache(mutableMapOf(), context) }
            platformSpecificCacheSettings()
        }
    }
    
    override fun invoke(context: SettingContext): Cache {
        return parse(url, context)
    }
}
```

**Key Changes:**
- Convert from a data class to a value class with `@JvmInline` annotation
- Nest the settings class inside the parent interface
- Implement `Setting<T>` instead of `() -> T`
- Use a URL-based configuration approach with `UrlSettingParser`
- Accept a `SettingContext` parameter in the `invoke` method
- Remove legacy parameters and simplify to a single `url` parameter when possible

### 3. Metrics Implementation

#### Before (Lightning Server):
```kotlin
// Decorator pattern for metrics
class MetricsCache(val wraps: Cache, metricsKeyName: String): Cache by wraps {
    val metricKey = MetricType("$metricsKeyName Wait Time", MetricUnit.Milliseconds)
    val countMetricKey = MetricType("$metricsKeyName Call Count", MetricUnit.Count)
    
    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? = 
        Metrics.addPerformanceToSumPerHandler(metricKey, countMetricKey) {
            wraps.get(key = key, serializer = serializer)
        }
    
    // Other overridden methods with metrics...
}

// Usage in interface
override fun withMetrics(metricsKeyName: String): Cache = MetricsCache(this, metricsKeyName)
```

#### After (Service Abstractions):
```kotlin
// Abstract base class for metrics
public abstract class MetricTrackingCache: Cache {
    private val readMetric = performanceMetric("read")
    private val writeMetric = performanceMetric("write")
    private val modifyFailuresMetric = countMetric("modifyFailures")
    
    protected abstract suspend fun <T> getInternal(key: String, serializer: KSerializer<T>): T?
    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        return readMetric.measure { getInternal(key, serializer) }
    }
    
    // Other methods with metrics tracking...
}

// Implementation extends the base class
public open class MapCache(
    public val entries: MutableMap<String, Entry>,
    override val context: SettingContext,
) : MetricTrackingCache() {
    // Implementation of abstract methods...
}
```

**Key Changes:**
- Replace decorator pattern with an abstract base class
- Define protected abstract methods for the actual implementation
- Implement public methods that add metrics tracking around the abstract methods
- Use more granular metrics (read vs. write operations)
- Use the new metrics API with `measure()` and `report()` functions

### 4. Dependency Injection

#### Before (Lightning Server):
```kotlin
open class LocalCache(val entries: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()) : Cache {
    companion object: LocalCache()
    // Implementation...
}

// Usage
register("local") { LocalCache }
```

#### After (Service Abstractions):
```kotlin
public open class MapCache(
    public val entries: MutableMap<String, Entry>,
    override val context: SettingContext,
) : MetricTrackingCache() {
    override val serializersModule: SerializersModule get() = context.serializersModule
    // Implementation...
}

// Usage
register("ram-unsafe") { url, context -> MapCache(mutableMapOf(), context) }
```

**Key Changes:**
- Remove static companion object instances
- Accept dependencies through constructor parameters
- Use the `SettingContext` to access shared resources
- Make implementations more testable and configurable

### 5. Health Check Implementation

#### Before (Lightning Server):
```kotlin
override suspend fun healthCheck(): HealthStatus {
    return try {
        set("health-check-test-key", true)
        if (get<Boolean>("health-check-test-key") == true) {
            HealthStatus(HealthStatus.Level.OK)
        } else {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Could not retrieve set property")
        }
    } catch (e: Exception) {
        HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
    }
}
```

#### After (Service Abstractions):
```kotlin
override suspend fun healthCheck(): HealthStatus {
    return try {
        set("health-check-test-key", Clock.System.now())
        // We check if the write occurred recently to ensure we're not just seeing stale information
        if (get<Instant>("health-check-test-key").let { it != null && it > Clock.System.now().minus(10.seconds) }) {
            HealthStatus(HealthStatus.Level.OK)
        } else {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Could not retrieve set property")
        }
    } catch (e: Exception) {
        HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
    }
}
```

**Key Changes:**
- Improve health check logic to verify recency of data
- Use `Clock.System.now()` for platform-independent time
- Implement more robust checks that verify the service is working correctly

### 6. Extension Functions

#### Before (Lightning Server):
```kotlin
suspend inline fun <reified T : Any> Cache.get(key: String): T? {
    return get(key, Serialization.Internal.json.serializersModule.serializer<T>())
}

// Other extension functions...
```

#### After (Service Abstractions):
```kotlin
// Move extension functions to a separate file
public suspend inline fun <reified T> Cache.get(key: String): T? {
    return get(key, serializersModule.serializer())
}

// Other extension functions...
```

**Key Changes:**
- Move extension functions to a separate file (e.g., `Cache.ext.kt`)
- Use the instance's `serializersModule` property instead of a global serializer
- Add explicit visibility modifiers

## Common Patterns and Anti-Patterns

### Patterns to Follow

1. **Interface Hierarchy**: Extend the `Service` interface for all service abstractions
2. **Metrics Base Classes**: Create abstract base classes for metrics tracking
3. **Context-Based Configuration**: Use `SettingContext` for accessing shared resources
4. **Platform Independence**: Avoid platform-specific dependencies when possible
5. **Explicit Visibility**: Always use explicit visibility modifiers

### Anti-Patterns to Avoid

1. **Static Singletons**: Avoid companion object instances and static methods
2. **Global State**: Don't rely on global state or static configuration
3. **Platform Dependencies**: Minimize direct dependencies on JVM-specific libraries
4. **Implicit Visibility**: Don't rely on Kotlin's default visibility modifiers

## Serialization Handling

- Each service interface should have a `serializersModule` property
- Implementations should get the serialization module from the context
- Extension functions should use the instance's serialization module

## Testing Considerations

- Write tests that verify both the interface contract and specific implementations
- Use dependency injection to make testing easier
- Test health checks and metrics tracking
- Ensure platform-independent code works across different Kotlin targets

## Migration Checklist

- [ ] Update interface to extend `Service` and add explicit visibility modifiers
- [ ] Create a nested `Settings` value class implementing `Setting<T>`
- [ ] Implement a metrics tracking abstract base class
- [ ] Update implementations to use dependency injection
- [ ] Move extension functions to a separate file
- [ ] Update health check implementation
- [ ] Write or update tests
- [ ] Verify platform independence

## Example: Complete Migration

For a complete example of a successful migration, compare:
- Old: `old-lightning-server/server-core/src/main/kotlin/com/lightningkite/lightningserver/cache/Cache.kt`
- New: `cache/src/commonMain/kotlin/com/lightningkite/serviceabstractions/cache/Cache.kt`

This guide should help you successfully migrate interfaces from Lightning Server to the Service Abstractions project while following best practices and maintaining consistency across the codebase.