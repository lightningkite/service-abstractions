package com.lightningkite.services.geocoding.test

import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.geocoding.*
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * The behavior every [Geocoding] implementation must share, regardless of provider.
 *
 * Extend it and supply a service plus one address you know it can resolve:
 *
 * ```kotlin
 * class LocalGeocodingTest : GeocodingTests() {
 *     override val geocoding = LocalGeocoding("test", TestSettingContext())
 *     override val knownQuery = GeocodeQuery.Text("Reno, NV")
 *     override val knownCoordinate = GeoCoordinate(39.5296, -119.8138)
 * }
 * ```
 *
 * The tolerance defaults are loose on purpose. Providers legitimately disagree about
 * where a city "is" by several kilometers, and a contract suite that fails on that is
 * testing the provider's opinion rather than this library's correctness.
 */
public abstract class GeocodingTests {

    /** The service under test, or null to skip the suite (e.g. live credentials are unavailable). */
    public abstract val geocoding: Geocoding?

    /** An address the service is expected to resolve. */
    public abstract val knownQuery: GeocodeQuery

    /** Roughly where [knownQuery] is. */
    public abstract val knownCoordinate: GeoCoordinate

    /** How far a result may sit from [knownCoordinate] and still be considered correct. */
    public open val toleranceKilometers: Double get() = 25.0

    /**
     * Text that should match nothing. Fuzzy geocoders match any real word, so it has none:
     * an earlier default containing "nowhere township" correctly matched Nowhere, Oklahoma.
     */
    public open val nonsenseQuery: String get() = "zzqqxx qqvvzz xxzzqq"

    /** A prefix that should produce suggestions. Override for providers with narrow data. */
    public open val autocompletePrefix: String get() = "Sacrament"

    /** A well-formed suggestion ID the provider does not know. */
    public abstract val unknownSuggestionId: String

    @Test
    public fun geocodeFindsKnownAddress(): TestResult = runTest {
        val geocoding = geocoding ?: return@runTest
        val results = geocoding.geocode(knownQuery)
        assertTrue(results.isNotEmpty(), "Expected at least one result for $knownQuery")
        val distance = results.first().coordinate distanceToKilometers knownCoordinate
        assertTrue(
            distance <= toleranceKilometers,
            "Best result was ${distance}km from the expected location, which exceeds ${toleranceKilometers}km",
        )
    }

    @Test
    public fun geocodeResultsAreWellFormed(): TestResult = runTest {
        val geocoding = geocoding ?: return@runTest
        for (result in geocoding.geocode(knownQuery)) {
            assertTrue(result.label.isNotBlank(), "Results must carry a displayable label")
            assertTrue(
                result.coordinate.latitude in -90.0..90.0 && result.coordinate.longitude in -180.0..180.0,
                "Coordinate ${result.coordinate} is outside the valid range",
            )
            result.confidence?.let {
                assertTrue(it in 0.0..1.0, "Confidence $it is outside 0.0..1.0")
            }
            result.boundingBox?.let {
                assertTrue(
                    it.southwest.latitude <= it.northeast.latitude,
                    "Bounding box corners are swapped: $it",
                )
            }
        }
    }

    @Test
    public fun geocodeRespectsLimit(): TestResult = runTest {
        val geocoding = geocoding ?: return@runTest
        val results = geocoding.geocode(GeocodeQuery.Text(knownQuery.toSingleLine(), limit = 1))
        assertTrue(results.size <= 1, "Asked for 1 result, got ${results.size}")
    }

    @Test
    public fun unrecognizedAddressReturnsEmptyRatherThanThrowing(): TestResult = runTest {
        val geocoding = geocoding ?: return@runTest
        assertEquals(emptyList(), geocoding.geocode(GeocodeQuery.Text(nonsenseQuery)))
    }

    @Test
    public open fun reverseGeocodeDescribesKnownCoordinate(): TestResult = runTest {
        val geocoding = geocoding ?: return@runTest
        val results = geocoding.reverseGeocode(ReverseGeocodeQuery(knownCoordinate))
        assertTrue(results.isNotEmpty(), "Expected a reverse result for $knownCoordinate")
        val distance = results.first().coordinate distanceToKilometers knownCoordinate
        assertTrue(
            distance <= toleranceKilometers,
            "Reverse result was ${distance}km away, which exceeds ${toleranceKilometers}km",
        )
    }

    @Test
    public fun autocompleteReturnsUsableSuggestions(): TestResult = runTest {
        val geocoding = geocoding ?: return@runTest
        val suggestions = geocoding.autocomplete(AutocompleteQuery(autocompletePrefix, limit = 5))
        assertTrue(suggestions.isNotEmpty(), "Expected suggestions for '$autocompletePrefix'")
        assertTrue(suggestions.size <= 5, "Asked for 5 suggestions, got ${suggestions.size}")
        assertTrue(suggestions.all { it.label.isNotBlank() }, "Suggestions must carry a label")
    }

    @Test
    public fun autocompleteSuggestionsResolve(): TestResult = runTest {
        val geocoding = geocoding ?: return@runTest
        val session = AutocompleteSession.random()
        val suggestion = geocoding.autocomplete(AutocompleteQuery(autocompletePrefix, session = session)).first()
        val resolved = assertNotNull(geocoding.resolve(suggestion.id, session), "Could not resolve $suggestion")
        assertTrue(resolved.label.isNotBlank(), "Resolved results must carry a label")
    }

    @Test
    public fun resolvingAnUnknownIdReturnsNull(): TestResult = runTest {
        val geocoding = geocoding ?: return@runTest
        assertNull(geocoding.resolve(unknownSuggestionId))
    }

    @Test
    public fun blankQueriesAreRejectedUpFront(): TestResult = runTest {
        assertFailsWith<IllegalArgumentException> { GeocodeQuery.Text("   ") }
        assertFailsWith<IllegalArgumentException> { AutocompleteQuery("") }
        assertFailsWith<IllegalArgumentException> { GeocodeQuery.Structured(Address()) }
    }

    @Test
    public fun healthCheckReports(): TestResult = runTest {
        val geocoding = geocoding ?: return@runTest
        geocoding.connect()
        assertNotNull(geocoding.healthCheck().level)
        geocoding.disconnect()
    }
}
