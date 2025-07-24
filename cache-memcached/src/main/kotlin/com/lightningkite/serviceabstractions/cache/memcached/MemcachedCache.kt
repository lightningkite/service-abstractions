package com.lightningkite.serviceabstractions.cache.memcached

import com.lightningkite.serviceabstractions.SettingContext
import com.lightningkite.serviceabstractions.cache.Cache
import com.lightningkite.serviceabstractions.cache.MetricTrackingCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.rubyeye.xmemcached.MemcachedClient
import net.rubyeye.xmemcached.XMemcachedClient
import net.rubyeye.xmemcached.aws.AWSElasticCacheClient
import net.rubyeye.xmemcached.exception.MemcachedException
import net.rubyeye.xmemcached.exception.NoValueException
import net.rubyeye.xmemcached.utils.AddrUtil
import java.net.InetSocketAddress
import kotlin.time.Duration

/**
 * A cache implementation that uses Memcached as the backend.
 */
public class MemcachedCache(
    public val client: MemcachedClient,
    override val context: SettingContext
) : MetricTrackingCache() {
    
    public val json: Json = Json { this.serializersModule = context.serializersModule }
    
    public companion object {
        init {
            Cache.Settings.register("memcached-test") { url, context ->
                val process = EmbeddedMemcached.start()
                Runtime.getRuntime().addShutdownHook(Thread {
                    process.destroy()
                })
                MemcachedCache(XMemcachedClient("127.0.0.1", 11211), context)
            }
            
            Cache.Settings.register("memcached") { url, context ->
                val hosts = url.substringAfter("://").split(' ', ',').filter { it.isNotBlank() }
                    .map {
                        InetSocketAddress(
                            it.substringBefore(':'),
                            it.substringAfter(':', "").toIntOrNull() ?: 11211
                        )
                    }
                MemcachedCache(XMemcachedClient(hosts), context)
            }
            
            Cache.Settings.register("memcached-aws") { url, context ->
                val configFullHost = url.substringAfter("://")
                val configPort = configFullHost.substringAfter(':', "").toIntOrNull() ?: 11211
                val configHost = configFullHost.substringBefore(':')
                val client = AWSElasticCacheClient(InetSocketAddress(configHost, configPort))
                MemcachedCache(client, context)
            }
        }
    }

    override suspend fun <T> getInternal(key: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        try {
            client.get<String>(key)?.let { json.decodeFromString(serializer, it) }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun <T> setInternal(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?): Unit =
        withContext(Dispatchers.IO) {
            if(!client.set(
                key,
                timeToLive?.inWholeSeconds?.toInt() ?: 0,
                json.encodeToString(serializer, value)
            )) throw IllegalStateException("Failed to set value in Memcached")
            Unit
        }

    override suspend fun <T> setIfNotExistsInternal(
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

    override suspend fun addInternal(key: String, value: Int, timeToLive: Duration?): Unit = withContext(Dispatchers.IO) {
        client.incr(key, value.toLong(), value.toLong())
        timeToLive?.let {
            client.touch(key, it.inWholeSeconds.toInt())
        }
        Unit
    }

    override suspend fun removeInternal(key: String): Unit = withContext(Dispatchers.IO) {
        client.delete(key)
        Unit
    }
}