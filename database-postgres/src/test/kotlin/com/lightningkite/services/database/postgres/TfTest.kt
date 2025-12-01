package com.lightningkite.services.database.postgres

import com.lightningkite.services.database.Database
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.expensive
import kotlin.test.Test

class TfTest {
    init {
        PostgresDatabase
    }
    @Test fun testAuroraV2() {
        assertPlannableAws<Database.Settings>("awsAuroraServerlessV2") {
            it.awsAuroraServerlessV2()
        }
    }
    @Test fun testAuroraV1() {
        assertPlannableAws<Database.Settings>("awsAuroraServerlessV1") {
            it.awsAuroraServerlessV1()
        }
    }
}