package com.lightningkite.services.geocoding.stadia

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.geocoding.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class StadiaGeocodingTest {

    private val feature = """
        {"type": "FeatureCollection", "features": [{
          "type": "Feature",
          "geometry": {"type": "Point", "coordinates": [-73.9857, 40.7484]},
          "properties": {
            "gid": "openaddresses:address:us/ny/city_of_new_york:abc",
            "layer": "address", "accuracy": "point",
            "label": "350 5th Ave, New York, NY, USA",
            "housenumber": "350", "street": "5th Ave", "locality": "New York", "region_a": "NY",
            "postalcode": "10118", "country_code": "US"
          }
        }]}
    """

    @Test
    fun suggestionsResolveByGid() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val geocoding = StadiaGeocoding("stadia", TestSettingContext(), "key", baseClient = HttpClient(MockEngine { request ->
            requests.add(request)
            respond(feature, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))

        val suggestion = geocoding.autocomplete(AutocompleteQuery("350 5th")).single()
        assertEquals("openaddresses:address:us/ny/city_of_new_york:abc", suggestion.id)

        val result = assertNotNull(geocoding.resolve(suggestion.id, AutocompleteSession.random()))
        val place = requests.last()
        assertEquals("/geocoding/v1/place", place.url.encodedPath)
        assertEquals(suggestion.id, place.url.parameters["ids"])
        assertEquals(GeoCoordinate(40.7484, -73.9857), result.coordinate)
        assertEquals(GeocodePrecision.ROOFTOP, result.precision)
    }
}
