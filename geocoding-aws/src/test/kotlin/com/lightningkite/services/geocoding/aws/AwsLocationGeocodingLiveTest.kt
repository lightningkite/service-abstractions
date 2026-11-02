package com.lightningkite.services.geocoding.aws

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.aws.AwsConnections
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.geocoding.GeocodeQuery
import com.lightningkite.services.geocoding.test.GeocodingTests
import com.lightningkite.services.get
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.geoplaces.GeoPlacesAsyncClient
import kotlin.test.Test

/**
 * The shared contract suite against the real Amazon Location service, using the `lk` AWS
 * profile (region from `AWS_REGION`, default `us-west-2`).
 *
 * Skips, rather than fails, when the profile is missing or the service is unreachable or
 * unauthorized, which a health-check probe detects up front. Every request is billed.
 */
class AwsLocationGeocodingLiveTest : GeocodingTests() {

    override val geocoding: AwsLocationGeocoding? get() = live

    override val knownQuery: GeocodeQuery = GeocodeQuery.Text("Reno, NV")
    override val knownCoordinate: GeoCoordinate = GeoCoordinate(39.5296, -119.8138)

    /** Amazon Location autocomplete completes addresses, so a city-name prefix is the wrong probe. */
    override val autocompletePrefix: String = "1600 Pennsylvania Ave NW, Washing"

    override val unknownSuggestionId: String = "AQAAAFUAnotarealplaceid"

    companion object {
        /** Shared so the billed probe runs once, not once per test instance. */
        val live: AwsLocationGeocoding? by lazy {
            val context = TestSettingContext()
            val connections = context[AwsConnections]
            val geocoding = AwsLocationGeocoding(
                "aws-live", context,
                GeoPlacesAsyncClient.builder()
                    .region(Region.of(System.getenv("AWS_REGION") ?: "us-west-2"))
                    .credentialsProvider(ProfileCredentialsProvider.create("lk"))
                    .httpClient(connections.asyncClient)
                    .overrideConfiguration(connections.clientOverrideConfiguration)
                    .build(),
            )
            val health = runBlocking { withTimeoutOrNull(15_000) { geocoding.healthCheck() } }
            if (health == null || health.level == HealthStatus.Level.ERROR) {
                println("Skipping live Amazon Location tests; 'lk' profile unavailable or unauthorized: ${health?.additionalMessage ?: "timed out"}")
                return@lazy null
            }
            geocoding
        }
    }

    @Test
    fun check() {}
}
