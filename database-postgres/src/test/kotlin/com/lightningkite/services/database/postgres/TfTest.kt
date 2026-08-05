package com.lightningkite.services.database.postgres

import com.lightningkite.services.Untested
import com.lightningkite.services.database.Database
import com.lightningkite.services.test.assertPlannableAws
import kotlin.test.Test

@OptIn(Untested::class)
class TfTest {
    init {
        // Bare reference forces PostgresDatabase's companion object to init, registering the
        // postgresql:// URL schemes needed by the Database.Settings parser used below.
        @Suppress("UNUSED_EXPRESSION")
        PostgresDatabase
    }

    @Test
    fun testAuroraV2() {
        assertPlannableAws<Database.Settings>("awsAuroraServerlessV2") {
            it.awsAuroraServerlessV2()
        }
    }

    @Test
    fun testAuroraV1() {
        assertPlannableAws<Database.Settings>("awsAuroraServerlessV1") {
            it.awsAuroraServerlessV1()
        }
    }
}