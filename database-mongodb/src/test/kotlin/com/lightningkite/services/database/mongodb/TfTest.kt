package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.Database
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.*
import java.io.File
import kotlin.test.Test

class TfTest {
    init {
        // Bare reference forces MongoDatabase's companion object to init, registering the
        // mongodb:// URL schemes needed by the Database.Settings parser used below.
        @Suppress("UNUSED_EXPRESSION")
        MongoDatabase
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
                    orgId = "6323a65c43d66b56a2ea5aea",
                    analyticNodes = null,
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
                    orgId = "test-org-id",
                    analyticNodes = null,
                )
            }
        )
    }
}