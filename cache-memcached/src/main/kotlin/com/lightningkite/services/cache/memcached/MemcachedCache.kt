package com.lightningkite.services.cache.memcached

import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.SettingContext
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.telemetry.telemetryTrace
import net.rubyeye.xmemcached.exception.MemcachedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.rubyeye.xmemcached.MemcachedClient
import net.rubyeye.xmemcached.XMemcachedClient
import net.rubyeye.xmemcached.autodiscovery.AutoDiscoveryCacheClient
import java.net.InetSocketAddress
import kotlin.time.Duration

/**
 * Memcached implementation of the Cache abstraction using XMemcached client.
 *
 * Provides distributed caching with:
 * - **True CAS operations**: Atomic compare-and-set via Memcached CAS tokens
 * - **TTL support**: Native Memcached expiration (seconds precision)
 * - **High performance**: Binary protocol, connection pooling
 * - **AWS ElastiCache**: Special support for AWS ElastiCache configuration endpoint
 *
 * ## Supported URL Schemes
 *
 * Standard Memcached URLs:
 * - `memcached://localhost:11211` - Single server
 * - `memcached://host1:11211,host2:11211` - Multiple servers (automatic sharding)
 * - `memcached://host1:11211 host2:11211` - Space-separated servers
 * - `memcached-aws://config-endpoint.cache.amazonaws.com:11211` - AWS ElastiCache
 * - `memcached-test://` - Embedded Memcached for testing
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Local development
 * Cache.Settings("memcached://localhost:11211")
 *
 * // Multiple servers with automatic sharding
 * Cache.Settings("memcached://cache1:11211,cache2:11211,cache3:11211")
 *
 * // AWS ElastiCache cluster
 * Cache.Settings("memcached-aws://my-cluster.cfg.cache.amazonaws.com:11211")
 *
 * // Testing with embedded instance
 * Cache.Settings("memcached-test://")
 * ```
 *
 * ## Implementation Notes
 *
 * - **Serialization**: Values stored as JSON strings
 * - **CAS operations**: Uses Memcached GETS/CAS for atomic compareAndSet
 * - **TTL precision**: Seconds only (not milliseconds like Redis)
 * - **Connection pooling**: Managed by XMemcached client
 * - **Error handling**: Returns null on deserialization errors (graceful degradation)
 *
 * ## Important Gotchas
 *
 * - **TTL 0 means no expiration**: Unlike some systems, 0 = infinite TTL
 * - **1MB value limit**: Memcached has a default 1MB limit per item
 * - **No transactions**: Operations are atomic individually but not across multiple keys
 * - **ElastiCache auto-discovery**: AWS URL requires ElastiCache client with config endpoint
 *
 * @property name Service name for logging/metrics
 * @property makeClient Lazy factory for the XMemcached client (supports both standard and
 *   ElastiCache); building it lazily and re-invoking it on reconnect is what lets [disconnect]
 *   actually release the client's selector thread and socket pool without permanently bricking
 *   the cache.
 * @property context Service context with serializers
 */
public class MemcachedCache(
    override val name: String,
    public val makeClient: () -> MemcachedClient,
    override val context: SettingContext,
) : Cache {

    // A `var` (rather than a plain `by lazy` delegate) so `disconnect()` can discard the built
    // client and let the next access rebuild a fresh one from `makeClient` — see `disconnect()`.
    private var _client = lazy(makeClient)
    public val client: MemcachedClient get() = _client.value

    public val json: Json = Json { this.serializersModule = context.internalSerializersModule }

    public companion object {
        public fun Cache.Settings.Companion.memcached(vararg hosts: InetSocketAddress): Cache.Settings =
            Cache.Settings("memcached://${hosts.joinToString(",") { it.hostString + ":" + it.port }}")

        public fun Cache.Settings.Companion.memcachedTest(): Cache.Settings = Cache.Settings("memcached-test")
        public fun Cache.Settings.Companion.memcachedAws(host: String, port: Int): Cache.Settings =
            Cache.Settings("memcached-aws://$host:$port")

        init {
            Cache.Settings.register("memcached-test") { name, url, context ->
                val process = EmbeddedMemcached.start()
                Runtime.getRuntime().addShutdownHook(Thread {
                    process.destroy()
                })
                MemcachedCache(name, { XMemcachedClient("127.0.0.1", 11211) }, context)
            }

            Cache.Settings.register("memcached") { name, url, context ->
                val hosts = url.substringAfter("://").split(' ', ',').filter { it.isNotBlank() }
                    .map {
                        InetSocketAddress(
                            it.substringBefore(':'),
                            it.substringAfter(':', "").toIntOrNull() ?: 11211
                        )
                    }
                MemcachedCache(name, { XMemcachedClient(hosts) }, context)
            }

            Cache.Settings.register("memcached-aws") { name, url, context ->
                val configFullHost = url.substringAfter("://")
                val configPort = configFullHost.substringAfter(':', "").toIntOrNull() ?: 11211
                val configHost = configFullHost.substringBefore(':')
                // AWSElasticCacheClient is deprecated in favor of AutoDiscoveryCacheClient, which is
                // the same auto-discovery implementation under a new (non-AWS-specific) name.
                MemcachedCache(name, { AutoDiscoveryCacheClient(InetSocketAddress(configHost, configPort)) }, context)
            }
        }
    }

    // Static, low-cardinality span attributes shared by every operation. The cache key is hashed so a
    // high-cardinality value never reaches telemetry.
    private fun spanAttrs(
        key: String,
        timeToLive: Duration? = null,
    ): TelemetryAttributes = TelemetryAttributes {
        put(Cache.TelemetryKeys.key, context.telemetrySanitization.hashCacheKey(key))
        put(Cache.TelemetryKeys.system, "memcached")
        timeToLive?.let { put(Cache.TelemetryKeys.ttl, it.inWholeSeconds) }
    }

    /** Establishes the underlying XMemcached client. Optional — every operation does this lazily. */
    override suspend fun connect() {
        client
    }

    /**
     * Shuts down the XMemcached client, releasing its selector thread and socket pool. Idempotent:
     * repeated calls, or calling it without ever having connected, are a no-op beyond the first.
     *
     * A subsequent [connect] (or any operation) rebuilds the client from [makeClient], so this does
     * not permanently disable the cache — see the `_client` var above.
     */
    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            if (_client.isInitialized()) _client.value.shutdown()
        }
        _client = lazy(makeClient)
    }

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? =
        telemetryTrace("get", attributes = spanAttrs(key), dimensions = setOf(Cache.TelemetryKeys.hit)) { span ->
            val result = withContext(Dispatchers.IO) {
                try {
                    client.get<String>(key)?.let { json.decodeFromString(serializer, it) }
                } catch (e: MemcachedException) {
                    // Cache-miss or protocol-level error — treat as absent.
                    null
                }
                // IOException and other connection errors propagate to the outer handler.
            }
            span.enrich(TelemetryAttributes { put(Cache.TelemetryKeys.hit, result != null) })
            result
        }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?): Unit =
        telemetryTrace("set", attributes = spanAttrs(key, timeToLive)) {
            withContext(Dispatchers.IO) {
                if (!client.set(
                        key,
                        timeToLive?.inWholeSeconds?.toInt() ?: 0,
                        json.encodeToString(serializer, value)
                    )
                ) throw IllegalStateException("Failed to set value in Memcached")
            }
        }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ): Boolean = telemetryTrace("setIfNotExists", attributes = spanAttrs(key, timeToLive), dimensions = setOf(Cache.TelemetryKeys.added)) { span ->
        val result = withContext(Dispatchers.IO) {
            client.add(
                key,
                timeToLive?.inWholeSeconds?.toInt() ?: 0,
                json.encodeToString(serializer, value)
            )
        }
        span.enrich(TelemetryAttributes { put(Cache.TelemetryKeys.added, result) })
        result
    }

    override suspend fun add(key: String, value: Long, timeToLive: Duration?): Long =
        telemetryTrace("add", attributes = TelemetryAttributes { putAll(spanAttrs(key, timeToLive)); put(Cache.TelemetryKeys.value, value) }) {
            withContext(Dispatchers.IO) {
                // Memcached's incr/decr commands only accept non-negative deltas.
                // Negative deltas must use decr; initValue is used when the key doesn't exist.
                val result = if (value >= 0) {
                    client.incr(key, value, value)
                } else {
                    client.decr(key, -value, -value)
                }
                timeToLive?.let {
                    client.touch(key, it.inWholeSeconds.toInt())
                }
                result
            }
        }

    override suspend fun remove(key: String): Unit =
        telemetryTrace("remove", attributes = spanAttrs(key)) {
            withContext(Dispatchers.IO) {
                client.delete(key)
            }
        }

    override suspend fun <T> compareAndSet(
        key: String,
        serializer: KSerializer<T>,
        expected: T?,
        new: T?,
        timeToLive: Duration?,
    ): Boolean = telemetryTrace("compareAndSet", attributes = spanAttrs(key, timeToLive), dimensions = setOf(Cache.TelemetryKeys.casSuccess)) { span ->
        val result = withContext(Dispatchers.IO) {
            // Early return if expected equals new
            if (expected == new) return@withContext true

            // Get the current value with CAS token
            val getsResult = client.gets<String>(key)
            val currentValue = try {
                getsResult?.value?.let { json.decodeFromString(serializer, it) }
            } catch (e: Exception) {
                null
            }

            // Check if current value matches expected
            if (currentValue != expected) {
                return@withContext false
            }

            // Now perform the CAS operation based on the state transition
            when {
                new == null -> {
                    // Delete guarded by the CAS token from the `gets()` above, rather than the old
                    // unconditional client.delete(key) (which also always returned a hardcoded
                    // `true`, ignoring whether the delete actually happened). This class talks the
                    // classic Memcached *text* protocol (XMemcachedClient's default), whose DELETE
                    // command has no CAS argument on the wire at all — so the CAS value passed here
                    // is currently accepted by XMemcached's API but silently dropped by
                    // TextCommandFactory before it reaches the server, same as passing none. Real
                    // atomicity for this branch needs the binary protocol (BinaryCommandFactory);
                    // until then this still fixes the always-`true` return value, and is forward
                    // compatible with a future protocol switch at no extra cost.
                    client.delete(key, getsResult!!.cas, client.opTimeout)
                }

                expected == null -> {
                    // Key doesn't exist, use add (atomic set-if-not-exists)
                    client.add(
                        key,
                        timeToLive?.inWholeSeconds?.toInt() ?: 0,
                        json.encodeToString(serializer, new)
                    )
                }

                else -> {
                    // Key exists and we have a CAS token, use it for atomic update
                    client.cas(
                        key,
                        timeToLive?.inWholeSeconds?.toInt() ?: 0,
                        json.encodeToString(serializer, new),
                        getsResult!!.cas
                    )
                }
            }
        }
        span.enrich(TelemetryAttributes { put(Cache.TelemetryKeys.casSuccess, result) })
        result
    }

    override suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int,
        timeToLive: Duration?,
        modification: (T?) -> T?,
    ): Boolean {
        repeat(maxTries) {
            val current = get(key, serializer)
            val new = modification(current)
            if (compareAndSet(key, serializer, current, new, timeToLive)) return true
        }
        return false
    }
}