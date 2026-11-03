package com.lightningkite.services.geocoding.azure

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.azure.core.credential.TokenRequestContext
import com.azure.identity.DefaultAzureCredentialBuilder
import com.lightningkite.services.SettingContext
import com.lightningkite.services.Untested
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.geocoding.*
import com.lightningkite.services.http.client as sharedClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.ParametersBuilder
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

/**
 * How [AzureMapsGeocoding] proves it may use an Azure Maps account.
 */
public sealed interface AzureMapsAuth {
    /** The account's shared key. Grants every Azure Maps API, so keep it server-side. */
    public class SubscriptionKey(public val key: String) : AzureMapsAuth

    /**
     * A Microsoft Entra ID identity with an Azure Maps data-reader role on the account.
     *
     * @property clientId The Azure Maps *account's* client ID (not an app registration's),
     * from the account's Authentication page.
     */
    public class EntraId(
        public val clientId: String,
        public val credential: TokenCredential = DefaultAzureCredentialBuilder().build(),
    ) : AzureMapsAuth
}

/**
 * Geocoding via [Azure Maps](https://learn.microsoft.com/en-us/azure/azure-maps/) Search,
 * API version `2026-01-01`.
 *
 * ## Supported URL Schemes
 *
 * - `azure-maps://subscriptionKey`
 * - `azure-maps://accountClientId?auth=entra` — Microsoft Entra ID through
 *   `DefaultAzureCredential` (managed identity, environment, `az login`)
 *
 * ```kotlin
 * Geocoding.Settings("azure-maps://$subscriptionKey")
 * ```
 *
 * ## Notes
 *
 * - **Autocomplete returns neither coordinates nor a place ID**, and Azure Maps has no
 *   place-details lookup. A suggestion's [AddressSuggestion.id] therefore carries its
 *   structured address, and [resolve] geocodes that address. Suggestions are limited to
 *   addresses so that this lookup lands on the place the user picked.
 * - **Sessions are ignored.** Azure bills autocomplete as one transaction per ten requests,
 *   and [resolve] as one geocode.
 * - **[GeocodeResult.confidence] is always null.** Azure reports High/Medium/Low, not a score.
 * - **At most 20 results** per request, Azure's cap; larger limits return 20.
 */
@Untested
public class AzureMapsGeocoding(
    override val name: String,
    override val context: SettingContext,
    private val auth: AzureMapsAuth,
    private val client: HttpClient = sharedClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : Geocoding {

    private val json = Json { ignoreUnknownKeys = true }

    private val tokenLock = Mutex()
    private var token: AccessToken? = null

    /** Entra tokens last about an hour; fetching one per request would double latency. */
    private suspend fun entraToken(entra: AzureMapsAuth.EntraId): String = tokenLock.withLock {
        token?.takeIf { it.expiresAt.isAfter(OffsetDateTime.now().plusMinutes(5)) }?.token
            ?: withContext(Dispatchers.IO) {
                entra.credential.getTokenSync(TokenRequestContext().addScopes(ENTRA_SCOPE))
            }.also { token = it }.token
    }

    private suspend fun features(path: String, parameters: ParametersBuilder.() -> Unit): List<AzureFeature> {
        val headers = when (auth) {
            is AzureMapsAuth.SubscriptionKey -> mapOf("subscription-key" to auth.key)
            is AzureMapsAuth.EntraId -> mapOf(
                "x-ms-client-id" to auth.clientId,
                HttpHeaders.Authorization to "Bearer ${entraToken(auth)}",
            )
        }
        val response: HttpResponse = try {
            client.get("$endpoint/$path") {
                url {
                    this.parameters.append("api-version", API_VERSION)
                    this.parameters.parameters()
                }
                headers.forEach { (name, value) -> header(name, value) }
            }
        } catch (e: Exception) {
            throw GeocodingException("Could not reach Azure Maps", e)
        }
        // Credentials travel in headers, so the body is safe to report.
        if (!response.status.isSuccess()) {
            throw GeocodingException("Azure Maps returned ${response.status.value}: ${response.bodyAsText()}")
        }
        // Azure answers application/geo+json, which ktor's JSON content negotiation would not accept.
        return try {
            json.decodeFromString(AzureFeatureCollection.serializer(), response.bodyAsText()).features
        } catch (e: Exception) {
            throw GeocodingException("Could not parse the Azure Maps response", e)
        }
    }

    override suspend fun geocode(query: GeocodeQuery): List<GeocodeResult> =
        geocodeFeatures(query).mapNotNull { it.toResult() }

    private suspend fun geocodeFeatures(query: GeocodeQuery): List<AzureFeature> =
        features("geocode") {
            when (query) {
                is GeocodeQuery.Text -> append("query", query.text)
                is GeocodeQuery.Structured -> {
                    query.address.street?.let { append("addressLine", it) }
                    query.address.locality?.let { append("locality", it) }
                    query.address.region?.let { append("adminDistrict", it) }
                    query.address.postalCode?.let { append("postalCode", it) }
                    query.address.country?.let { append("countryRegion", it) }
                }
            }
            query.focus?.let { append("coordinates", "${it.longitude},${it.latitude}") }
            append("top", query.limit.coerceAtMost(MAX_RESULTS).toString())
        }

    /** Azure returns only the single most specific match, so [ReverseGeocodeQuery.limit] above 1 changes nothing. */
    override suspend fun reverseGeocode(query: ReverseGeocodeQuery): List<GeocodeResult> =
        features("reverseGeocode") {
            append("coordinates", "${query.coordinate.longitude},${query.coordinate.latitude}")
        }.mapNotNull { it.toResult() }.take(query.limit)

    override suspend fun autocomplete(query: AutocompleteQuery): List<AddressSuggestion> =
        features("geocode:autocomplete") {
            append("query", query.text)
            // Azure requires a point or a box; without a focus, the box is the whole world.
            val focus = query.focus
            if (focus != null) append("coordinates", "${focus.longitude},${focus.latitude}")
            else append("bbox", "-180,-90,180,90")
            query.country?.let { append("countryRegion", it) }
            append("resultTypeGroups", "Address")
            append("top", query.limit.coerceAtMost(MAX_RESULTS).toString())
        }.mapNotNull { feature ->
            val address = feature.properties.address?.toAddress()?.takeUnless { it.isEmpty } ?: return@mapNotNull null
            AddressSuggestion(
                id = json.encodeToString(Address.serializer(), address),
                label = feature.properties.address.formattedAddress ?: address.toSingleLine(),
                address = address,
            )
        }

    /**
     * The ID is the suggestion's structured address, which is geocoded here. Azure answers an
     * address it cannot find with a coarser area (`UpHierarchy`), such as the whole country;
     * that is a different place from the one picked, so it resolves to null.
     */
    override suspend fun resolve(suggestionId: String, session: AutocompleteSession?): GeocodeResult? {
        val address = json.decodeFromString(Address.serializer(), suggestionId)
        return geocodeFeatures(GeocodeQuery.Structured(address, limit = 1))
            .filterNot { "UpHierarchy" in it.properties.matchCodes }
            .firstNotNullOfOrNull { it.toResult() }
    }

    public companion object {
        public const val DEFAULT_ENDPOINT: String = "https://atlas.microsoft.com"
        private const val API_VERSION = "2026-01-01"
        private const val ENTRA_SCOPE = "https://atlas.microsoft.com/.default"
        private const val MAX_RESULTS = 20

        /** Configures Azure Maps geocoding with a subscription key. */
        public fun Geocoding.Settings.Companion.azureMaps(subscriptionKey: String): Geocoding.Settings =
            Geocoding.Settings("azure-maps://$subscriptionKey")

        /** Configures Azure Maps geocoding with Microsoft Entra ID, using `DefaultAzureCredential`. */
        public fun Geocoding.Settings.Companion.azureMapsEntra(accountClientId: String): Geocoding.Settings =
            Geocoding.Settings("azure-maps://$accountClientId?auth=entra")

        init {
            Geocoding.Settings.register("azure-maps") { name, url, context ->
                val rest = url.removePrefix("azure-maps://")
                val credential = rest.substringBefore('?')
                require(credential.isNotBlank()) {
                    "Invalid Azure Maps URL. Expected azure-maps://[subscriptionKey] or azure-maps://[accountClientId]?auth=entra"
                }
                val auth = when (val query = rest.substringAfter('?', "")) {
                    "" -> AzureMapsAuth.SubscriptionKey(credential)
                    "auth=entra" -> AzureMapsAuth.EntraId(credential)
                    else -> throw IllegalArgumentException("Unknown Azure Maps URL options '$query'. The only option is auth=entra")
                }
                AzureMapsGeocoding(name, context, auth)
            }
        }
    }
}

/**
 * Display points report how they were placed, which is the real precision signal; the
 * entity type only says what kind of area a pointless-by-nature result covers.
 */
private fun precisionOf(type: String?, calculationMethod: String?): GeocodePrecision = when (calculationMethod) {
    // A parcel center is on the lot rather than the roof, close enough for dispatch.
    "Rooftop", "Parcel" -> GeocodePrecision.ROOFTOP
    "Interpolation", "InterpolationOffset" -> GeocodePrecision.INTERPOLATED
    else -> when (type) {
        "RoadBlock", "RoadIntersection" -> GeocodePrecision.STREET
        "Postcode1" -> GeocodePrecision.POSTAL_CODE
        "Neighborhood", "PopulatedPlace" -> GeocodePrecision.LOCALITY
        "AdminDivision1", "AdminDivision2" -> GeocodePrecision.REGION
        "CountryRegion" -> GeocodePrecision.COUNTRY
        else -> GeocodePrecision.UNKNOWN
    }
}

@Serializable
private data class AzureFeatureCollection(val features: List<AzureFeature> = emptyList())

@Serializable
private data class AzureFeature(
    val geometry: AzurePoint? = null,
    val properties: AzureProperties = AzureProperties(),
    val bbox: List<Double>? = null,
) {
    fun toResult(): GeocodeResult? {
        // GeoJSON orders coordinates longitude first.
        val coordinates = geometry?.coordinates?.takeIf { it.size >= 2 } ?: return null
        val address = properties.address?.toAddress() ?: Address()
        val display = properties.geocodePoints.firstOrNull { "Display" in it.usageTypes } ?: properties.geocodePoints.firstOrNull()
        return GeocodeResult(
            coordinate = GeoCoordinate(latitude = coordinates[1], longitude = coordinates[0]),
            label = properties.address?.formattedAddress ?: address.toSingleLine(),
            address = address,
            precision = precisionOf(properties.type, display?.calculationMethod),
            confidence = null,
            // bbox is [west, south, east, north].
            boundingBox = bbox?.takeIf { it.size == 4 }?.let {
                GeoBoundingBox(GeoCoordinate(it[1], it[0]), GeoCoordinate(it[3], it[2]))
            },
        )
    }
}

@Serializable
private data class AzurePoint(val coordinates: List<Double> = emptyList())

@Serializable
private data class AzureProperties(
    val type: String? = null,
    val address: AzureAddress? = null,
    val matchCodes: List<String> = emptyList(),
    val geocodePoints: List<AzureGeocodePoint> = emptyList(),
)

@Serializable
private data class AzureGeocodePoint(
    val calculationMethod: String? = null,
    val usageTypes: List<String> = emptyList(),
)

@Serializable
private data class AzureAddress(
    val addressLine: String? = null,
    val streetNumber: String? = null,
    val streetName: String? = null,
    val locality: String? = null,
    /** Coarsest first: the state or province, then the county. */
    val adminDistricts: List<AzureAdminDistrict> = emptyList(),
    val postalCode: String? = null,
    val countryRegion: AzureCountryRegion? = null,
    val formattedAddress: String? = null,
) {
    fun toAddress() = Address(
        street = addressLine ?: listOfNotNull(streetNumber, streetName).joinToString(" ").takeIf { it.isNotEmpty() },
        locality = locality,
        region = adminDistricts.firstOrNull()?.let { it.shortName ?: it.name },
        postalCode = postalCode,
        country = countryRegion?.iso,
    )
}

@Serializable
private data class AzureAdminDistrict(val name: String? = null, val shortName: String? = null)

@Serializable
private data class AzureCountryRegion(@SerialName("ISO") val iso: String? = null, val name: String? = null)
