package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.Database
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.TerraformEmitterAwsTestWithDomainVpc
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.bastion
import com.lightningkite.services.test.expensive
import kotlinx.serialization.serializer
import java.io.File
import kotlin.math.exp
import kotlin.test.Test

class TfTest {
    init {
        MongoDatabase
    }
    @Test
    fun testServerless() {
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

    @Test
    fun testFlex() {
        assertPlannableAws<Database.Settings>(
            name = "mongodb-flex",
            fulfill = {
                it.mongodbAtlasFlex(
                    orgId = "test-org-id"
                )
            }
        )
    }

    @Test
    fun testFullPeering() {
        expensive {
            val emitter = TerraformEmitterAwsTestWithDomainVpc(
                File("build/test/fullpeering"),
                "fullpeeringttest",
                Database.Settings.serializer()
            )
            with(emitter) {
                TerraformNeed<Database.Settings>("fullpeeringttest").mongodbAtlas(
                    orgId = "6323a65c43d66b56a2ea5aea"
                )
            }
            emitter.bastion()
            emitter.write()//.apply()
        }
    }

    @Test
    fun testDedicated() {
        assertPlannableAws<Database.Settings>(
            name = "mongodb-dedicated",
            fulfill = {
                it.mongodbAtlas(
                    orgId = "test-org-id"
                )
            }
        )
    }
}