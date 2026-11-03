package com.lightningkite.services.geocoding.aws

import com.lightningkite.services.SettingContext
import com.lightningkite.services.Untested
import com.lightningkite.services.get
import com.lightningkite.services.aws.AwsConnections
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.geocoding.*
import kotlinx.coroutines.future.await
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.geoplaces.GeoPlacesAsyncClient
import software.amazon.awssdk.services.geoplaces.model.AutocompleteIntendedUse
import software.amazon.awssdk.services.geoplaces.model.GeocodeIntendedUse
import software.amazon.awssdk.services.geoplaces.model.GetPlaceIntendedUse
import software.amazon.awssdk.services.geoplaces.model.PlaceType
import software.amazon.awssdk.services.geoplaces.model.ReverseGeocodeIntendedUse
import software.amazon.awssdk.services.geoplaces.model.ValidationException
import software.amazon.awssdk.services.geoplaces.model.Address as AwsAddress

/**
 * Geocoding via [Amazon Location Service](https://docs.aws.amazon.com/location/latest/developerguide/places-concepts.html)
 * (the Places v2 API).
 *
 * Authenticates with IAM like any other AWS service, so it fits deployments that already
 * run on AWS without another vendor key. The role needs `geo-places:Geocode`,
 * `geo-places:ReverseGeocode`, `geo-places:Autocomplete`, and `geo-places:GetPlace`. In Terraform,
 * `need<Geocoding.Settings>("geocoding").awsLocation()` sets the URL and grants them.
 *
 * ## Supported URL Schemes
 *
 * - `aws-location://region` — default credential chain (environment, instance role, ...)
 * - `aws-location://accessKey:secretKey@region`
 * - Append `?intendedUse=Storage` if you keep results. See below.
 *
 * ```kotlin
 * Geocoding.Settings("aws-location://us-west-2")
 * ```
 *
 * ## Notes
 *
 * - **Storing results needs `intendedUse=Storage`**, which AWS bills at a higher rate.
 *   The default, `SingleUse`, forbids keeping results, and wrapping this in
 *   [CachedGeocoding] counts as keeping them.
 * - **Sessions are ignored.** AWS bills each autocomplete request, and [resolve] is one
 *   `GetPlace` request.
 * - **Autocomplete completes addresses only**, not business names.
 * - **[GeocodeResult.confidence] is AWS's overall match score**, reported only by forward
 *   geocoding.
 */
public class AwsLocationGeocoding(
    override val name: String,
    override val context: SettingContext,
    private val client: GeoPlacesAsyncClient,
    private val storesResults: Boolean = false,
) : Geocoding {

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: SdkException) {
        throw GeocodingException("Amazon Location failed: ${e.message}", e)
    }

    override suspend fun geocode(query: GeocodeQuery): List<GeocodeResult> = call {
        client.geocode { request ->
            when (query) {
                is GeocodeQuery.Text -> request.queryText(query.text)
                // AWS wants the house number apart from the street name, so a leading number is split off.
                is GeocodeQuery.Structured -> request.queryComponents {
                    val street = query.address.street?.let { Regex("""(\d+[A-Za-z]?)\s+(.+)""").matchEntire(it.trim()) }
                    if (street != null) {
                        it.addressNumber(street.groupValues[1])
                        it.street(street.groupValues[2])
                    } else {
                        it.street(query.address.street)
                    }
                    it.locality(query.address.locality)
                    it.region(query.address.region)
                    it.postalCode(query.address.postalCode)
                    it.country(query.address.country)
                }
            }
            query.focus?.let { request.biasPosition(it.longitude, it.latitude) }
            request.maxResults(query.limit)
            request.intendedUse(if (storesResults) GeocodeIntendedUse.STORAGE else GeocodeIntendedUse.SINGLE_USE)
        }.await().resultItems().mapNotNull {
            result(it.placeType(), it.title(), it.address(), it.position(), it.mapView(), it.estimatedPointAddress(), it.matchScores()?.overall())
        }
    }

    override suspend fun reverseGeocode(query: ReverseGeocodeQuery): List<GeocodeResult> = call {
        client.reverseGeocode { request ->
            request.queryPosition(query.coordinate.longitude, query.coordinate.latitude)
            request.maxResults(query.limit)
            request.intendedUse(if (storesResults) ReverseGeocodeIntendedUse.STORAGE else ReverseGeocodeIntendedUse.SINGLE_USE)
        }.await().resultItems().mapNotNull {
            result(it.placeType(), it.title(), it.address(), it.position(), it.mapView(), it.estimatedPointAddress(), confidence = null)
        }
    }

    override suspend fun autocomplete(query: AutocompleteQuery): List<AddressSuggestion> = call {
        client.autocomplete { request ->
            request.queryText(query.text)
            request.maxResults(query.limit)
            query.focus?.let { request.biasPosition(it.longitude, it.latitude) }
            query.country?.let { country -> request.filter { it.includeCountries(country) } }
            // Autocomplete has no Storage option; its results are suggestions, not places.
            request.intendedUse(AutocompleteIntendedUse.SINGLE_USE)
        }.await().resultItems().map {
            AddressSuggestion(id = it.placeId(), label = it.title(), address = it.address().toAddress())
        }
    }

    override suspend fun resolve(suggestionId: String, session: AutocompleteSession?): GeocodeResult? = call {
        val place = try {
            client.getPlace { request ->
                request.placeId(suggestionId)
                request.intendedUse(if (storesResults) GetPlaceIntendedUse.STORAGE else GetPlaceIntendedUse.SINGLE_USE)
            }.await()
        } catch (e: ValidationException) {
            // AWS answers an unknown place ID with "'PlaceId' must be a valid ID", and the ID is the only input here.
            return@call null
        }
        result(place.placeType(), place.title(), place.address(), place.position(), place.mapView(), place.estimatedPointAddress(), confidence = null)
    }

    /** The three AWS result types share these fields but no common type. */
    private fun result(
        placeType: PlaceType?,
        title: String?,
        address: AwsAddress?,
        position: List<Double>,
        mapView: List<Double>,
        estimatedPointAddress: Boolean?,
        confidence: Double?,
    ): GeocodeResult? {
        // AWS orders coordinates longitude first.
        if (position.size < 2) return null
        val parsed = address.toAddress()
        return GeocodeResult(
            coordinate = GeoCoordinate(latitude = position[1], longitude = position[0]),
            label = address?.label() ?: title ?: parsed.toSingleLine(),
            address = parsed,
            precision = precisionOf(placeType, estimatedPointAddress == true),
            confidence = confidence,
            // mapView is [minLon, minLat, maxLon, maxLat].
            boundingBox = mapView.takeIf { it.size == 4 }?.let {
                GeoBoundingBox(GeoCoordinate(it[1], it[0]), GeoCoordinate(it[3], it[2]))
            },
        )
    }

    public companion object {
        /** Configures Amazon Location geocoding with the default credential chain. */
        public fun Geocoding.Settings.Companion.awsLocation(region: Region, storesResults: Boolean = false): Geocoding.Settings =
            Geocoding.Settings("aws-location://$region" + if (storesResults) "?intendedUse=Storage" else "")

        init {
            Geocoding.Settings.register("aws-location") { name, url, context ->
                val match = Regex("""aws-location://(?:(?<access>[^:@]+):(?<secret>[^@]+)@)?(?<region>[^/?]+)/?(?:\?intendedUse=(?<use>\w+))?""")
                    .matchEntire(url)
                    ?: throw IllegalArgumentException(
                        "Invalid Amazon Location URL. Expected aws-location://[accessKey:secretKey@]region[?intendedUse=Storage|SingleUse]"
                    )
                val access = match.groups["access"]?.value
                val secret = match.groups["secret"]?.value
                val storesResults = when (val use = match.groups["use"]?.value) {
                    null, "SingleUse" -> false
                    "Storage" -> true
                    else -> throw IllegalArgumentException("Unknown intendedUse '$use'. Expected Storage or SingleUse")
                }
                val connections = context[AwsConnections]
                val client = GeoPlacesAsyncClient.builder()
                    .region(Region.of(match.groups["region"]!!.value))
                    .credentialsProvider(
                        if (access != null && secret != null) StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))
                        else DefaultCredentialsProvider.builder().build()
                    )
                    .httpClient(connections.asyncClient)
                    .overrideConfiguration(connections.clientOverrideConfiguration)
                    .build()
                AwsLocationGeocoding(name, context, client, storesResults)
            }
        }
    }
}

private fun AwsAddress?.toAddress(): Address {
    if (this == null) return Address()
    return Address(
        street = listOfNotNull(addressNumber(), street()).joinToString(" ").takeIf { it.isNotEmpty() },
        locality = locality(),
        region = region()?.let { it.code() ?: it.name() },
        postalCode = postalCode(),
        country = country()?.code2(),
    )
}

/** AWS flags point addresses it estimated rather than surveyed, which is interpolation by another name. */
private fun precisionOf(type: PlaceType?, estimated: Boolean): GeocodePrecision = when (type) {
    PlaceType.POINT_ADDRESS, PlaceType.SECONDARY_ADDRESS, PlaceType.POINT_OF_INTEREST ->
        if (estimated) GeocodePrecision.INTERPOLATED else GeocodePrecision.ROOFTOP
    PlaceType.INTERPOLATED_ADDRESS, PlaceType.INFERRED_SECONDARY_ADDRESS -> GeocodePrecision.INTERPOLATED
    PlaceType.STREET, PlaceType.INTERSECTION -> GeocodePrecision.STREET
    PlaceType.POSTAL_CODE -> GeocodePrecision.POSTAL_CODE
    PlaceType.LOCALITY, PlaceType.DISTRICT, PlaceType.SUB_DISTRICT, PlaceType.BLOCK, PlaceType.SUB_BLOCK -> GeocodePrecision.LOCALITY
    PlaceType.REGION, PlaceType.SUB_REGION -> GeocodePrecision.REGION
    PlaceType.COUNTRY -> GeocodePrecision.COUNTRY
    PlaceType.UNKNOWN_TO_SDK_VERSION, null -> GeocodePrecision.UNKNOWN
}
