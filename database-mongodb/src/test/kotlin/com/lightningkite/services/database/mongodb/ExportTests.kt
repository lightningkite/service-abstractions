package com.lightningkite.services.database.mongodb

import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.database.DatabaseTableDefinition
import com.lightningkite.services.database.test.LargeTestModel
import com.lightningkite.services.database.test.SimpleLargeTestModel
import com.lightningkite.services.database.writeToJsonFiles
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test

class ExportTests {
    val database get() = TestDatabase.mongoClient

    @Test
    fun exportTest() = runTest {
        database.prepare(DatabaseTableDefinition<LargeTestModel>()).insert(
            List(10) { LargeTestModel() }
        )
        database.prepare(DatabaseTableDefinition<SimpleLargeTestModel>()).insert(
            List(10) { SimpleLargeTestModel() }
        )

        database.export().writeToJsonFiles(
            folder = KFile("src/test/kotlin/com/lightningkite/services/database/mongodb/exportTest"),
            json = Json.Default
        )
    }
}