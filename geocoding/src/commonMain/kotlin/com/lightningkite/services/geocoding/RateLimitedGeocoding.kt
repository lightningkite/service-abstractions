package com.lightningkite.services.geocoding

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Wraps a geocoder so it never starts requests closer together than [minimumInterval].
 *
 * Several providers publish hard limits and enforce them by blocking you rather than
 * by returning an error you can retry — OpenStreetMap's public Nominatim allows one
 * request per second and bans clients that exceed it. Throttling on the way out is
 * cheaper than discovering that in production.
 *
 * ```kotlin
 * val geocoding = someGeocoder.rateLimited(1.seconds)
 * ```
 *
 * Callers queue rather than fail: a call that arrives too early suspends until its turn.
 * That means this bounds throughput, not latency — under sustained load, waits grow
 * without limit. If you need to shed load instead of queueing it, do that above this
 * wrapper.
 *
 * Only request *starts* are spaced. Requests themselves may overlap, which is what the
 * published limits actually govern.
 */
public class RateLimitedGeocoding(
    private val wrapped: Geocoding,
    private val minimumInterval: Duration,
) : Geocoding by wrapped {

    private val lock = Mutex()
    private var nextAllowed: Instant? = null

    /**
     * Holding the lock across the delay is deliberate: it makes waiters queue in arrival
     * order and each claim a distinct slot, rather than all waking together and firing
     * at once.
     */
    private suspend fun <T> throttled(block: suspend () -> T): T {
        lock.withLock {
            val clock = wrapped.context.clock
            nextAllowed?.let { next ->
                val wait = next - clock.now()
                if (wait.isPositive()) delay(wait)
            }
            nextAllowed = clock.now() + minimumInterval
        }
        return block()
    }

    override suspend fun geocode(query: GeocodeQuery): List<GeocodeResult> =
        throttled { wrapped.geocode(query) }

    override suspend fun reverseGeocode(query: ReverseGeocodeQuery): List<GeocodeResult> =
        throttled { wrapped.reverseGeocode(query) }

    override suspend fun autocomplete(query: AutocompleteQuery): List<AddressSuggestion> =
        throttled { wrapped.autocomplete(query) }
}

/**
 * Spaces outgoing requests by at least [minimumInterval].
 *
 * @see RateLimitedGeocoding
 */
public fun Geocoding.rateLimited(minimumInterval: Duration): Geocoding =
    RateLimitedGeocoding(this, minimumInterval)
