package com.lightningkite.services.cache.memcached

import com.lightningkite.services.SettingContext
import com.lightningkite.services.cache.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.rubyeye.xmemcached.MemcachedClient
import net.rubyeye.xmemcached.XMemcachedClient
import net.rubyeye.xmemcached.aws.AWSElasticCacheClient
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
 * @property client XMemcached client instance (supports both standard and ElastiCache)
 * @property context Service context with serializers
 */
public class MemcachedCache(
    override val name: String,
    public val client: MemcachedClient,
    override val context: SettingContext
) : Cache {

    // TODO: Support OpenTelemetry
    
    public val json: Json = Json { this.serializersModule = context.internalSerializersModule }
    
    public companion object {
        public fun Cache.Settings.Companion.memcached(vararg hosts: InetSocketAddress): Cache.Settings = Cache.Settings("memcached://${hosts.joinToString(","){ it.hostString + ":" + it.port }}")
        public fun Cache.Settings.Companion.memcachedTest(): Cache.Settings = Cache.Settings("memcached-test")
        public fun Cache.Settings.Companion.memcachedAws(host: String, port: Int): Cache.Settings = Cache.Settings("memcached-aws://$host:$port")
        init {
            Cache.Settings.register("memcached-test") { name, url, context ->
                val process = EmbeddedMemcached.start()
                Runtime.getRuntime().addShutdownHook(Thread {
                    process.destroy()
                })
                MemcachedCache(name, XMemcachedClient("127.0.0.1", 11211), context)
            }
            
            Cache.Settings.register("memcached") { name, url, context ->
                val hosts = url.substringAfter("://").split(' ', ',').filter { it.isNotBlank() }
                    .map {
                        InetSocketAddress(
                            it.substringBefore(':'),
                            it.substringAfter(':', "").toIntOrNull() ?: 11211
                        )
                    }
                MemcachedCache(name, XMemcachedClient(hosts), context)
            }
            
            Cache.Settings.register("memcached-aws") { name, url, context ->
                val configFullHost = url.substringAfter("://")
                val configPort = configFullHost.substringAfter(':', "").toIntOrNull() ?: 11211
                val configHost = configFullHost.substringBefore(':')
                val client = AWSElasticCacheClient(InetSocketAddress(configHost, configPort))
                MemcachedCache(name, client, context)
            }
        }
    }

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        try {
            client.get<String>(key)?.let { json.decodeFromString(serializer, it) }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?): Unit =
        withContext(Dispatchers.IO) {
            if(!client.set(
                key,
                timeToLive?.inWholeSeconds?.toInt() ?: 0,
                json.encodeToString(serializer, value)
            )) throw IllegalStateException("Failed to set value in Memcached")
            Unit
        }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ): Boolean = withContext(Dispatchers.IO) {
        client.add(
            key,
            timeToLive?.inWholeSeconds?.toInt() ?: 0,
            json.encodeToString(serializer, value)
        )
    }

    override suspend fun add(key: String, value: Int, timeToLive: Duration?): Unit = withContext(Dispatchers.IO) {
        client.incr(key, value.toLong(), value.toLong())
        timeToLive?.let {
            client.touch(key, it.inWholeSeconds.toInt())
        }
        Unit
    }

    override suspend fun remove(key: String): Unit = withContext(Dispatchers.IO) {
        client.delete(key)
        Unit
    }

    override suspend fun <T> compareAndSet(
        key: String,
        serializer: KSerializer<T>,
        expected: T?,
        new: T?,
        timeToLive: Duration?
    ): Boolean = withContext(Dispatchers.IO) {
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
            new == null && expected == null -> {
                // Both null, nothing to do
                true
            }
            new == null -> {
                // Delete the key (expected is not null, so key exists)
                client.delete(key)
                true
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

    override suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int,
        timeToLive: Duration?,
        modification: (T?) -> T?
    ): Boolean {
        repeat(maxTries) {
            val current = get(key, serializer)
            val new = modification(current)
            if (compareAndSet(key, serializer, current, new, timeToLive)) return true
        }
        return false
    }
}