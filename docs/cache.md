# Cache

`Cache` is a key-value store with expiry and atomic operations: Redis, Memcached, DynamoDB, or an in-memory map.  Values are serialized with kotlinx.serialization, so any `@Serializable` type can be stored.

## Modules

| Module | Add it for |
|---|---|
| `cache` | `Cache`, `CacheKey`, `PrefixCache`, in-memory `ram`.  Multiplatform. |
| `cache-redis` | `redis://` (Lettuce).  JVM. |
| `cache-memcached` | `memcached://`, ElastiCache auto-discovery, a local test server.  JVM. |
| `cache-dynamodb` | `dynamodb://`, DynamoDB Local for tests.  JVM. |
| `cache-test` | `CacheTest`, the shared contract suite. |

## Quick start

```kotlin
@Serializable
data class AppSettings(val cache: Cache.Settings = Cache.Settings("ram"))

suspend fun main() {
    RedisCache  // registers redis://
    val settings = Json.decodeFromString<AppSettings>("""{"cache": "redis://localhost:6379"}""")
    val cache = settings.cache("cache", TestSettingContext())

    cache.set("session:abc", Session("ada"), timeToLive = 30.minutes)
    val session: Session? = cache.get<Session>("session:abc")
}
```

## Common tasks

### Lock or deduplicate

```kotlin
if (cache.setIfNotExists("lock:job-42", "worker-1", timeToLive = 30.seconds)) {
    try { runJob() } finally { cache.remove("lock:job-42") }
}
```

### Count

```kotlin
val hits: Int = cache.add("hits:$minute", 1, timeToLive = 1.minutes)  // atomic; returns the new value
```

### Update in place

```kotlin
val ok: Boolean = cache.modify<Int>("balance", maxTries = 5) { (it ?: 0) + 10 }
```

`modify` reads, applies the function, and writes with `compareAndSet`, retrying on conflict up to `maxTries` (default 1).  It returns `false` if every try lost a race.  Returning `null` from the function removes the key.

### Take a value once

```kotlin
val token: String? = cache.getAndRemove<String>("one-time-token")
```

### Typed keys and namespaces

```kotlin
val sessionKey = CacheKey<Session>("session:abc")
cache.set(sessionKey, Session("ada"))
val s: Session? = cache.get(sessionKey)

val tenantCache: Cache = PrefixCache(cache, "tenant-7:")
```

## Settings URLs

| Scheme | Module | Example |
|---|---|---|
| `ram` | `cache` | `ram` (thread-safe) |
| `ram-unsafe` | `cache` | `ram-unsafe` (single-threaded) |
| `redis` | `cache-redis` | `redis://:password@cache.internal:6379/2` |
| `memcached` | `cache-memcached` | `memcached://10.0.0.5:11211,10.0.0.6:11211` |
| `memcached-aws` | `cache-memcached` | `memcached-aws://mycache.abc123.cfg.use1.cache.amazonaws.com:11211` |
| `memcached-test` | `cache-memcached` | `memcached-test` (starts `memcached` from `PATH`) |
| `dynamodb` | `cache-dynamodb` | `dynamodb://us-west-2/app-cache` |
| `dynamodb-local` | `cache-dynamodb` | `dynamodb-local` (downloads and runs DynamoDB Local) |

Parameters and quirks: [Settings URL reference: Cache](settings-url-reference.md#cache).  Reference `RedisCache`, `MemcachedCache` or `DynamoDbCache` before parsing settings so the scheme is registered.

## Testing

Use `ram`.  To check a custom implementation against the contract every backend meets, subclass `CacheTest`:

```kotlin
class MyCacheTests : CacheTest() {
    override val cache: Cache = MyCache("test", TestSettingContext())
}
```

## Gotchas

- **TLS Redis**: only `redis` is registered, so `rediss://` and sentinel URLs fail with "No handler".
- **Expiry in `ram`**: expired entries are dropped when read, not proactively, so a long-running process that never re-reads keys keeps growing.
- **`add` on non-numbers**: behavior is backend-specific; most throw.  Don't mix `add` and `set` of other types on one key.
- **Keys in telemetry** are hashed, not recorded in plain text.
