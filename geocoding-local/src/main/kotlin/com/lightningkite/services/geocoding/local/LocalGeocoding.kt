package com.lightningkite.services.geocoding.local

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.geocoding.*
import kotlin.math.PI
import kotlin.math.cos

/**
 * Geocoding for US addresses with no API key, no network, and no account.
 *
 * Answers from a ~400 KB US Census Gazetteer extract bundled in this module: the
 * centroid of every ZIP Code Tabulation Area (~33,800) and every incorporated place and
 * census-designated place (~32,400). The Gazetteer is public domain, so nothing here
 * carries an attribution obligation.
 *
 * ## What it is for
 *
 * Local development, tests, CI, and offline or air-gapped deployments — anywhere you
 * want real coordinates for real US addresses without configuring a provider or paying
 * per request. It is a genuine geocoder over a real dataset, not a stub: "Reno, NV",
 * "89501" and "350 5th Ave, New York NY 10001" all resolve to correct positions.
 *
 * ```kotlin
 * val geocoding = Geocoding.Settings("local")("geocoding", context)
 * geocoding.geocodeOne("Sacramento, CA")   // 38.568, -121.468
 * ```
 *
 * ## Limits you must design around
 *
 * - **No street-level data.** There is no house-number database here — that is gigabytes,
 *   not kilobytes. A full street address resolves to the centroid of its ZIP code or
 *   city, and reports [GeocodePrecision.POSTAL_CODE] or [GeocodePrecision.LOCALITY]
 *   accordingly. It never claims [GeocodePrecision.ROOFTOP]. Code that checks precision
 *   before trusting a coordinate — which it should anyway — behaves correctly against
 *   this provider without changes.
 * - **United States only.** Queries naming another country return no results. Puerto
 *   Rico and DC are included.
 * - **Centroids, not addresses.** A ZIP centroid can sit a few miles from any given
 *   address in that ZIP, and for large rural ZIPs, further.
 * - **Same-named cities are ranked crudely.** With no population data in the public
 *   domain source, "Springfield" with no state returns matches ordered incorporated-first
 *   and then alphabetically by state. Pass a region, or a [GeocodeQuery.focus] point, to
 *   disambiguate properly.
 *
 * ## Cost
 *
 * The dataset decodes lazily on first use — roughly 100 ms and about 1 MB of heap, held
 * for the life of the service. Call [connect] during startup to pay that before the
 * first request rather than during it.
 */
public class LocalGeocoding(
    override val name: String,
    override val context: SettingContext,
) : Geocoding {

    private val data: Gazetteer by lazy { Gazetteer.load() }

    /** Decodes the bundled dataset now, so the first real query does not pay for it. */
    override suspend fun connect() {
        data.placeCount
    }

    override suspend fun geocode(query: GeocodeQuery): List<GeocodeResult> {
        val address = when (query) {
            is GeocodeQuery.Structured -> query.address
            is GeocodeQuery.Text -> parse(query.text)
        }
        if (!isUnitedStates(address.country)) return emptyList()

        val region = address.region?.let { stateCode(it) }

        // A ZIP pins the location far more tightly than a city name, so it wins when both
        // are present.
        address.postalCode?.let { postal ->
            val index = data.zipIndex(postal.take(5))
            if (index >= 0) {
                return listOf(zipResult(index, address, region))
            }
        }

        val focus = query.focus
        address.locality?.let { locality ->
            val matches = resolveLocality(locality, region)
            if (matches.isNotEmpty()) {
                return matches
                    .let { if (focus != null) it.sortedBy { i -> focus.distanceTo(i) } else it }
                    .take(query.limit)
                    .map { placeResult(it, address.postalCode) }
            }
        }

        // Falling back to the state centroid is honest so long as the precision says so:
        // it is the right answer to "where is Nevada" and clearly the wrong one to trust
        // for a delivery.
        if (region != null && address.street == null && address.locality == null) {
            regionResult(region)?.let { return listOf(it) }
        }
        return emptyList()
    }

    override suspend fun reverseGeocode(query: ReverseGeocodeQuery): List<GeocodeResult> {
        if (data.placeCount == 0) return emptyList()
        val nearestZip = nearestZip(query.coordinate)
        return nearestPlaces(query.coordinate, query.limit).map { placeResult(it, nearestZip) }
    }

    override suspend fun autocomplete(query: AutocompleteQuery): List<AddressSuggestion> {
        if (!isUnitedStates(query.country)) return emptyList()
        val normalized = query.text.normalizedForGeocoding()
        if (normalized.isEmpty()) return emptyList()

        // A run of digits is someone typing a ZIP, which name matching would never find.
        if (normalized.all { it.isDigit() }) {
            return zipsStartingWith(normalized, query.limit).map { index ->
                AddressSuggestion(
                    label = data.zipCode(index),
                    address = Address(postalCode = data.zipCode(index), country = "US"),
                    coordinate = coordinateOfZip(index),
                )
            }
        }

        val focus = query.focus
        var matches = data.placesStartingWith(normalized, query.limit * MATCH_OVERSHOOT)
        if (focus != null) matches = matches.sortedBy { focus.distanceTo(it) }
        return matches.take(query.limit).map { index ->
            AddressSuggestion(
                label = placeLabel(index),
                address = placeAddress(index, postalCode = null),
                coordinate = coordinateOfPlace(index),
            )
        }
    }

    /**
     * Verifies the bundled dataset decodes, rather than looking up an address.
     *
     * There is no remote service here to be unavailable; the only thing that can fail is
     * a missing or corrupt resource, and that fails identically every time.
     */
    override suspend fun healthCheck(): HealthStatus = try {
        HealthStatus(
            HealthStatus.Level.OK,
            additionalMessage = "${data.placeCount} places and ${data.zipCount} ZIP codes, ${data.vintage} vintage",
        )
    } catch (e: Exception) {
        HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
    }

    // ---- lookup helpers ----

    /** Place indices matching a locality name, optionally constrained to one state. */
    private fun resolveLocality(locality: String, region: String?): List<Int> {
        val matches = data.placesNamed(locality.normalizedForGeocoding())
        return if (region == null) matches else matches.filter { data.placeState(it) == region }
    }

    private fun nearestPlaces(target: GeoCoordinate, limit: Int): List<Int> {
        val lat = (target.latitude * SCALE).toInt()
        val lon = (target.longitude * SCALE).toInt()
        val scale = cos(target.latitude * PI / 180)
        // A linear sweep of ~32k points takes well under a millisecond, and costs no
        // memory beyond the arrays already loaded. A spatial index would be faster and
        // strictly more code to get wrong.
        return (0 until data.placeCount)
            .sortedBy { squaredDistance(data.latitude[it], data.longitude[it], lat, lon, scale) }
            .take(limit)
    }

    private fun nearestZip(target: GeoCoordinate): String? {
        if (data.zipCount == 0) return null
        val lat = (target.latitude * SCALE).toInt()
        val lon = (target.longitude * SCALE).toInt()
        val scale = cos(target.latitude * PI / 180)
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (i in 0 until data.zipCount) {
            val d = squaredDistance(data.zipLatitude[i], data.zipLongitude[i], lat, lon, scale)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return data.zipCode(best)
    }

    private fun zipsStartingWith(prefix: String, limit: Int): List<Int> =
        (0 until data.zipCount).asSequence()
            .filter { data.zipCode(it).startsWith(prefix) }
            .take(limit)
            .toList()

    // ---- result construction ----

    private fun coordinateOfPlace(index: Int) =
        GeoCoordinate(data.latitude[index] / SCALE, data.longitude[index] / SCALE)

    private fun coordinateOfZip(index: Int) =
        GeoCoordinate(data.zipLatitude[index] / SCALE, data.zipLongitude[index] / SCALE)

    private fun GeoCoordinate.distanceTo(placeIndex: Int): Double =
        this distanceToKilometers coordinateOfPlace(placeIndex)

    private fun placeLabel(index: Int): String = "${data.placeName(index)}, ${data.placeState(index)}"

    private fun placeAddress(index: Int, postalCode: String?) = Address(
        locality = data.placeName(index),
        region = data.placeState(index),
        postalCode = postalCode,
        country = "US",
    )

    private fun placeResult(index: Int, postalCode: String?) = GeocodeResult(
        coordinate = coordinateOfPlace(index),
        label = placeLabel(index) + (postalCode?.let { " $it" } ?: ""),
        address = placeAddress(index, postalCode),
        precision = GeocodePrecision.LOCALITY,
    )

    private fun zipResult(index: Int, requested: Address, region: String?): GeocodeResult {
        val code = data.zipCode(index)
        val address = Address(
            locality = requested.locality,
            region = region,
            postalCode = code,
            country = "US",
        )
        return GeocodeResult(
            coordinate = coordinateOfZip(index),
            label = listOfNotNull(requested.locality, region, code).joinToString(" ").ifEmpty { code },
            address = address,
            precision = GeocodePrecision.POSTAL_CODE,
        )
    }

    /** Averages a state's places to stand in for a state centroid, which the source lacks. */
    private fun regionResult(region: String): GeocodeResult? {
        var latSum = 0L
        var lonSum = 0L
        var count = 0
        for (i in 0 until data.placeCount) {
            if (data.placeState(i) == region) {
                latSum += data.latitude[i]
                lonSum += data.longitude[i]
                count++
            }
        }
        if (count == 0) return null
        return GeocodeResult(
            coordinate = GeoCoordinate(latSum / count / SCALE, lonSum / count / SCALE),
            label = STATE_NAMES.entries.firstOrNull { it.value == region }?.key
                ?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                ?: region,
            address = Address(region = region, country = "US"),
            precision = GeocodePrecision.REGION,
        )
    }

    // ---- freeform parsing ----

    /**
     * Splits freeform text into address components.
     *
     * Reads right to left, which is where the reliable structure is: a trailing ZIP, then
     * a trailing state, then a city. The city is taken as the *longest* trailing run of
     * words that names a real place, so "350 5th Ave New York" finds "New York" rather
     * than failing on the street, and "Salt Lake City" is not mistaken for "City".
     */
    internal fun parse(text: String): Address {
        val segments = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return Address()

        var tokens = segments.last().split(' ').filter { it.isNotEmpty() }
        var postalCode: String? = null
        var region: String? = null

        tokens.lastOrNull()?.let { last ->
            val digits = last.substringBefore('-')
            if (digits.length == 5 && digits.all { it.isDigit() }) {
                postalCode = digits
                tokens = tokens.dropLast(1)
            }
        }

        // State names can be several words ("New Hampshire"), so try the longest first.
        for (size in minOf(MAX_STATE_WORDS, tokens.size) downTo 1) {
            val candidate = tokens.takeLast(size).joinToString(" ")
            val code = stateCode(candidate)
            if (code != null) {
                region = code
                tokens = tokens.dropLast(size)
                break
            }
        }

        val leading = segments.dropLast(1)
        val cityWords = if (tokens.isEmpty() && leading.isNotEmpty()) {
            leading.last().split(' ').filter { it.isNotEmpty() }
        } else {
            tokens
        }
        val street = when {
            // Whatever preceded the city segment is the street, when commas told us so.
            tokens.isEmpty() && leading.size > 1 -> leading.dropLast(1).joinToString(", ")
            tokens.isNotEmpty() && leading.isNotEmpty() -> leading.joinToString(", ")
            else -> null
        }

        val locality = longestKnownPlace(cityWords, region)
        return Address(
            street = street ?: cityWords.dropLast(locality?.split(' ')?.size ?: 0)
                .joinToString(" ").ifEmpty { null },
            locality = locality,
            region = region,
            postalCode = postalCode,
            country = "US",
        )
    }

    /** The longest trailing run of [words] that names a place, or null if none does. */
    private fun longestKnownPlace(words: List<String>, region: String?): String? {
        for (size in words.size downTo 1) {
            val candidate = words.takeLast(size).joinToString(" ")
            if (resolveLocality(candidate, region).isNotEmpty()) return candidate
        }
        return null
    }

    public companion object {
        /** Configures offline geocoding. Equivalent to `Geocoding.Settings("local")`. */
        public fun Geocoding.Settings.Companion.local(): Geocoding.Settings = Geocoding.Settings("local")

        init {
            Geocoding.Settings.register("local") { name, _, context -> LocalGeocoding(name, context) }
        }

        /** Coordinates are stored as milli-degrees. */
        private const val SCALE = 1000.0

        /** Longest state name in words, e.g. "District of Columbia". */
        private const val MAX_STATE_WORDS = 3

        /** Fetch extra name matches before focus-sorting, so the nearest is not cut off first. */
        private const val MATCH_OVERSHOOT = 4

        private fun isUnitedStates(country: String?): Boolean =
            country == null || country.normalizedForGeocoding() in setOf("us", "usa", "united states", "united states of america")

        /** Resolves a state name or abbreviation to its USPS code, or null if it is neither. */
        private fun stateCode(value: String): String? {
            val normalized = value.normalizedForGeocoding()
            STATE_NAMES[normalized]?.let { return it }
            val upper = normalized.uppercase()
            return if (upper.length == 2 && upper in STATE_NAMES.values) upper else null
        }

        /**
         * The 52 USPS codes present in the Gazetteer. Needed because the source ships codes
         * only, while people type names.
         */
        private val STATE_NAMES: Map<String, String> = mapOf(
            "alabama" to "AL", "alaska" to "AK", "arizona" to "AZ", "arkansas" to "AR",
            "california" to "CA", "colorado" to "CO", "connecticut" to "CT", "delaware" to "DE",
            "district of columbia" to "DC", "florida" to "FL", "georgia" to "GA", "hawaii" to "HI",
            "idaho" to "ID", "illinois" to "IL", "indiana" to "IN", "iowa" to "IA",
            "kansas" to "KS", "kentucky" to "KY", "louisiana" to "LA", "maine" to "ME",
            "maryland" to "MD", "massachusetts" to "MA", "michigan" to "MI", "minnesota" to "MN",
            "mississippi" to "MS", "missouri" to "MO", "montana" to "MT", "nebraska" to "NE",
            "nevada" to "NV", "new hampshire" to "NH", "new jersey" to "NJ", "new mexico" to "NM",
            "new york" to "NY", "north carolina" to "NC", "north dakota" to "ND", "ohio" to "OH",
            "oklahoma" to "OK", "oregon" to "OR", "pennsylvania" to "PA", "puerto rico" to "PR",
            "rhode island" to "RI", "south carolina" to "SC", "south dakota" to "SD",
            "tennessee" to "TN", "texas" to "TX", "utah" to "UT", "vermont" to "VT",
            "virginia" to "VA", "washington" to "WA", "west virginia" to "WV",
            "wisconsin" to "WI", "wyoming" to "WY",
        )

        private fun squaredDistance(latA: Int, lonA: Int, latB: Int, lonB: Int, longitudeScale: Double): Double {
            val dLat = (latA - latB).toDouble()
            // Longitude degrees shrink toward the poles; without this correction, "nearest"
            // is wrong by 40% in Alaska.
            val dLon = (lonA - lonB).toDouble() * longitudeScale
            return dLat * dLat + dLon * dLon
        }
    }
}
