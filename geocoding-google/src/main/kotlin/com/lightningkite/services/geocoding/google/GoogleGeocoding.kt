package com.lightningkite.services.geocoding.google

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.geocoding.*
import com.lightningkite.services.http.client
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
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
 * ## Notes
 *
 * - **Autocomplete returns no coordinates.** Google's Places Autocomplete returns place
 *   IDs; resolving one to a position is a second, separately billed Place Details call.
 *   [AddressSuggestion.coordinate] is therefore null here. Geocode the label once the
 *   user picks a suggestion. See [AddressSuggestion.coordinate].
 * - **Autocomplete is billed per session, not per request**, if you send a session
 *   token. This implementation does not, so each keystroke is billed individually —
 *   debounce your input.
 * - **Structured queries become a components filter**, which Google treats as a hard
 *   restriction rather than a hint, so a wrong state yields no results rather than a
 *   wrong answer.
 * - **`ZERO_RESULTS` is not an error** and comes back as an empty list. Every other
 *   non-OK status raises [GeocodingException], including `OVER_QUERY_LIMIT`.
 */
public class GoogleGeocoding(
    override val name: String,
    override val context: SettingContext,
    private val apiKey: String,
    baseClient: HttpClient = client,
) : Geocoding {

    private val client = baseClient.config {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    private suspend inline fun <reified T : GoogleStatus> request(
        url: String,
        parameters: URLBuilder.() -> Unit,
    ): T {
        val built = URLBuilder(url).apply {
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
        val parsed: T = try {
            response.body()
        } catch (e: Exception) {
            throw GeocodingException("Could not parse the Google Maps response", e)
        }
        // Google reports application errors with HTTP 200 and a status field.
        if (parsed.status != "OK" && parsed.status != "ZERO_RESULTS") {
            throw GeocodingException("Google Maps returned ${parsed.status}: ${parsed.errorMessage ?: "no detail"}")
        }
        return parsed
    }

    override suspend fun geocode(query: GeocodeQuery): List<GeocodeResult> {
        val response = request<GoogleGeocodeResponse>(GEOCODE_URL) {
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
        request<GoogleGeocodeResponse>(GEOCODE_URL) {
            parameters.append("latlng", "${query.coordinate.latitude},${query.coordinate.longitude}")
        }.results.mapNotNull { it.toResult() }.take(query.limit)

    override suspend fun autocomplete(query: AutocompleteQuery): List<AddressSuggestion> =
        request<GooglePlacesResponse>(AUTOCOMPLETE_URL) {
            parameters.append("input", query.text)
            query.country?.let { parameters.append("components", "country:${it.lowercase()}") }
            query.focus?.let {
                parameters.append("location", "${it.latitude},${it.longitude}")
                // Google requires a radius alongside a location bias.
                parameters.append("radius", FOCUS_RADIUS_METERS.toString())
            }
        }.predictions.take(query.limit).map { prediction ->
            AddressSuggestion(
                label = prediction.description,
                address = Address(locality = prediction.structuredFormatting?.secondaryText),
                // Deliberately null: resolving this would require a second billed call.
                coordinate = null,
            )
        }

    public companion object {
        private const val GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json"
        private const val AUTOCOMPLETE_URL = "https://maps.googleapis.com/maps/api/place/autocomplete/json"

        /** Google rejects a location bias without a radius; 50 km biases without excluding much. */
        private const val FOCUS_RADIUS_METERS = 50_000

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

private interface GoogleStatus {
    val status: String
    val errorMessage: String?
}

@Serializable
private data class GoogleGeocodeResponse(
    override val status: String = "UNKNOWN_ERROR",
    @SerialName("error_message") override val errorMessage: String? = null,
    val results: List<GoogleResult> = emptyList(),
) : GoogleStatus

@Serializable
private data class GooglePlacesResponse(
    override val status: String = "UNKNOWN_ERROR",
    @SerialName("error_message") override val errorMessage: String? = null,
    val predictions: List<GooglePrediction> = emptyList(),
) : GoogleStatus

@Serializable
private data class GooglePrediction(
    val description: String = "",
    @SerialName("structured_formatting") val structuredFormatting: GoogleStructuredFormatting? = null,
)

@Serializable
private data class GoogleStructuredFormatting(
    @SerialName("main_text") val mainText: String? = null,
    @SerialName("secondary_text") val secondaryText: String? = null,
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
        val address = Address(
            street = listOfNotNull(component("street_number"), component("route"))
                .joinToString(" ").takeIf { it.isNotEmpty() },
            locality = component("locality") ?: component("postal_town") ?: component("sublocality"),
            region = component("administrative_area_level_1", short = true),
            postalCode = component("postal_code"),
            country = component("country", short = true),
        )
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

    private fun component(type: String, short: Boolean = false): String? =
        addressComponents.firstOrNull { type in it.types }
            ?.let { if (short) it.shortName ?: it.longName else it.longName }

    /**
     * `location_type` says how the position was derived; `APPROXIMATE` only says "some
     * area centroid", so the result's own types decide which kind of area it was.
     */
    private fun precision(): GeocodePrecision = when (geometry?.locationType) {
        "ROOFTOP" -> GeocodePrecision.ROOFTOP
        "RANGE_INTERPOLATED" -> GeocodePrecision.INTERPOLATED
        "GEOMETRIC_CENTER" -> if ("route" in types) GeocodePrecision.STREET else GeocodePrecision.LOCALITY
        "APPROXIMATE" -> when {
            "postal_code" in types -> GeocodePrecision.POSTAL_CODE
            "country" in types -> GeocodePrecision.COUNTRY
            "administrative_area_level_1" in types -> GeocodePrecision.REGION
            else -> GeocodePrecision.LOCALITY
        }
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
