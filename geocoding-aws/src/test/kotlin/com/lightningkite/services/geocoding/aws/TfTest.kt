package com.lightningkite.services.geocoding.aws

import com.lightningkite.services.geocoding.Geocoding
import com.lightningkite.services.terraform.AwsPolicyStatement
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.TerraformEmitterAwsTest
import com.lightningkite.services.test.assertPlannableAws
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class TfTest {

    init {
        // Force class init so AwsLocationGeocoding's companion registers the URL scheme;
        // the bare reference is the point of this line.
        @Suppress("UNUSED_EXPRESSION")
        AwsLocationGeocoding
    }

    @Test
    fun settingAndPolicy() {
        val emitter = TerraformEmitterAwsTest(File("build/test/awsLocation"), "geocoding", Geocoding.Settings.serializer())
        with(emitter) { TerraformNeed<Geocoding.Settings>("geocoding").awsLocation(storesResults = true) }
        assertEquals(JsonPrimitive("aws-location://us-west-2?intendedUse=Storage"), emitter.settings["geocoding"])
        assertEquals(
            listOf(
                AwsPolicyStatement(
                    action = listOf("geo-places:Geocode", "geo-places:ReverseGeocode", "geo-places:Autocomplete", "geo-places:GetPlace"),
                    resource = listOf("arn:aws:geo-places:us-west-2::provider/default"),
                )
            ),
            emitter.policyStatements,
        )
    }

    @Test
    fun plannable() {
        assertPlannableAws<Geocoding.Settings>("awsLocation") { it.awsLocation() }
    }
}
