package com.lightningkite.services.database.postgres

import com.lightningkite.services.database.Database
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.assertTerraformApply
import com.lightningkite.services.test.expensive
import kotlin.test.Test

class TfTest {
    @Test fun testAuroraV2() {
        assertPlannableAws<Database.Settings>(vpc = true) {
            it.awsAuroraServerlessV2()
        }
    }
    
    @Test fun expensiveTestAuroraV2() {
        expensive {
            assertTerraformApply(
                name = "aws-aurora-v2",
                domain = false,
                vpc = true,
                serializer = Database.Settings.serializer(),
                fulfill = {
                    it.awsAuroraServerlessV2()
                }
            )
        }
    }
    
    @Test fun testAuroraV1() {
        assertPlannableAws<Database.Settings>(vpc = true) {
            it.awsAuroraServerlessV1()
        }
    }
    
    @Test fun expensiveTestAuroraV1() {
        expensive {
            assertTerraformApply(
                name = "aws-aurora-v1",
                domain = false,
                vpc = true,
                serializer = Database.Settings.serializer(),
                fulfill = {
                    it.awsAuroraServerlessV1()
                }
            )
        }
    }
}