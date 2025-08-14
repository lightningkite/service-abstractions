package com.lightningkite.services.database.jsonfile

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.KFile
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.InMemoryDatabase
import com.lightningkite.services.database.test.AggregationsTest
import com.lightningkite.services.database.test.ConditionTests
import com.lightningkite.services.database.test.MetaTest
import com.lightningkite.services.database.test.ModificationTests
import com.lightningkite.services.database.test.OperationsTests
import com.lightningkite.services.database.test.SortTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.junit.Assert.*

private fun db() = JsonFileDatabase("test", KFile("build/testrun").also {
    it.deleteRecursively()
    it.createDirectories()
}, TestSettingContext())
class JsonFileAggregationsTest: AggregationsTest() {
    override val database: Database = db()
}
class JsonFileConditionTests: ConditionTests() {
    override val database: Database = db()
}
class JsonFileModificationTests: ModificationTests() {
    override val database: Database = db()
}
class JsonFileOperationsTests: OperationsTests() {
    override val database: Database = db()
}
class JsonFileSortTest: SortTest() {
    override val database: Database = db()
}
class JsonFileMetaTest: MetaTest() {
    override val database: Database = db()
}