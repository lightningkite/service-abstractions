package com.lightningkite.services.geocoding.azure

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.geocoding.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import kotlin.test.*

class AzureMapsGeocodingTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun geocoding(
        auth: AzureMapsAuth = AzureMapsAuth.SubscriptionKey("secret-key"),
        status: HttpStatusCode = HttpStatusCode.OK,
        body: (HttpRequestData) -> String,
    ) = AzureMapsGeocoding("azure", TestSettingContext(), auth, HttpClient(MockEngine { request ->
        requests.add(request)
        respond(body(request), status, headersOf(HttpHeaders.ContentType, "application/geo+json"))
    }))

    /** Microsoft's sample response for "15127 NE 24th Street, Redmond, WA". */
    private val geocodeResponse = """
        {"type": "FeatureCollection", "features": [{
          "type": "Feature",
          "properties": {
            "address": {
              "countryRegion": {"name": "United States", "ISO": "US"},
              "adminDistricts": [{"shortName": "WA"}, {"shortName": "King County"}],
              "formattedAddress": "15127 NE 24th St, Redmond, WA 98052",
              "streetName": "NE 24th St", "streetNumber": "15127", "locality": "Redmond",
              "postalCode": "98052", "addressLine": "15127 NE 24th St"
            },
            "type": "Address", "confidence": "High", "matchCodes": ["Good"],
            "geocodePoints": [
              {"geometry": {"type": "Point", "coordinates": [-122.138681, 47.630358]}, "calculationMethod": "Rooftop", "usageTypes": ["Display"]},
              {"geometry": {"type": "Point", "coordinates": [-122.1386787, 47.6302179]}, "calculationMethod": "Interpolation", "usageTypes": ["Route"]}
            ]
          },
          "geometry": {"type": "Point", "coordinates": [-122.138681, 47.630358]},
          "bbox": [-122.14632282407, 47.626495282429325, -122.13103917593001, 47.63422071757068]
        }]}
    """

    /** Microsoft's sample autocomplete response for "1 Microsoft": no geometry, no ID. */
    private val autocompleteResponse = """
        {"type": "FeatureCollection", "features": [{
          "type": "Feature", "geometry": null,
          "properties": {
            "typeGroup": "Address", "type": "Address",
            "address": {
              "locality": "Redmond",
              "adminDistricts": [{"name": "Washington", "shortName": "WA"}, {"name": "King"}],
              "countryRegion": {"ISO": "US", "name": "United States"},
              "postalCode": "98052", "streetNumber": "1", "streetName": "Microsoft Way",
              "addressLine": "1 Microsoft Way",
              "formattedAddress": "1 Microsoft Way, Redmond, WA 98052, United States"
            }
          }
        }]}
    """

    private val redmond = Address(street = "15127 NE 24th St", locality = "Redmond", region = "WA", postalCode = "98052", country = "US")

    @Test
    fun geocodeParsesTheSampleResponse() = runTest {
        val result = geocoding { geocodeResponse }
            .geocode("15127 NE 24th Street Redmond WA", focus = GeoCoordinate(47.6, -122.1))
            .single()

        val request = requests.single()
        assertEquals("/geocode", request.url.encodedPath)
        assertEquals("2026-01-01", request.url.parameters["api-version"])
        assertEquals("-122.1,47.6", request.url.parameters["coordinates"])
        assertEquals("secret-key", request.headers["subscription-key"])
        assertFalse("secret-key" in request.url.toString())

        assertEquals(GeoCoordinate(47.630358, -122.138681), result.coordinate)
        assertEquals("15127 NE 24th St, Redmond, WA 98052", result.label)
        assertEquals(redmond, result.address)
        // The Display point decides; the Route point's interpolation does not.
        assertEquals(GeocodePrecision.ROOFTOP, result.precision)
        assertEquals(GeoCoordinate(47.626495282429325, -122.14632282407), result.boundingBox?.southwest)
    }

    @Test
    fun structuredQueriesUseAzuresFields() = runTest {
        geocoding { geocodeResponse }.geocode(redmond)
        val parameters = requests.single().url.parameters
        assertEquals("15127 NE 24th St", parameters["addressLine"])
        assertEquals("WA", parameters["adminDistrict"])
        assertEquals("US", parameters["countryRegion"])
        assertNull(parameters["query"], "Azure rejects mixing query with structured fields")
    }

    @Test
    fun limitsAboveAzuresCapAreClamped() = runTest {
        geocoding { geocodeResponse }.geocode(GeocodeQuery.Text("Redmond", limit = 50))
        assertEquals("20", requests.single().url.parameters["top"])
    }

    @Test
    fun suggestionsResolveByGeocodingTheirAddress() = runTest {
        val geocoding = geocoding { if (it.url.encodedPath.endsWith(":autocomplete")) autocompleteResponse else geocodeResponse }

        val suggestion = geocoding.autocomplete(AutocompleteQuery("1 Microsoft", country = "US")).single()
        val autocomplete = requests.single().url.parameters
        assertEquals("Address", autocomplete["resultTypeGroups"])
        assertEquals("-180,-90,180,90", autocomplete["bbox"], "Azure needs a box when there is no focus")
        assertEquals("1 Microsoft Way, Redmond, WA 98052, United States", suggestion.label)

        assertNotNull(geocoding.resolve(suggestion.id, AutocompleteSession.random()))
        val resolve = requests.last().url.parameters
        assertEquals("1 Microsoft Way", resolve["addressLine"])
        assertEquals("Redmond", resolve["locality"])
        assertEquals("98052", resolve["postalCode"])
        assertEquals("1", resolve["top"])
    }

    @Test
    fun resolvingAnAddressAzureCannotFindReturnsNull() = runTest {
        val geocoding = geocoding { """{"type": "FeatureCollection", "features": []}""" }
        val id = Json.encodeToString(Address.serializer(), Address(street = "1 Nowhere Ln", locality = "Atlantis"))
        assertNull(geocoding.resolve(id))
    }

    @Test
    fun resolvingRejectsFallbacksToCoarserAreas() = runTest {
        val geocoding = geocoding {
            geocodeResponse.replace("\"type\": \"Address\"", "\"type\": \"CountryRegion\"")
                .replace("\"Good\"", "\"UpHierarchy\"")
        }
        assertNull(geocoding.resolve(Json.encodeToString(Address.serializer(), redmond)))
    }

    @Test
    fun entraTokensAreSentAndReused() = runTest {
        var issued = 0
        val credential = TokenCredential {
            issued++
            Mono.just(AccessToken("entra-token", OffsetDateTime.now().plusHours(1)))
        }
        val geocoding = geocoding(AzureMapsAuth.EntraId("account-client-id", credential)) { geocodeResponse }

        geocoding.geocode("Redmond")
        geocoding.geocode("Seattle")

        for (request in requests) {
            assertEquals("Bearer entra-token", request.headers[HttpHeaders.Authorization])
            assertEquals("account-client-id", request.headers["x-ms-client-id"])
        }
        assertEquals(1, issued)
    }

    @Test
    fun errorsBecomeGeocodingExceptions() = runTest {
        val geocoding = geocoding(status = HttpStatusCode.Unauthorized) {
            """{"error": {"code": "401 Unauthorized", "message": "Authentication failed"}}"""
        }
        val error = assertFailsWith<GeocodingException> { geocoding.geocode("Redmond") }
        assertContains(error.message!!, "401")
    }

    @Test
    fun urlsParse() {
        AzureMapsGeocoding  // registers the scheme
        assertIs<AzureMapsGeocoding>(Geocoding.Settings("azure-maps://key")("azure", TestSettingContext()))
        assertIs<AzureMapsGeocoding>(Geocoding.Settings("azure-maps://client-id?auth=entra")("azure", TestSettingContext()))
        assertFailsWith<IllegalArgumentException> { Geocoding.Settings("azure-maps://key?auth=sas")("azure", TestSettingContext()) }
    }
}
