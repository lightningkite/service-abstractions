package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.Database
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.assertPlannableAwsVpc
import com.lightningkite.services.test.expensive
import kotlin.test.Test

class TfTest {
    @Test fun testServerless() {
        assertPlannableAws<Database.Settings>(
            name = "mongodb-serverless",
            fulfill = {
                it.mongodbAtlasServerless(
                    orgId = "test-org-id",
                    continuousBackupEnabled = true
                )
            }
        )
    }
    @Test fun testFlex() {
        assertPlannableAws<Database.Settings>(
            name = "mongodb-serverless",
            fulfill = {
                it.mongodbFlex(
                    orgId = "test-org-id"
                )
            }
        )
    }
    @Test fun testDedicated() {
        assertPlannableAws<Database.Settings>(
            name = "mongodb-serverless",
            fulfill = {
                it.mongodbAtlas(
                    orgId = "test-org-id"
                )
            }
        )
    }
}