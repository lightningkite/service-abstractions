package com.lightningkite.services.geocoding.aws

import com.lightningkite.services.geocoding.Geocoding
import com.lightningkite.services.geocoding.aws.AwsLocationGeocoding.Companion.awsLocation
import com.lightningkite.services.terraform.AwsPolicyStatement
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.json.JsonPrimitive
import software.amazon.awssdk.regions.Region

/**
 * Configures Amazon Location geocoding.
 *
 * Amazon Location's Places API has no resources to create, so this only fulfills the
 * `aws-location://` setting and grants the role the `geo-places` actions [AwsLocationGeocoding] calls.
 *
 * @param region AWS region. Defaults to emitter's applicationRegion.
 * @param storesResults Whether results are kept (including via [com.lightningkite.services.geocoding.CachedGeocoding]),
 *   which AWS requires to be requested as `intendedUse=Storage` and bills at a higher rate.
 *
 * ```kotlin
 * context(emitter) {
 *     need<Geocoding.Settings>("geocoding").awsLocation()
 * }
 * ```
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<Geocoding.Settings>.awsLocation(
    region: String = emitter.applicationRegion,
    storesResults: Boolean = false,
): Unit {
    if (!Geocoding.Settings.supports("aws-location")) throw IllegalArgumentException("You need to reference AwsLocationGeocoding in your server definition to use this.")
    emitter.fulfillSetting(name, JsonPrimitive(Geocoding.Settings.awsLocation(Region.of(region), storesResults).url))
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    emitter.policyStatements += AwsPolicyStatement(
        action = listOf(
            "geo-places:Geocode",
            "geo-places:ReverseGeocode",
            "geo-places:Autocomplete",
            "geo-places:GetPlace",
        ),
        resource = listOf("arn:aws:geo-places:${region}::provider/default")
    )
}
