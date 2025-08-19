package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.Database
import com.lightningkite.services.terraform.TerraformCloudInfo
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.assertTerraformApply
import com.lightningkite.services.test.expensive
import kotlin.test.Test

class TfTest {
    @Test fun testAtlasServerless() {
        assertPlannableAws<Database.Settings>(vpc = true) {
            it.mongodbAtlasServerless(
                orgId = "test-org-id",
                continuousBackupEnabled = true
            )
        }
    }
    
    @Test fun expensiveTestAtlasServerless() {
        MongoDatabase
        expensive {
            assertTerraformApply(
                name = "mongodb-atlas-serverless",
                domain = false,
                vpc = true,
                serializer = Database.Settings.serializer(),
                fulfill = {
                    it.mongodbAtlasServerless(
                        orgId = "test-org-id",
                        continuousBackupEnabled = true
                    )
                }
            )
        }
    }
    
    @Test fun testAtlas() {
        assertPlannableAws<Database.Settings>(vpc = true) {
            it.mongodbAtlas(
                orgId = "test-org-id",
                backupEnabled = true
            )
        }
    }
    
    @Test fun expensiveTestAtlas() {
        MongoDatabase
        expensive {
            assertTerraformApply(
                name = "mongodb-atlas",
                domain = false,
                vpc = true,
                serializer = Database.Settings.serializer(),
                fulfill = {
                    it.mongodbAtlas(
                        orgId = "test-org-id",
                        backupEnabled = true
                    )
                }
            )
        }
    }
    
    @Test fun testFlex() {
        assertPlannableAws<Database.Settings>(vpc = true) {
            it.mongodbFlex(
                orgId = "test-org-id",
                backupEnabled = true
            )
        }
    }
    
    @Test fun expensiveTestFlex() {
        MongoDatabase
        expensive {
            assertTerraformApply(
                name = "mongodb-flex",
                domain = false,
                vpc = true,
                serializer = Database.Settings.serializer(),
                fulfill = {
                    it.mongodbFlex(
                        orgId = "test-org-id",
                        backupEnabled = true
                    )
                }
            )
        }
    }
}