package com.lightningkite.services.geocoding.aws

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.geocoding.*
import kotlinx.coroutines.test.runTest
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.geoplaces.GeoPlacesAsyncClient
import software.amazon.awssdk.services.geoplaces.model.*
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import software.amazon.awssdk.services.geoplaces.model.Address as AwsAddress

class AwsLocationGeocodingTest {

    private val empireState = AwsAddress.builder()
        .label("350 5th Ave, New York, NY 10118, United States")
        .addressNumber("350").street("5th Ave").locality("New York")
        .region { it.code("NY").name("New York") }
        .postalCode("10118")
        .country { it.code2("US").code3("USA").name("United States") }
        .build()

    /** Records each request and answers from the handlers a test supplies. */
    private class FakeClient : GeoPlacesAsyncClient {
        val requests = mutableListOf<Any>()
        var geocodeResponse: GeocodeResponse = GeocodeResponse.builder().build()
        var autocompleteResponse: AutocompleteResponse = AutocompleteResponse.builder().build()
        var getPlaceResponse: () -> GetPlaceResponse = { GetPlaceResponse.builder().build() }

        override fun geocode(request: GeocodeRequest): CompletableFuture<GeocodeResponse> {
            requests.add(request)
            return CompletableFuture.completedFuture(geocodeResponse)
        }

        override fun autocomplete(request: AutocompleteRequest): CompletableFuture<AutocompleteResponse> {
            requests.add(request)
            return CompletableFuture.completedFuture(autocompleteResponse)
        }

        override fun getPlace(request: GetPlaceRequest): CompletableFuture<GetPlaceResponse> {
            requests.add(request)
            return CompletableFuture.supplyAsync { getPlaceResponse() }
        }

        override fun serviceName(): String = "geo-places"
        override fun close() {}
    }

    private val client = FakeClient()
    private fun geocoding(storesResults: Boolean = false) =
        AwsLocationGeocoding("aws", TestSettingContext(), client, storesResults)

    @Test
    fun geocodeMapsResultsAndBiasesLongitudeFirst() = runTest {
        client.geocodeResponse = GeocodeResponse.builder().resultItems(
            GeocodeResultItem.builder()
                .placeId("here:pds:place:1").placeType(PlaceType.POINT_ADDRESS).title("350 5th Ave")
                .address(empireState)
                .position(-73.9857, 40.7484)
                .mapView(-73.987, 40.747, -73.984, 40.749)
                .matchScores { it.overall(0.97) }
                .build()
        ).build()

        val result = geocoding().geocode("350 5th Ave, New York", focus = GeoCoordinate(40.7, -74.0)).single()

        val request = client.requests.single() as GeocodeRequest
        assertEquals(listOf(-74.0, 40.7), request.biasPosition())
        assertEquals(GeocodeIntendedUse.SINGLE_USE, request.intendedUse())
        assertEquals(GeoCoordinate(40.7484, -73.9857), result.coordinate)
        assertEquals("350 5th Ave, New York, NY 10118, United States", result.label)
        assertEquals(
            Address(street = "350 5th Ave", locality = "New York", region = "NY", postalCode = "10118", country = "US"),
            result.address,
        )
        assertEquals(GeocodePrecision.ROOFTOP, result.precision)
        assertEquals(0.97, result.confidence)
        assertEquals(GeoBoundingBox(GeoCoordinate(40.747, -73.987), GeoCoordinate(40.749, -73.984)), result.boundingBox)
    }

    @Test
    fun structuredStreetsSplitOffTheHouseNumber() = runTest {
        geocoding().geocode(Address(street = "350 5th Ave", locality = "New York", region = "NY", country = "US"))
        val components = (client.requests.single() as GeocodeRequest).queryComponents()
        assertEquals("350", components.addressNumber())
        assertEquals("5th Ave", components.street())
        assertEquals("US", components.country())
    }

    @Test
    fun storingResultsIsDeclared() = runTest {
        geocoding(storesResults = true).geocode("Reno, NV")
        assertEquals(GeocodeIntendedUse.STORAGE, (client.requests.single() as GeocodeRequest).intendedUse())
    }

    @Test
    fun estimatedPointAddressesAreInterpolated() = runTest {
        client.geocodeResponse = GeocodeResponse.builder().resultItems(
            GeocodeResultItem.builder().placeType(PlaceType.POINT_ADDRESS).estimatedPointAddress(true)
                .title("1 Main St").position(-119.8, 39.5).build()
        ).build()
        assertEquals(GeocodePrecision.INTERPOLATED, geocoding().geocodeOne("1 Main St")?.precision)
    }

    @Test
    fun suggestionsResolveByPlaceId() = runTest {
        client.autocompleteResponse = AutocompleteResponse.builder().resultItems(
            AutocompleteResultItem.builder().placeId("here:pds:place:1").title("350 5th Ave, New York, NY")
                .address(empireState).build()
        ).build()
        client.getPlaceResponse = {
            GetPlaceResponse.builder().placeId("here:pds:place:1").placeType(PlaceType.POINT_ADDRESS)
                .address(empireState).position(-73.9857, 40.7484).build()
        }
        val geocoding = geocoding()

        val suggestion = geocoding.autocomplete(AutocompleteQuery("350 5th", country = "US")).single()
        assertEquals("here:pds:place:1", suggestion.id)
        assertEquals(listOf("US"), (client.requests.last() as AutocompleteRequest).filter().includeCountries())

        val resolved = assertNotNull(geocoding.resolve(suggestion.id, AutocompleteSession.random()))
        assertEquals("here:pds:place:1", (client.requests.last() as GetPlaceRequest).placeId())
        assertEquals(GeoCoordinate(40.7484, -73.9857), resolved.coordinate)
    }

    @Test
    fun anUnknownPlaceResolvesToNull() = runTest {
        client.getPlaceResponse = {
            throw GeoPlacesException.builder().statusCode(404)
                .awsErrorDetails(AwsErrorDetails.builder().errorMessage("Not found").build()).build()
        }
        assertNull(geocoding().resolve("here:pds:place:gone"))
    }

    @Test
    fun serviceErrorsBecomeGeocodingExceptions() = runTest {
        client.getPlaceResponse = {
            throw AccessDeniedException.builder().statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorMessage("not authorized").build()).build()
        }
        assertFailsWith<GeocodingException> { geocoding().resolve("here:pds:place:1") }
    }

    @Test
    fun urlsParse() {
        AwsLocationGeocoding  // registers the scheme
        assertIs<AwsLocationGeocoding>(Geocoding.Settings("aws-location://us-west-2")("aws", TestSettingContext()))
        assertIs<AwsLocationGeocoding>(
            Geocoding.Settings("aws-location://AKIA:secret@us-east-1?intendedUse=Storage")("aws", TestSettingContext())
        )
        assertFailsWith<IllegalArgumentException> {
            Geocoding.Settings("aws-location://us-west-2?intendedUse=Forever")("aws", TestSettingContext())
        }
    }
}
