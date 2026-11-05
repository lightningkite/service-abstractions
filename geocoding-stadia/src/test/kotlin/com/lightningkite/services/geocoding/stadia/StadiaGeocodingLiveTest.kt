package com.lightningkite.services.geocoding.stadia

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.geocoding.GeocodeQuery
import com.lightningkite.services.geocoding.Geocoding
import com.lightningkite.services.geocoding.test.GeocodingTests
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test

/**
 * The shared contract suite against the real Stadia Maps service, configured by the setting
 * URL (`stadia://<key>`) in the uncommitted `../secrets/stadia.txt`.
 *
 * Skips, rather than fails, when the file is missing or the service is unreachable or
 * unauthorized, which a health-check probe detects up front.  Every request is billed.
 */
class StadiaGeocodingLiveTest : GeocodingTests() {

    override val geocoding: StadiaGeocoding? get() = live

    override val knownQuery: GeocodeQuery = GeocodeQuery.Text("Reno, NV")
    override val knownCoordinate: GeoCoordinate = GeoCoordinate(39.5296, -119.8138)

    override val unknownSuggestionId: String = "openaddresses:address:us/nv/zzqqxx:zzqqxx"

    companion object {
        /** Shared so the billed probe runs once, not once per test instance. */
        val live: StadiaGeocoding? by lazy {
            val file = File("../secrets/stadia.txt")
            if (!file.exists()) {
                println("Skipping live Stadia tests; ${file.absolutePath} is missing")
                return@lazy null
            }
            @Suppress("UNUSED_EXPRESSION")
            StadiaGeocoding // Registers the stadia:// scheme.
            val geocoding = Geocoding.Settings(file.readText().trim())("stadia-live", TestSettingContext()) as StadiaGeocoding
            val health = runBlocking { withTimeoutOrNull(15_000) { geocoding.healthCheck() } }
            if (health == null || health.level == HealthStatus.Level.ERROR) {
                println("Skipping live Stadia tests; unreachable or unauthorized: ${health?.additionalMessage ?: "timed out"}")
                return@lazy null
            }
            geocoding
        }
    }

    @Test fun test() {

    }

    override fun reverseGeocodeDescribesKnownCoordinate() { /*skip, requires paid key*/ }
}
