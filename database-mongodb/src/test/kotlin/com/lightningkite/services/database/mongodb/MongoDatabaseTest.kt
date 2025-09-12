package com.lightningkite.services.database.mongodb

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.mongodb.TestDatabase.mongoClient
import com.lightningkite.services.database.test.AggregationsTest
import com.lightningkite.services.database.test.ConditionTests
import com.lightningkite.services.database.test.MetaTest
import com.lightningkite.services.database.test.ModificationTests
import com.lightningkite.services.database.test.OperationsTests
import com.lightningkite.services.database.test.SortTest
import java.io.File


object TestDatabase {
    val settings = testMongo()
    val mongoClient = MongoDatabase("default", clientSettings = settings, databaseName = "test", context = TestSettingContext())
}
fun db() = mongoClient

class MongodbAggregationsTest: AggregationsTest() {
    override val database: Database = db()
}
class MongodbConditionTests: ConditionTests() {
    override val database: Database = db()
}
class MongodbModificationTests: ModificationTests() {
    override val database: Database = db()
}
class MongodbOperationsTests: OperationsTests() {
    override val database: Database = db()
}
class MongodbSortTest: SortTest() {
    override val database: Database = db()
}
class MongodbMetaTest: MetaTest() {
    override val database: Database = db()
}

private fun ignoreme() {
    File("asdf").delete()
}