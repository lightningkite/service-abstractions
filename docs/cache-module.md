# Cache Module

The `cache` module provides an abstract interface for key-value caching with implementations for various backends (Redis, Memcached, in-memory, etc.). All values are serialized using KotlinX Serialization for type-safe storage and retrieval.

## Getting Started

### Basic Setup

```kotlin
// In your configuration
@Serializable
data class ServerSettings(
    val cache: Cache.Settings = Cache.Settings("redis://localhost:6379")
)

// Instantiate the cache
val context = createSettingContext()
val cache = settings.cache("session-cache", context)
```

### Simple Operations

```kotlin
// Store a value
cache.set("user:123", user, timeToLive = 1.hours)

// Retrieve a value
val cachedUser = cache.get<User>("user:123")

// Remove a value
cache.remove("user:123")
```

## Core Interface

The `Cache` interface provides these operations:

### get()
Retrieves a value from the cache:

```kotlin
suspend fun <T> get(key: String, serializer: KSerializer<T>): T?

// With type inference:
val user: User? = cache.get("user:123")
```

Returns `null` if:
- The key doesn't exist
- The value has expired
- Deserialization fails

### set()
Stores a value in the cache:

```kotlin
suspend fun <T> set(
    key: String,
    value: T,
    serializer: KSerializer<T>,
    timeToLive: Duration? = null
)

// With type inference:
cache.set("user:123", user, timeToLive = 30.minutes)
```

- Overwrites any existing value
- `timeToLive = null` means no expiration
- Use reasonable TTLs to prevent stale data and memory bloat

### setIfNotExists()
Atomically stores a value only if the key doesn't exist:

```kotlin
val wasSet = cache.setIfNotExists("lock:process", "worker-1", timeToLive = 10.seconds)
if (wasSet) {
    // We acquired the lock
    processTask()
}
```

**Use Cases:**
- Distributed locking
- Ensuring only one instance initializes data
- Rate limiting (first request sets a key)

**Note:** Atomic behavior depends on implementation. MapCache may have race conditions.

### modify()
Atomically modifies a value using compare-and-swap:

```kotlin
cache.modify<Counter>("page-views", maxTries = 5) { current ->
    current?.copy(count = current.count + 1) ?: Counter(1)
}
```

**How it works:**
1. Reads current value
2. Calls modification function
3. Writes back only if value hasn't changed
4. Retries up to `maxTries` times on conflicts

**Important:** The default implementation has a race condition - it reads the value twice. Use implementations that override with proper CAS operations for production use.

**Use Cases:**
- Incrementing counters with validation
- Updating complex objects atomically
- Implementing optimistic locking patterns

### add()
Atomically increments a numeric value:

```kotlin
cache.add("page-views", 1, timeToLive = 1.days)
cache.add("downloads", 5)  // Increment by 5
cache.add("count", -1)     // Decrement
```

- Creates the key with the given value if it doesn't exist
- Works with all numeric types (Byte, Short, Int, Long, Float, Double)
- Non-numeric values are treated as 0 in MapCache

### remove()
Removes a key from the cache:

```kotlin
cache.remove("user:123")
```

Idempotent - removing a non-existent key succeeds silently.

## Available Implementations

### In-Memory (ram)

Thread-safe in-memory cache using `ConcurrentHashMap` on JVM:

```kotlin
Cache.Settings("ram")
// or
Cache.Settings("ram://")
```

**Characteristics:**
- Fast (no network overhead)
- Data lost on restart
- Memory-limited
- Thread-safe on JVM/Android
- Good for: Session data, rate limiting, temporary caching

### In-Memory Unsafe (ram-unsafe)

Single-threaded in-memory cache:

```kotlin
Cache.Settings("ram-unsafe")
```

**Characteristics:**
- Fastest (no synchronization overhead)
- **Not thread-safe** - use only in single-threaded contexts
- Good for: Single-threaded applications, testing

### Redis

Distributed cache using Redis (requires `cache-redis` module):

```kotlin
Cache.Settings("redis://localhost:6379/0")
```

**Characteristics:**
- Persistent (configurable)
- Shared across multiple instances
- Supports clustering
- Good for: Multi-instance applications, session sharing, distributed locking

### Memcached

Distributed cache using Memcached (requires `cache-memcached` module):

```kotlin
Cache.Settings("memcached://localhost:11211")
```

**Characteristics:**
- Pure in-memory (never persists)
- Simple and fast
- LRU eviction
- Good for: Simple caching scenarios, high-throughput workloads

### DynamoDB

AWS DynamoDB-backed cache (requires `cache-dynamodb` module):

```kotlin
Cache.Settings("dynamodb://table-name")
```

**Characteristics:**
- Fully managed
- Automatic scaling
- Global replication available
- TTL support
- Good for: AWS-native applications, serverless architectures

## CacheHandle

Type-safe handle for repeated operations on the same key:

```kotlin
val userCache: CacheHandle<User> = { cache }["user:123"]

// Cleaner API for repeated operations
userCache.set(user, timeToLive = 1.hours)
val cachedUser = userCache.get()
userCache.remove()
```

**Benefits:**
- No need to specify key and serializer repeatedly
- Type-safe - compile-time checking of value type
- Cleaner code for frequently accessed keys

**Creating Handles:**
```kotlin
val cache: () -> Cache = { myCache }

// Type inferred from variable declaration
val userHandle: CacheHandle<User> = cache["user:123"]
val counterHandle: CacheHandle<Int> = cache["page-views"]
```

## Extension Functions

The module provides type-safe extensions using reified generics:

```kotlin
// Type inferred from context
val user: User? = cache.get("user:123")

// Instead of:
val user: User? = cache.get("user:123", context.internalSerializersModule.serializer<User>())
```

All operations have reified variants:
- `get<T>(key: String)`
- `set<T>(key: String, value: T, timeToLive: Duration?)`
- `setIfNotExists<T>(key: String, value: T, timeToLive: Duration?)`
- `modify<T>(key: String, maxTries: Int, timeToLive: Duration?, modification: (T?) -> T?)`

## Common Patterns

### Cache-Aside Pattern

```kotlin
suspend fun getUser(id: String): User {
    return cache.get<User>("user:$id") ?: run {
        val user = database.findUser(id)
        cache.set("user:$id", user, timeToLive = 15.minutes)
        user
    }
}
```

### Write-Through Pattern

```kotlin
suspend fun updateUser(user: User) {
    database.updateUser(user)
    cache.set("user:${user.id}", user, timeToLive = 15.minutes)
}
```

### Distributed Locking

```kotlin
suspend fun processWithLock(taskId: String) {
    val lockKey = "lock:$taskId"
    val acquired = cache.setIfNotExists(lockKey, "worker-1", timeToLive = 30.seconds)

    if (!acquired) {
        // Another worker is processing this task
        return
    }

    try {
        processTask(taskId)
    } finally {
        cache.remove(lockKey)
    }
}
```

### Rate Limiting

```kotlin
suspend fun isRateLimited(userId: String): Boolean {
    val key = "rate-limit:$userId:${Clock.System.now().epochSeconds / 60}" // Per minute
    cache.add(key, 1, timeToLive = 1.minutes)

    val count = cache.get<Int>(key) ?: 0
    return count > 100 // Max 100 requests per minute
}
```

### Session Storage

```kotlin
@Serializable
data class UserSession(
    val userId: String,
    val loginTime: Instant,
    val permissions: List<String>
)

suspend fun storeSession(sessionId: String, session: UserSession) {
    cache.set("session:$sessionId", session, timeToLive = 24.hours)
}

suspend fun getSession(sessionId: String): UserSession? {
    return cache.get<UserSession>("session:$sessionId")
}
```

## Testing

Use in-memory cache for tests:

```kotlin
@Test
fun testCaching() = runTest {
    val context = TestSettingContext()
    val cache = Cache.Settings("ram")("test-cache", context)

    // Test operations
    cache.set("key", "value", timeToLive = 1.hours)
    assertEquals("value", cache.get<String>("key"))
}
```

For testing MapCache specifically (including expiration):

```kotlin
@Test
fun testExpiration() = runTest {
    val testClock = TestClock(Instant.parse("2025-01-01T00:00:00Z"))
    val context = TestSettingContext(clock = testClock)
    val cache = MapCache("test", ConcurrentMutableMap(), context)

    cache.set("key", "value", timeToLive = 1.hours)
    assertNotNull(cache.get<String>("key"))

    testClock.advance(2.hours)
    assertNull(cache.get<String>("key"))
}
```

## Health Checks

All cache implementations support health checks:

```kotlin
val status = cache.healthCheck()

when (status.level) {
    HealthStatus.Level.OK -> println("Cache is healthy")
    HealthStatus.Level.ERROR -> println("Cache error: ${status.additionalMessage}")
    else -> println("Cache warning: ${status.additionalMessage}")
}
```

The default health check:
1. Writes a test value with current timestamp
2. Reads it back
3. Verifies it was written recently (within 10 seconds)
4. Returns ERROR if write/read fails or data is stale

## Performance Considerations

### TTL Strategy

**Short TTLs (seconds to minutes):**
- Session data
- Real-time information
- High-change-rate data

**Medium TTLs (hours):**
- User profiles
- Configuration
- Computed aggregations

**Long TTLs (days):**
- Static content
- Rarely-changing reference data

**No TTL:**
- Avoid in production - leads to unbounded memory growth
- Use only for truly static data with manual invalidation

### Key Design

Use structured key naming:
```kotlin
// Good
"user:${userId}:profile"
"product:${productId}:inventory"
"session:${sessionId}"

// Avoid
userId  // Ambiguous
"cache_${userId}"  // Unclear what it stores
```

Benefits:
- Easy to understand what's cached
- Pattern-based operations (where supported)
- Simpler debugging

### Serialization Overhead

- Small objects: JSON serialization overhead is minimal
- Large objects: Consider compression or storing in file storage
- Frequently accessed: In-memory cache to avoid deserialization

## MapCache Implementation Details

`MapCache` is the in-memory implementation:

```kotlin
class MapCache(
    override val name: String,
    val entries: MutableMap<String, Entry>,
    override val context: SettingContext
) : Cache
```

**Entry Structure:**
```kotlin
data class Entry(
    val value: Any?,      // Stored untyped
    val expires: Instant? // Null = no expiration
)
```

**Important Characteristics:**

1. **No Serialization**: Values are stored as-is (not serialized). This is faster but means:
   - Objects are not copied (mutations affect cached value)
   - Memory references are preserved
   - Not suitable for distributed scenarios

2. **Expiration Handling**:
   - Expired entries checked on access only
   - No background cleanup - memory grows until keys are accessed
   - Consider periodic `clear()` for long-running applications

3. **Thread Safety**: Depends on the backing map
   - Use `ConcurrentHashMap` for multi-threaded access
   - Use plain `HashMap` for single-threaded (faster)

4. **Known Issues**:
   - `setIfNotExists()` doesn't check if entry is expired
   - Treats expired entry as existing, preventing new writes

## Platform Support

- **JVM**: All implementations (Redis, Memcached, DynamoDB require JVM)
- **JavaScript**: MapCache only
- **Native**: MapCache only (with thread-safety caveat)
- **Android**: All implementations

## Best Practices

1. **Always set TTLs** in production to prevent unbounded memory growth
2. **Use appropriate implementations** for your scale (ram for single instance, Redis for multi-instance)
3. **Handle cache misses gracefully** - cache failures shouldn't break core functionality
4. **Monitor cache hit rates** to validate effectiveness
5. **Use structured key names** for clarity and debugging
6. **Consider cache warming** for frequently accessed data at startup
7. **Invalidate on updates** to prevent serving stale data
8. **Use CacheHandle** for cleaner code when accessing same keys repeatedly

## Common Pitfalls

1. **No TTL on production caches** - leads to memory leaks
2. **Not handling null returns** - cache misses require fallback logic
3. **Caching too much** - not everything needs caching, adds complexity
4. **Caching too long** - stale data problems
5. **Over-reliance on cache** - cache failures should degrade gracefully, not break application
6. **MapCache in production** - use Redis/Memcached for distributed scenarios

## See Also

- [Basis Module](basis-module.md) - Core abstractions
- [Database Module](database-module.md) - Database abstraction
- [Redis Implementation](cache-redis.md) - Redis-specific details (if available)
