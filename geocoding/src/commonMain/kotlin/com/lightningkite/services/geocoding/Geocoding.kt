package com.lightningkite.services.geocoding

import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.data.HealthStatus
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Service abstraction for turning addresses into coordinates and back.
 *
 * ## Available Implementations
 *
 * - **TestGeocoding** (`test`) — an empty, programmable table for unit tests
 * - **LocalGeocoding** (`local`) — offline US lookups from bundled Census data,
 *   no API key and no network (requires the `geocoding-local` module)
 * - **StadiaGeocoding** (`stadia://`) — Stadia Maps, a hosted Pelias (`geocoding-stadia`)
 * - **GoogleGeocoding** (`google://`) — Google Maps Platform (`geocoding-google`)
 * - **NominatimGeocoding** (`nominatim://`) — OpenStreetMap, free or self-hosted
 *   (`geocoding-nominatim`)
 *
 * ## Configuration
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val geocoding: Geocoding.Settings = Geocoding.Settings("local"),
 * )
 *
 * val geocoding: Geocoding = settings.geocoding("geocoding", context)
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * // Forward: address to coordinate
 * val match = geocoding.geocodeOne("350 5th Ave, New York NY")
 * if (match != null && match.precision >= GeocodePrecision.STREET) {
 *     drawPin(match.coordinate)
 * }
 *
 * // Reverse: coordinate to address
 * val here = geocoding.reverseGeocodeOne(GeoCoordinate(40.7484, -73.9857))
 *
 * // Type-ahead
 * val suggestions = geocoding.autocomplete(AutocompleteQuery("350 5th a"))
 * ```
 *
 * ## Important Gotchas
 *
 * - **Empty results are normal.** An unrecognized address returns an empty list, not an
 *   exception. [GeocodingException] means the *provider* failed, not the address.
 * - **Results are guesses ranked by the provider.** Check [GeocodeResult.precision]
 *   before trusting a coordinate; a geocoder handed a bad street address will silently
 *   return the city centroid rather than fail.
 * - **[GeocodeResult.confidence] is not comparable across providers.** Each vendor
 *   scores on its own scale. Use it to rank within one result list, nothing more.
 * - **Requests cost money.** Every hosted provider bills per request. Wrap with
 *   [cached] — addresses essentially never move, so a long TTL is safe and cuts
 *   most repeat traffic.
 * - **Rate limits are real and vary wildly.** Nominatim's public endpoint allows one
 *   request per second and will ban you for exceeding it. See [rateLimited].
 * - **Terms of use vary.** Several providers forbid storing coordinates long-term or
 *   displaying them on a competitor's map. Check before caching to a database.
 * - **Autocomplete may not include coordinates.** See [AddressSuggestion.coordinate].
 * - **Health checks cost a request.** See [healthCheckFrequency].
 */
public interface Geocoding : Service {

    /**
     * Finds locations matching an address.
     *
     * @return Candidate matches, best first. Empty when nothing matched — this is a
     * normal outcome for an address the provider does not recognize.
     * @throws GeocodingException if the provider could not be reached or rejected the request
     */
    public suspend fun geocode(query: GeocodeQuery): List<GeocodeResult>

    /**
     * Finds the address at or near a coordinate.
     *
     * @return Matches, nearest first. Empty when the point has nothing near it, which
     * is common in oceans and wilderness.
     * @throws GeocodingException if the provider could not be reached or rejected the request
     */
    public suspend fun reverseGeocode(query: ReverseGeocodeQuery): List<GeocodeResult>

    /**
     * Suggests completions for a partially typed address.
     *
     * Intended for interactive type-ahead, so implementations favor speed over
     * completeness. Do not use it to geocode a complete address — use [geocode].
     *
     * @throws GeocodingException if the provider could not be reached or rejected the request
     */
    public suspend fun autocomplete(query: AutocompleteQuery): List<AddressSuggestion>

    /**
     * The address [healthCheck] looks up to prove the provider is answering.
     *
     * Override if the default is not resolvable by a particular provider — for example
     * one restricted to a single country.
     */
    public val healthCheckQuery: GeocodeQuery
        get() = GeocodeQuery.Text("Washington, DC, USA", limit = 1)

    /**
     * Defaults to one hour rather than the usual minute because a health check against a
     * hosted geocoder is a billed request. Hourly is ~24 requests a day, which is
     * negligible on every provider's pricing; per-minute would not be.
     */
    override val healthCheckFrequency: Duration
        get() = 1.hours

    /**
     * Verifies the provider answers by looking up [healthCheckQuery].
     *
     * A successful call that matches nothing is reported as a warning rather than an
     * error: the service is clearly reachable, but a provider that cannot find
     * Washington, DC is misconfigured — wrong country restriction, or an empty dataset.
     */
    override suspend fun healthCheck(): HealthStatus = try {
        if (geocode(healthCheckQuery).isEmpty()) {
            HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "Provider returned no results for its health check query")
        } else {
            HealthStatus(HealthStatus.Level.OK)
        }
    } catch (e: Exception) {
        HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
    }

    /**
     * Configuration for instantiating a geocoding service.
     *
     * The URL scheme selects the provider:
     * - `test` — empty programmable table, for unit tests
     * - `local` — offline US lookups from bundled data (`geocoding-local`)
     * - `stadia://apiKey` (`geocoding-stadia`)
     * - `google://apiKey` (`geocoding-google`)
     * - `nominatim://contactEmail` or `nominatim://contactEmail@your-host` (`geocoding-nominatim`)
     *
     * @property url Connection string selecting the provider and its credentials
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "test",
    ) : Setting<Geocoding> {
        override fun invoke(name: String, context: SettingContext): Geocoding = parse(name, url, context)

        public companion object : UrlSettingParser<Geocoding>() {
            init {
                register("test") { name, _, context -> TestGeocoding(name, context) }
            }
        }
    }
}

/**
 * Thrown when a geocoding provider fails.
 *
 * This means the provider could not answer — network failure, bad credentials, quota
 * exhausted, malformed response. An address the provider simply does not recognize is
 * *not* an error; that returns an empty result list.
 */
public class GeocodingException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Looks up a freeform address. Shorthand for [Geocoding.geocode] with [GeocodeQuery.Text]. */
public suspend fun Geocoding.geocode(
    text: String,
    focus: GeoCoordinate? = null,
    limit: Int = 5,
): List<GeocodeResult> = geocode(GeocodeQuery.Text(text, focus, limit))

/** Looks up structured address components. Shorthand for [Geocoding.geocode] with [GeocodeQuery.Structured]. */
public suspend fun Geocoding.geocode(
    address: Address,
    focus: GeoCoordinate? = null,
    limit: Int = 5,
): List<GeocodeResult> = geocode(GeocodeQuery.Structured(address, focus, limit))

/**
 * Returns the single best match, or null if nothing matched.
 *
 * Remember to check [GeocodeResult.precision] — "best" only means the provider ranked
 * it first, not that it is precise.
 */
public suspend fun Geocoding.geocodeOne(text: String, focus: GeoCoordinate? = null): GeocodeResult? =
    geocode(GeocodeQuery.Text(text, focus, limit = 1)).firstOrNull()

/** Returns the single best match for structured components, or null if nothing matched. */
public suspend fun Geocoding.geocodeOne(address: Address, focus: GeoCoordinate? = null): GeocodeResult? =
    geocode(GeocodeQuery.Structured(address, focus, limit = 1)).firstOrNull()

/** Returns the nearest address to a coordinate, or null if there is nothing near it. */
public suspend fun Geocoding.reverseGeocodeOne(coordinate: GeoCoordinate): GeocodeResult? =
    reverseGeocode(ReverseGeocodeQuery(coordinate, limit = 1)).firstOrNull()
