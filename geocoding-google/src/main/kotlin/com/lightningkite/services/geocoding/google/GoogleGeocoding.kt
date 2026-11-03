package com.lightningkite.services.geocoding.google

import com.lightningkite.services.SettingContext
import com.lightningkite.services.Untested
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.geocoding.*
import com.lightningkite.services.http.client
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Geocoding via the [Google Maps Platform](https://developers.google.com/maps/documentation/geocoding).
 *
 * The most accurate option for most addresses, and the most restrictive to use. Read
 * Google's terms before adopting it: displaying results on a non-Google map is
 * prohibited, and coordinates generally may not be cached beyond 30 days.
 *
 * ## Supported URL Schemes
 *
 * - `google://apiKey`
 *
 * ```kotlin
 * Geocoding.Settings("google://$apiKey")
 * ```
 *
 * The key needs the Geocoding API and Places API (New) enabled.
 *
 * ## Notes
 *
 * - **Send an [AutocompleteSession].** Google bills autocomplete per session when the
 *   session ends in [resolve]: the first 12 requests are billed, the rest are free, and
 *   [resolve] is billed as Place Details Essentials. Without a session, every keystroke
 *   is billed.
 * - **[resolve] requests only Essentials fields.** Adding a Pro field (a phone number,
 *   opening hours) to its field mask bills every resolve at the Pro rate.
 * - **Structured queries become a components filter**, which Google treats as a hard
 *   restriction rather than a hint, so a wrong state yields no results rather than a
 *   wrong answer.
 * - **`ZERO_RESULTS` is not an error** and comes back as an empty list. Every other
 *   non-OK status raises [GeocodingException], including `OVER_QUERY_LIMIT`.
 */
@Untested
public class GoogleGeocoding(
    override val name: String,
    override val context: SettingContext,
    private val apiKey: String,
    baseClient: HttpClient = client,
) : Geocoding {

    private val client = baseClient.config {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
        }
    }

    /** The Geocoding API, which reports errors with HTTP 200 and a status field. */
    private suspend fun geocodingRequest(parameters: URLBuilder.() -> Unit): GoogleGeocodeResponse {
        val built = URLBuilder(GEOCODE_URL).apply {
            parameters()
            this.parameters.append("key", apiKey)
        }.buildString()

        val response: HttpResponse = try {
            client.get(built)
        } catch (e: Exception) {
            throw GeocodingException("Could not reach Google Maps", e)
        }
        if (!response.status.isSuccess()) {
            // The key is in the URL, so report the status and body but never the URL itself.
            throw GeocodingException("Google Maps returned ${response.status.value}: ${response.bodyAsText()}")
        }
        val parsed: GoogleGeocodeResponse = try {
            response.body()
        } catch (e: Exception) {
            throw GeocodingException("Could not parse the Google Maps response", e)
        }
        if (parsed.status != "OK" && parsed.status != "ZERO_RESULTS") {
            throw GeocodingException("Google Maps returned ${parsed.status}: ${parsed.errorMessage ?: "no detail"}")
        }
        return parsed
    }

    /** Places API (New), which reports errors with HTTP status codes. Null on 404. */
    private suspend inline fun <reified T> placesRequest(send: HttpClient.() -> HttpResponse): T? {
        val response: HttpResponse = try {
            client.send()
        } catch (e: Exception) {
            throw GeocodingException("Could not reach Google Places", e)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) {
            throw GeocodingException("Google Places returned ${response.status.value}: ${response.bodyAsText()}")
        }
        return try {
            response.body<T>()
        } catch (e: Exception) {
            throw GeocodingException("Could not parse the Google Places response", e)
        }
    }

    private fun HttpRequestBuilder.placesHeaders(fieldMask: String) {
        header("X-Goog-Api-Key", apiKey)
        header("X-Goog-FieldMask", fieldMask)
    }

    override suspend fun geocode(query: GeocodeQuery): List<GeocodeResult> {
        val response = geocodingRequest {
            when (query) {
                is GeocodeQuery.Text -> parameters.append("address", query.text)
                is GeocodeQuery.Structured -> {
                    query.address.street?.let { parameters.append("address", it) }
                    // Components are a filter, so only the unambiguous fields go here.
                    val components = listOfNotNull(
                        query.address.locality?.let { "locality:$it" },
                        query.address.region?.let { "administrative_area:$it" },
                        query.address.postalCode?.let { "postal_code:$it" },
                        query.address.country?.let { "country:$it" },
                    )
                    if (components.isNotEmpty()) parameters.append("components", components.joinToString("|"))
                }
            }
        }
        return response.results.mapNotNull { it.toResult() }.take(query.limit)
    }

    override suspend fun reverseGeocode(query: ReverseGeocodeQuery): List<GeocodeResult> =
        geocodingRequest {
            parameters.append("latlng", "${query.coordinate.latitude},${query.coordinate.longitude}")
        }.results.mapNotNull { it.toResult() }.take(query.limit)

    override suspend fun autocomplete(query: AutocompleteQuery): List<AddressSuggestion> {
        val request = PlacesAutocompleteRequest(
            input = query.text,
            sessionToken = query.session?.token?.toString(),
            includedRegionCodes = query.country?.let { listOf(it.lowercase()) },
            locationBias = query.focus?.let {
                PlacesLocationBias(PlacesCircle(PlacesLatLng(it.latitude, it.longitude), FOCUS_RADIUS_METERS))
            },
        )
        val response = placesRequest<PlacesAutocompleteResponse> {
            post(AUTOCOMPLETE_URL) {
                placesHeaders(AUTOCOMPLETE_FIELD_MASK)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } ?: throw GeocodingException("Google Places autocomplete returned 404")
        return response.suggestions.mapNotNull { it.placePrediction }.take(query.limit).map { prediction ->
            AddressSuggestion(
                id = prediction.placeId,
                label = prediction.text.text,
                address = Address(locality = prediction.structuredFormat?.secondaryText?.text),
            )
        }
    }

    override suspend fun resolve(suggestionId: String, session: AutocompleteSession?): GeocodeResult? =
        placesRequest<GooglePlace> {
            get("$PLACES_URL/${suggestionId.encodeURLPathPart()}") {
                placesHeaders(PLACE_FIELD_MASK)
                session?.let { parameter("sessionToken", it.token.toString()) }
            }
        }?.toResult()

    public companion object {
        private const val GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json"
        private const val PLACES_URL = "https://places.googleapis.com/v1/places"
        private const val AUTOCOMPLETE_URL = "$PLACES_URL:autocomplete"

        private const val AUTOCOMPLETE_FIELD_MASK =
            "suggestions.placePrediction.placeId,suggestions.placePrediction.text,suggestions.placePrediction.structuredFormat"

        /** All Essentials-tier fields, which keeps [resolve] on the cheapest Place Details SKU. */
        private const val PLACE_FIELD_MASK = "formattedAddress,addressComponents,location,viewport,types"

        /** 50 km biases toward the focus without excluding much. */
        private const val FOCUS_RADIUS_METERS = 50_000.0

        /** Configures Google Maps geocoding. */
        public fun Geocoding.Settings.Companion.google(apiKey: String): Geocoding.Settings =
            Geocoding.Settings("google://$apiKey")

        init {
            Geocoding.Settings.register("google") { name, url, context ->
                val key = url.removePrefix("google://")
                require(key.isNotBlank()) { "Invalid Google URL. Expected google://[apiKey]" }
                GoogleGeocoding(name, context, key)
            }
        }
    }
}

/**
 * Both Google APIs use the same component types; only the field names holding the text differ.
 * [component] returns the long or short text for the first component of a type.
 */
private fun googleAddress(component: (type: String, short: Boolean) -> String?) = Address(
    street = listOfNotNull(component("street_number", false), component("route", false))
        .joinToString(" ").takeIf { it.isNotEmpty() },
    locality = component("locality", false) ?: component("postal_town", false) ?: component("sublocality", false),
    region = component("administrative_area_level_1", true),
    postalCode = component("postal_code", false),
    country = component("country", true),
)

/** What kind of area a result is, judged from its types alone. */
private fun areaPrecision(types: List<String>): GeocodePrecision = when {
    "postal_code" in types -> GeocodePrecision.POSTAL_CODE
    "country" in types -> GeocodePrecision.COUNTRY
    "administrative_area_level_1" in types -> GeocodePrecision.REGION
    else -> GeocodePrecision.LOCALITY
}

@Serializable
private data class GoogleGeocodeResponse(
    val status: String = "UNKNOWN_ERROR",
    @SerialName("error_message") val errorMessage: String? = null,
    val results: List<GoogleResult> = emptyList(),
)

@Serializable
private data class GoogleResult(
    @SerialName("formatted_address") val formattedAddress: String? = null,
    @SerialName("address_components") val addressComponents: List<GoogleComponent> = emptyList(),
    val geometry: GoogleGeometry? = null,
    val types: List<String> = emptyList(),
) {
    fun toResult(): GeocodeResult? {
        val location = geometry?.location ?: return null
        val address = googleAddress { type, short ->
            addressComponents.firstOrNull { type in it.types }
                ?.let { if (short) it.shortName ?: it.longName else it.longName }
        }
        return GeocodeResult(
            coordinate = GeoCoordinate(location.lat, location.lng),
            label = formattedAddress ?: address.toSingleLine(),
            address = address,
            precision = precision(),
            // Google publishes no confidence score.
            confidence = null,
            boundingBox = geometry.viewport?.let {
                GeoBoundingBox(
                    southwest = GeoCoordinate(it.southwest.lat, it.southwest.lng),
                    northeast = GeoCoordinate(it.northeast.lat, it.northeast.lng),
                )
            },
        )
    }

    /**
     * `location_type` says how the position was derived; `APPROXIMATE` only says "some
     * area centroid", so the result's own types decide which kind of area it was.
     */
    private fun precision(): GeocodePrecision = when (geometry?.locationType) {
        "ROOFTOP" -> GeocodePrecision.ROOFTOP
        "RANGE_INTERPOLATED" -> GeocodePrecision.INTERPOLATED
        "GEOMETRIC_CENTER" -> if ("route" in types) GeocodePrecision.STREET else GeocodePrecision.LOCALITY
        "APPROXIMATE" -> areaPrecision(types)
        else -> GeocodePrecision.UNKNOWN
    }
}

@Serializable
private data class GoogleComponent(
    @SerialName("long_name") val longName: String? = null,
    @SerialName("short_name") val shortName: String? = null,
    val types: List<String> = emptyList(),
)

@Serializable
private data class GoogleGeometry(
    val location: GoogleLatLng? = null,
    @SerialName("location_type") val locationType: String? = null,
    val viewport: GoogleViewport? = null,
)

@Serializable
private data class GoogleLatLng(val lat: Double = 0.0, val lng: Double = 0.0)

@Serializable
private data class GoogleViewport(val southwest: GoogleLatLng, val northeast: GoogleLatLng)

// ---- Places API (New) ----

@Serializable
private data class PlacesAutocompleteRequest(
    val input: String,
    val sessionToken: String? = null,
    val includedRegionCodes: List<String>? = null,
    val locationBias: PlacesLocationBias? = null,
)

@Serializable
private data class PlacesLocationBias(val circle: PlacesCircle)

@Serializable
private data class PlacesCircle(val center: PlacesLatLng, val radius: Double)

@Serializable
private data class PlacesLatLng(val latitude: Double, val longitude: Double)

@Serializable
private data class PlacesAutocompleteResponse(val suggestions: List<PlacesSuggestion> = emptyList())

/** Query predictions arrive only when requested, which this client never does. */
@Serializable
private data class PlacesSuggestion(val placePrediction: PlacesPrediction? = null)

@Serializable
private data class PlacesPrediction(
    val placeId: String,
    val text: PlacesText,
    val structuredFormat: PlacesStructuredFormat? = null,
)

@Serializable
private data class PlacesText(val text: String)

@Serializable
private data class PlacesStructuredFormat(val mainText: PlacesText? = null, val secondaryText: PlacesText? = null)

@Serializable
private data class GooglePlace(
    val formattedAddress: String? = null,
    val addressComponents: List<PlacesComponent> = emptyList(),
    val location: PlacesLatLng? = null,
    val viewport: PlacesViewport? = null,
    val types: List<String> = emptyList(),
) {
    fun toResult(): GeocodeResult? {
        val location = location ?: return null
        val address = googleAddress { type, short ->
            addressComponents.firstOrNull { type in it.types }
                ?.let { if (short) it.shortText ?: it.longText else it.longText }
        }
        return GeocodeResult(
            coordinate = GeoCoordinate(location.latitude, location.longitude),
            label = formattedAddress ?: address.toSingleLine(),
            address = address,
            precision = precision(),
            confidence = null,
            boundingBox = viewport?.let {
                GeoBoundingBox(
                    southwest = GeoCoordinate(it.low.latitude, it.low.longitude),
                    northeast = GeoCoordinate(it.high.latitude, it.high.longitude),
                )
            },
        )
    }

    /**
     * Place Details has no `location_type`, so only the types say how exact the position is.
     * A plain `street_address` may be interpolated, so it does not claim rooftop.
     */
    private fun precision(): GeocodePrecision = when {
        types.any { it == "premise" || it == "subpremise" || it == "establishment" } -> GeocodePrecision.ROOFTOP
        "street_address" in types -> GeocodePrecision.INTERPOLATED
        "route" in types -> GeocodePrecision.STREET
        else -> areaPrecision(types)
    }
}

@Serializable
private data class PlacesComponent(
    val longText: String? = null,
    val shortText: String? = null,
    val types: List<String> = emptyList(),
)

@Serializable
private data class PlacesViewport(val low: PlacesLatLng, val high: PlacesLatLng)
