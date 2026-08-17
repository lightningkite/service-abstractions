package com.lightningkite.services.geocoding.stadia

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
 * Geocoding via [Stadia Maps](https://stadiamaps.com), a hosted [Pelias](https://pelias.io) instance.
 *
 * Pelias is open source and built on OpenStreetMap, OpenAddresses and Who's On First, so
 * results are permissively licensed and Stadia's terms are unusually relaxed about
 * storing them — worth knowing if you intend to cache coordinates in your own database.
 *
 * ## Supported URL Schemes
 *
 * - `stadia://apiKey` — the default endpoint
 * - `stadia://apiKey@api-eu.stadiamaps.com` — the EU endpoint, for data residency
 *
 * ```kotlin
 * Geocoding.Settings("stadia://$apiKey")
 * ```
 *
 * ## Notes
 *
 * - **Structured queries use a different endpoint.** [GeocodeQuery.Structured] is sent to
 *   `/search/structured`, which matches better than jamming the components into one string.
 * - **Authentication is a query parameter**, per Stadia's API. Avoid logging raw request URLs.
 * - **[GeocodeResult.confidence] is Pelias's own 0–1 score** and is not comparable to
 *   other providers' numbers.
 * - **Autocomplete includes coordinates**, so no follow-up lookup is needed.
 */
public class StadiaGeocoding(
    override val name: String,
    override val context: SettingContext,
    private val apiKey: String,
    private val host: String = DEFAULT_HOST,
    baseClient: HttpClient = client,
) : Geocoding {

    private val client = baseClient.config {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    private suspend fun request(path: String, parameters: URLBuilder.() -> Unit): PeliasResponse {
        val url = URLBuilder("https://$host/geocoding/v1/$path").apply {
            parameters()
            this.parameters.append("api_key", apiKey)
        }.buildString()

        val response: HttpResponse = try {
            client.get(url)
        } catch (e: Exception) {
            throw GeocodingException("Could not reach Stadia Maps", e)
        }
        if (!response.status.isSuccess()) {
            // The key is in the URL, so report the status and body but never the URL itself.
            throw GeocodingException("Stadia Maps returned ${response.status.value}: ${response.bodyAsText()}")
        }
        return try {
            response.body()
        } catch (e: Exception) {
            throw GeocodingException("Could not parse the Stadia Maps response", e)
        }
    }

    override suspend fun geocode(query: GeocodeQuery): List<GeocodeResult> {
        val response = when (query) {
            is GeocodeQuery.Text -> request("search") {
                parameters.append("text", query.text)
                parameters.append("size", query.limit.toString())
                query.focus?.let {
                    parameters.append("focus.point.lat", it.latitude.toString())
                    parameters.append("focus.point.lon", it.longitude.toString())
                }
            }

            is GeocodeQuery.Structured -> request("search/structured") {
                query.address.street?.let { parameters.append("address", it) }
                query.address.locality?.let { parameters.append("locality", it) }
                query.address.region?.let { parameters.append("region", it) }
                query.address.postalCode?.let { parameters.append("postalcode", it) }
                query.address.country?.let { parameters.append("country", it) }
                parameters.append("size", query.limit.toString())
                query.focus?.let {
                    parameters.append("focus.point.lat", it.latitude.toString())
                    parameters.append("focus.point.lon", it.longitude.toString())
                }
            }
        }
        return response.features.mapNotNull { it.toResult() }
    }

    override suspend fun reverseGeocode(query: ReverseGeocodeQuery): List<GeocodeResult> =
        request("reverse") {
            parameters.append("point.lat", query.coordinate.latitude.toString())
            parameters.append("point.lon", query.coordinate.longitude.toString())
            parameters.append("size", query.limit.toString())
        }.features.mapNotNull { it.toResult() }

    override suspend fun autocomplete(query: AutocompleteQuery): List<AddressSuggestion> =
        request("autocomplete") {
            parameters.append("text", query.text)
            parameters.append("size", query.limit.toString())
            query.country?.let { parameters.append("boundary.country", it) }
            query.focus?.let {
                parameters.append("focus.point.lat", it.latitude.toString())
                parameters.append("focus.point.lon", it.longitude.toString())
            }
        }.features.mapNotNull { feature ->
            feature.toResult()?.let { AddressSuggestion(it.label, it.address, it.coordinate) }
        }

    public companion object {
        public const val DEFAULT_HOST: String = "api.stadiamaps.com"

        /** Configures Stadia Maps geocoding. */
        public fun Geocoding.Settings.Companion.stadia(apiKey: String): Geocoding.Settings =
            Geocoding.Settings("stadia://$apiKey")

        init {
            Geocoding.Settings.register("stadia") { name, url, context ->
                val credentials = url.removePrefix("stadia://")
                require(credentials.isNotBlank()) {
                    "Invalid Stadia URL. Expected stadia://[apiKey] or stadia://[apiKey]@[host]"
                }
                StadiaGeocoding(
                    name = name,
                    context = context,
                    apiKey = credentials.substringBefore('@'),
                    host = credentials.substringAfter('@', DEFAULT_HOST),
                )
            }
        }
    }
}

/**
 * Maps Pelias's `layer` and `accuracy` onto [GeocodePrecision].
 *
 * `accuracy` distinguishes a known building position (`point`) from the centroid of
 * whatever area matched (`centroid`), which is exactly the distinction that decides
 * whether an address result is trustworthy.
 */
private fun precisionOf(layer: String?, accuracy: String?): GeocodePrecision = when (layer) {
    "venue" -> GeocodePrecision.ROOFTOP
    "address" -> if (accuracy == "point") GeocodePrecision.ROOFTOP else GeocodePrecision.INTERPOLATED
    "street" -> GeocodePrecision.STREET
    "postalcode" -> GeocodePrecision.POSTAL_CODE
    "neighbourhood", "borough", "locality", "localadmin" -> GeocodePrecision.LOCALITY
    "county", "macrocounty", "region", "macroregion" -> GeocodePrecision.REGION
    "country", "dependency" -> GeocodePrecision.COUNTRY
    else -> GeocodePrecision.UNKNOWN
}

@Serializable
private data class PeliasResponse(val features: List<PeliasFeature> = emptyList())

@Serializable
private data class PeliasFeature(
    val geometry: PeliasGeometry? = null,
    val properties: PeliasProperties = PeliasProperties(),
    val bbox: List<Double>? = null,
) {
    fun toResult(): GeocodeResult? {
        // GeoJSON orders coordinates longitude first.
        val coordinates = geometry?.coordinates ?: return null
        if (coordinates.size < 2) return null
        val address = Address(
            street = listOfNotNull(properties.houseNumber, properties.street)
                .joinToString(" ").takeIf { it.isNotEmpty() },
            locality = properties.locality ?: properties.localAdmin,
            region = properties.regionCode ?: properties.region,
            postalCode = properties.postalCode,
            country = properties.countryCode,
        )
        return GeocodeResult(
            coordinate = GeoCoordinate(latitude = coordinates[1], longitude = coordinates[0]),
            label = properties.label ?: properties.name ?: address.toSingleLine(),
            address = address,
            precision = precisionOf(properties.layer, properties.accuracy),
            confidence = properties.confidence,
            // bbox is [minLon, minLat, maxLon, maxLat].
            boundingBox = bbox?.takeIf { it.size >= 4 }?.let {
                GeoBoundingBox(GeoCoordinate(it[1], it[0]), GeoCoordinate(it[3], it[2]))
            },
        )
    }
}

@Serializable
private data class PeliasGeometry(val coordinates: List<Double> = emptyList())

@Serializable
private data class PeliasProperties(
    val label: String? = null,
    val name: String? = null,
    @SerialName("housenumber") val houseNumber: String? = null,
    val street: String? = null,
    val locality: String? = null,
    @SerialName("localadmin") val localAdmin: String? = null,
    val region: String? = null,
    @SerialName("region_a") val regionCode: String? = null,
    @SerialName("postalcode") val postalCode: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val confidence: Double? = null,
    val layer: String? = null,
    val accuracy: String? = null,
)
