package com.lightningkite.services.database

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * FIX 32: the `delay://` URL scheme's bare-number form (e.g. "delay://100/ram", as opposed to the
 * "100-500ms" range form) built a zero-width Duration range (`100.milliseconds..100.milliseconds`).
 * [DelayedTable]'s doDelay() fed that straight into `Random.nextDouble(from, until)`, which throws
 * `IllegalArgumentException` whenever `from >= until` -- so every call through a bare-number
 * `delay://` database crashed deterministically.
 */
class DelayedTableUrlTest {

    @Test
    fun bareNumberDelayUrlPerformsADelayWithoutThrowing() = runTest {
        val database: Database = Database.Settings("delay://100/ram")("test", TestSettingContext())
        val table = database.table(DatabaseTableDefinition<HealthCheckTestModel>("bare-number-delay"))
        table.count() // must not throw IllegalArgumentException("Random range is empty")
    }
}
