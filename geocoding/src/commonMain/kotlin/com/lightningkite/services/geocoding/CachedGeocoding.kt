package com.lightningkite.services.geocoding

import com.lightningkite.services.cache.Cache
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.kotlincrypto.hash.sha2.SHA256
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Wraps a geocoder so identical queries are answered from a [Cache] instead of the provider.
 *
 * Worth doing on any hosted provider. Geocoding is billed per request, and applications
 * geocode the same handful of addresses over and over — a warehouse, a depot, a customer
 * whose record gets opened daily. Buildings also do not move, so a long TTL is safe;
 * the default is 30 days.
 *
 * ```kotlin
 * val geocoding = Geocoding.Settings("stadia://$apiKey")("geocoding", context)
 *     .cached(cache)
 * ```
 *
 * ## Notes
 *
 * - **Misses are cached too.** An address the provider does not recognize will not
 *   recognize it next week either, and bad input tends to arrive repeatedly.
 * - **Autocomplete is not cached.** Every keystroke produces a different query, so the
 *   hit rate is near zero while the write traffic is not. Those calls pass straight through.
 * - **Check your provider's terms.** Some licenses restrict how long results may be
 *   stored. Lower [timeToLive] if yours does.
 * - **Cache failures are not swallowed.** If the cache itself errors, the call fails
 *   rather than silently degrading into unbounded provider billing.
 */
public class CachedGeocoding(
    private val wrapped: Geocoding,
    private val cache: Cache,
    private val timeToLive: Duration = 30.days,
) : Geocoding by wrapped {

    private val json = Json { encodeDefaults = true }
    private val resultsSerializer = ListSerializer(GeocodeResult.serializer())

    /**
     * Keys are hashed rather than used raw because a freeform address can be arbitrarily
     * long and some cache backends cap key length (memcached at 250 bytes).
     */
    private fun <T> keyFor(kind: String, serializer: KSerializer<T>, query: T): String {
        val digest = SHA256().digest(json.encodeToString(serializer, query).encodeToByteArray())
        return "geocoding:${wrapped.name}:$kind:" + digest.joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private suspend fun <T> cached(
        kind: String,
        serializer: KSerializer<T>,
        query: T,
        fetch: suspend (T) -> List<GeocodeResult>,
    ): List<GeocodeResult> {
        val key = keyFor(kind, serializer, query)
        cache.get(key, resultsSerializer)?.let { return it }
        val fresh = fetch(query)
        cache.set(key, fresh, resultsSerializer, timeToLive)
        return fresh
    }

    override suspend fun geocode(query: GeocodeQuery): List<GeocodeResult> =
        cached("forward", GeocodeQuery.serializer(), query) { wrapped.geocode(it) }

    override suspend fun reverseGeocode(query: ReverseGeocodeQuery): List<GeocodeResult> =
        cached("reverse", ReverseGeocodeQuery.serializer(), query) { wrapped.reverseGeocode(it) }
}

/**
 * Answers repeated [geocode] and [reverseGeocode] calls from [cache].
 *
 * @param timeToLive How long to keep results. The 30-day default suits address lookups;
 * shorten it if your provider's terms restrict storage.
 * @see CachedGeocoding
 */
public fun Geocoding.cached(cache: Cache, timeToLive: Duration = 30.days): Geocoding =
    CachedGeocoding(this, cache, timeToLive)
