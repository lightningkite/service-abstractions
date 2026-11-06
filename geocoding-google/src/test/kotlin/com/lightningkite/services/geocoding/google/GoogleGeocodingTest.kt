package com.lightningkite.services.geocoding.google

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.geocoding.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class GoogleGeocodingTest {

    private val apiKey = "secret-key"
    private val requests = mutableListOf<HttpRequestData>()

    private fun geocoding(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GoogleGeocoding("google", TestSettingContext(), apiKey, HttpClient(MockEngine { request ->
            requests.add(request)
            handler(request)
        }))

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private val autocompleteResponse = """
        {"suggestions": [
          {"placePrediction": {
            "placeId": "ChIJ-place",
            "text": {"text": "350 5th Ave, New York, NY, USA"},
            "structuredFormat": {"mainText": {"text": "350 5th Ave"}, "secondaryText": {"text": "New York, NY, USA"}}
          }}
        ]}
    """

    private val placeResponse = """
        {
          "formattedAddress": "350 5th Ave, New York, NY 10118, USA",
          "types": ["premise", "establishment"],
          "location": {"latitude": 40.7484, "longitude": -73.9857},
          "viewport": {"low": {"latitude": 40.747, "longitude": -73.987}, "high": {"latitude": 40.749, "longitude": -73.984}},
          "addressComponents": [
            {"longText": "350", "shortText": "350", "types": ["street_number"]},
            {"longText": "5th Avenue", "shortText": "5th Ave", "types": ["route"]},
            {"longText": "New York", "shortText": "New York", "types": ["locality", "political"]},
            {"longText": "New York", "shortText": "NY", "types": ["administrative_area_level_1", "political"]},
            {"longText": "United States", "shortText": "US", "types": ["country", "political"]},
            {"longText": "10118", "shortText": "10118", "types": ["postal_code"]}
          ]
        }
    """

    @Test
    fun aSessionCarriesFromAutocompleteToResolve() = runTest {
        val geocoding = geocoding { request ->
            if (request.url.encodedPath.endsWith(":autocomplete")) json(autocompleteResponse) else json(placeResponse)
        }
        val session = AutocompleteSession.random()

        val suggestion = geocoding.autocomplete(
            AutocompleteQuery("350 5th", focus = GeoCoordinate(40.7, -74.0), country = "US", session = session)
        ).single()
        assertEquals("ChIJ-place", suggestion.id)
        assertEquals("350 5th Ave, New York, NY, USA", suggestion.label)

        val autocomplete = requests.single()
        assertEquals("https://places.googleapis.com/v1/places:autocomplete", autocomplete.url.toString())
        val body = Json.parseToJsonElement((autocomplete.body as TextContent).text).jsonObject
        assertEquals(session.token.toString(), body["sessionToken"]?.jsonPrimitive?.content)
        assertEquals("us", body["includedRegionCodes"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertNotNull(body["locationBias"]?.jsonObject?.get("circle"))

        val result = assertNotNull(geocoding.resolve(suggestion.id, session))
        val details = requests.last()
        assertEquals("/v1/places/ChIJ-place", details.url.encodedPath)
        assertEquals(session.token.toString(), details.url.parameters["sessionToken"])
        assertEquals("formattedAddress,addressComponents,location,viewport,types", details.headers["X-Goog-FieldMask"])

        for (request in requests) {
            assertEquals(apiKey, request.headers["X-Goog-Api-Key"])
            assertFalse(apiKey in request.url.toString(), "The key belongs in a header, not the URL")
        }

        assertEquals(GeoCoordinate(40.7484, -73.9857), result.coordinate)
        assertEquals(GeocodePrecision.ROOFTOP, result.precision)
        assertEquals(
            Address(street = "350 5th Avenue", locality = "New York", region = "NY", postalCode = "10118", country = "US"),
            result.address,
        )
        assertEquals(GeoCoordinate(40.747, -73.987), result.boundingBox?.southwest)
    }

    @Test
    fun withoutASessionNoTokenIsSent() = runTest {
        val geocoding = geocoding { json(placeResponse) }
        geocoding.resolve("ChIJ-place")
        assertNull(requests.single().url.parameters["sessionToken"])
    }

    @Test
    fun anUnknownPlaceResolvesToNull() = runTest {
        val geocoding = geocoding {
            json("""{"error": {"code": 404, "message": "Not found", "status": "NOT_FOUND"}}""", HttpStatusCode.NotFound)
        }
        assertNull(geocoding.resolve("ChIJ-gone"))
    }

    @Test
    fun placesErrorsBecomeGeocodingExceptions() = runTest {
        val geocoding = geocoding {
            json(
                """{"error": {"code": 403, "message": "Places API (New) has not been used in project 1", "status": "PERMISSION_DENIED"}}""",
                HttpStatusCode.Forbidden,
            )
        }
        val error = assertFailsWith<GeocodingException> { geocoding.autocomplete(AutocompleteQuery("350 5th")) }
        assertContains(error.message!!, "403")
    }
}
