package com.lightningkite.services.geocoding.azure

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.geocoding.Address
import com.lightningkite.services.geocoding.GeocodeQuery
import com.lightningkite.services.geocoding.test.GeocodingTests
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.test.Test

/**
 * The shared contract suite against the real Azure Maps service, authenticating with
 * Microsoft Entra ID through `DefaultAzureCredential` (so `az login` works).
 *
 * Needs `AZURE_MAPS_CLIENT_ID`: the Azure Maps account's client ID, from its Authentication
 * page, not an app registration's. The signed-in identity needs the Azure Maps Data Reader
 * role on the account.
 *
 * Skips, rather than fails, when the variable is unset or the service is unreachable or
 * unauthorized, which a health-check probe detects up front. Every request is billed.
 */
class AzureMapsGeocodingLiveTest : GeocodingTests() {

    override val geocoding: AzureMapsGeocoding? get() = live

    override val knownQuery: GeocodeQuery = GeocodeQuery.Text("Reno, NV")
    override val knownCoordinate: GeoCoordinate = GeoCoordinate(39.5296, -119.8138)

    /** Azure Maps suggestions are limited to addresses, so a city-name prefix is the wrong probe. */
    override val autocompletePrefix: String = "1600 Pennsylvania Ave NW, Washing"

    /** IDs are structured addresses, so an unknown one is an address that does not exist. */
    override val unknownSuggestionId: String = Json.encodeToString(
        Address.serializer(),
        Address(street = "99999 Zzqqxx Nowhere Ln", locality = "Zzqqxx", postalCode = "00000", country = "US"),
    )

    companion object {
        /** Shared so the billed probe runs once, not once per test instance. */
        val live: AzureMapsGeocoding? by lazy {
            val clientId = "e7a660d3-ce11-49bd-8a0b-0f2131075ce3"
            val geocoding = AzureMapsGeocoding("azure-live", TestSettingContext(), AzureMapsAuth.EntraId(clientId))
            val health = runBlocking { withTimeoutOrNull(30_000) { geocoding.healthCheck() } }
            if (health == null || health.level == HealthStatus.Level.ERROR) {
                println("Skipping live Azure Maps tests; unreachable or unauthorized: ${health?.additionalMessage ?: "timed out"}")
                return@lazy null
            }
            geocoding
        }
    }

    @Test
    fun test(){}
}
