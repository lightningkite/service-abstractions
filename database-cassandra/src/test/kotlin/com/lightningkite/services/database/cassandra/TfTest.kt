package com.lightningkite.services.database.cassandra

import com.lightningkite.services.database.Database
import com.lightningkite.services.test.assertPlannableAws
import kotlin.test.Test

class TfTest {
    init {
        CassandraDatabase
    }

    @Test
    fun testKeyspaces() {
        assertPlannableAws<Database.Settings>("keyspaces") {
            it.awsKeyspaces()
        }
    }

    @Test
    fun testKeyspacesWithPitr() {
        assertPlannableAws<Database.Settings>("keyspaces-pitr") {
            it.awsKeyspaces(pointInTimeRecovery = true)
        }
    }

    @Test
    fun testKeyspacesProvisioned() {
        assertPlannableAws<Database.Settings>("keyspaces-provisioned") {
            it.awsKeyspacesProvisioned(
                readCapacity = 10,
                writeCapacity = 10,
                autoScaling = true
            )
        }
    }
}
