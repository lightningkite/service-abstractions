package com.lightningkite.services.geocoding.local

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.geocoding.*
import com.lightningkite.services.geocoding.test.GeocodingTests
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalGeocodingContractTest : GeocodingTests() {
    override val geocoding: Geocoding = LocalGeocoding("local", TestSettingContext())
    override val knownQuery: GeocodeQuery = GeocodeQuery.Text("Reno, NV")
    override val knownCoordinate: GeoCoordinate = GeoCoordinate(39.5296, -119.8138)
}

/**
 * Behavior specific to the offline dataset: the freeform parser, precision honesty, and
 * the shape of the bundled data itself.
 */
class LocalGeocodingTest {

    private val geocoding = LocalGeocoding("local", TestSettingContext())

    private fun assertNear(expected: GeoCoordinate, actual: GeoCoordinate?, toleranceKm: Double = 15.0) {
        assertNotNull(actual, "Expected a result")
        val distance = expected distanceToKilometers actual
        assertTrue(distance <= toleranceKm, "Expected within ${toleranceKm}km of $expected, was $actual (${distance}km)")
    }

    @Test
    fun resolvesCityAndState() = runTest {
        assertNear(GeoCoordinate(38.5816, -121.4944), geocoding.geocodeOne("Sacramento, CA")?.coordinate)
        assertNear(GeoCoordinate(40.7608, -111.8910), geocoding.geocodeOne("Salt Lake City, UT")?.coordinate)
    }

    @Test
    fun resolvesSpelledOutStateNames() = runTest {
        assertNear(GeoCoordinate(38.5816, -121.4944), geocoding.geocodeOne("Sacramento, California")?.coordinate)
    }

    @Test
    fun resolvesZipCodes() = runTest {
        val result = geocoding.geocodeOne("89501")
        assertNear(GeoCoordinate(39.5296, -119.8138), result?.coordinate)
        assertEquals(GeocodePrecision.POSTAL_CODE, result?.precision)
        assertEquals("89501", result?.address?.postalCode)
    }

    @Test
    fun aZipBeatsACityWhenBothArePresent() = runTest {
        val result = geocoding.geocodeOne("350 5th Ave, New York, NY 10001")
        assertEquals(GeocodePrecision.POSTAL_CODE, result?.precision)
        assertNear(GeoCoordinate(40.7506, -73.9971), result?.coordinate)
    }

    @Test
    fun findsTheCityInsideAStreetAddressWithoutCommas() = runTest {
        val parsed = geocoding.parse("350 5th Ave New York NY")
        assertEquals("New York", parsed.locality)
        assertEquals("NY", parsed.region)
    }

    @Test
    fun prefersTheLongestPlaceNameMatch() = runTest {
        // "Salt Lake City" must not be truncated to a shorter place that also exists.
        assertEquals("Salt Lake City", geocoding.parse("Salt Lake City UT").locality)
    }

    @Test
    fun foldsAccentsSoUnaccentedInputMatches() = runTest {
        assertNotNull(geocoding.geocodeOne("Espanola, NM"), "Unaccented spelling should match Española")
        assertNotNull(geocoding.geocodeOne("Española, NM"))
    }

    @Test
    fun findsConsolidatedCityCountiesByTheirCityName() = runTest {
        // These are named after the merged county in the source data, so they only work
        // because of the alias table.
        assertNotNull(geocoding.geocodeOne("Augusta, GA"), "Augusta-Richmond County should match 'Augusta'")
        assertNotNull(geocoding.geocodeOne("Nashville, TN"), "Nashville-Davidson should match 'Nashville'")
        assertNotNull(geocoding.geocodeOne("Louisville, KY"))
        assertNotNull(geocoding.geocodeOne("Indianapolis, IN"))
    }

    @Test
    fun neverClaimsRooftopPrecision() = runTest {
        val result = geocoding.geocodeOne("1600 Pennsylvania Ave NW, Washington, DC 20500")
        assertNotNull(result)
        assertTrue(
            result.precision <= GeocodePrecision.POSTAL_CODE,
            "Offline data has no street-level detail, so it must not report ${result.precision}",
        )
    }

    @Test
    fun stateOnlyQueriesResolveToTheStateWithHonestPrecision() = runTest {
        val result = geocoding.geocodeOne("Nevada")
        assertNotNull(result)
        assertEquals(GeocodePrecision.REGION, result.precision)
    }

    @Test
    fun disambiguatesRepeatedNamesByState() = runTest {
        val springfields = geocoding.geocode("Springfield", limit = 20)
        assertTrue(springfields.size > 1, "Springfield exists in many states")

        val illinois = geocoding.geocodeOne("Springfield, IL")
        assertNotNull(illinois)
        assertEquals("IL", illinois.address.region)
        assertNear(GeoCoordinate(39.7817, -89.6501), illinois.coordinate)
    }

    @Test
    fun disambiguatesRepeatedNamesByFocusPoint() = runTest {
        val nearBoston = geocoding.geocodeOne("Springfield", focus = GeoCoordinate(42.3601, -71.0589))
        assertEquals("MA", nearBoston?.address?.region)
    }

    @Test
    fun reverseGeocodeNamesTheNearestCityAndZip() = runTest {
        val result = geocoding.reverseGeocodeOne(GeoCoordinate(39.5296, -119.8138))
        assertNotNull(result)
        assertEquals("Reno", result.address.locality)
        assertEquals("NV", result.address.region)
        assertNotNull(result.address.postalCode, "Reverse results should carry a ZIP")
    }

    @Test
    fun autocompleteCompletesCityNames() = runTest {
        val suggestions = geocoding.autocomplete(AutocompleteQuery("Sacrament", limit = 5))
        assertTrue(suggestions.any { it.label.startsWith("Sacramento") }, "Got $suggestions")
        assertTrue(suggestions.all { it.coordinate != null }, "Offline suggestions include coordinates")
    }

    @Test
    fun autocompleteCompletesZipCodes() = runTest {
        val suggestions = geocoding.autocomplete(AutocompleteQuery("895", limit = 5))
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.all { it.label.startsWith("895") }, "Got $suggestions")
    }

    @Test
    fun refusesCountriesItHasNoDataFor() = runTest {
        assertEquals(emptyList(), geocoding.geocode(Address(locality = "Paris", country = "FR")))
    }

    @Test
    fun healthCheckDescribesTheBundledDataset() = runTest {
        val health = geocoding.healthCheck()
        assertEquals(com.lightningkite.services.data.HealthStatus.Level.OK, health.level)
        assertTrue(health.additionalMessage?.contains("ZIP codes") == true, "Got ${health.additionalMessage}")
    }

    @Test
    fun registersItsUrlScheme() = runTest {
        val service = Geocoding.Settings("local")("geocoding", TestSettingContext())
        assertIs<LocalGeocoding>(service)
    }
}
