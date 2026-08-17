package com.lightningkite.services.geocoding

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.data.HealthStatus

/**
 * An in-memory geocoder holding exactly the places you put in it.
 *
 * For unit tests that need geocoding to return something specific and never touch the
 * network. It starts empty, so a test states its own fixtures and nothing else can
 * accidentally match:
 *
 * ```kotlin
 * val geocoding = TestGeocoding("test", context).apply {
 *     add(GeoCoordinate(39.5296, -119.8138), Address(locality = "Reno", region = "NV"))
 * }
 * assertEquals(39.5296, geocoding.geocodeOne("Reno, NV")!!.coordinate.latitude, 0.001)
 * ```
 *
 * For realistic data without configuring anything, use `LocalGeocoding` from the
 * `geocoding-local` module instead — it answers real US queries offline.
 *
 * Matching is deliberately simple: a query matches when every word in it appears
 * somewhere in the entry's label, after [normalizedForGeocoding]. That is forgiving
 * enough that tests do not have to reproduce a provider's exact label format.
 */
public class TestGeocoding(
    override val name: String,
    override val context: SettingContext,
) : Geocoding {

    private val entries = mutableListOf<GeocodeResult>()

    /** Every place currently in the table, in insertion order. */
    public val contents: List<GeocodeResult> get() = entries.toList()

    /**
     * Adds a place this geocoder should know about.
     *
     * @param coordinate Where the place is
     * @param address Its components; also used to build the label when none is given
     * @param precision What precision results for this place should report
     * @param label Display label. Defaults to [Address.toSingleLine].
     */
    public fun add(
        coordinate: GeoCoordinate,
        address: Address,
        precision: GeocodePrecision = GeocodePrecision.ROOFTOP,
        label: String = address.toSingleLine(),
    ) {
        entries.add(GeocodeResult(coordinate, label, address, precision, confidence = 1.0))
    }

    /** Adds a fully-specified result. */
    public fun add(result: GeocodeResult) {
        entries.add(result)
    }

    /** Empties the table. */
    public fun clear() {
        entries.clear()
    }

    private fun GeocodeResult.searchText(): String =
        listOf(label, address.toSingleLine()).joinToString(" ").normalizedForGeocoding()

    override suspend fun geocode(query: GeocodeQuery): List<GeocodeResult> {
        val words = query.toSingleLine().normalizedForGeocoding().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries
            .filter { entry -> entry.searchText().let { text -> words.all { it in text } } }
            .take(query.limit)
    }

    override suspend fun reverseGeocode(query: ReverseGeocodeQuery): List<GeocodeResult> =
        entries
            .sortedBy { it.coordinate distanceToKilometers query.coordinate }
            .take(query.limit)

    override suspend fun autocomplete(query: AutocompleteQuery): List<AddressSuggestion> {
        val prefix = query.text.normalizedForGeocoding()
        if (prefix.isEmpty()) return emptyList()
        return entries
            .filter { prefix in it.searchText() }
            .take(query.limit)
            .map { AddressSuggestion(it.label, it.address, it.coordinate) }
    }

    /**
     * Always OK. The default health check looks up a real address, which an empty test
     * table would report as a misconfiguration rather than the intended blank slate.
     */
    override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
}
