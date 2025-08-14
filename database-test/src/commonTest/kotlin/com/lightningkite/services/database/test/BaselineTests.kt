package com.lightningkite.services.database.test

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.InMemoryDatabase
import kotlin.test.Test

class RamAggregationsTest: AggregationsTest() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}
class RamConditionTests: ConditionTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}
class RamModificationTests: ModificationTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}
class RamOperationsTests: OperationsTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}
class RamSortTest: SortTest() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}
class RamMetaTest: MetaTest() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}
class MetadataTest {
    @Test fun check() {
        println(LargeTestModel_uuid.serializer.descriptor.serialName)
        println(LargeTestModel_uuidNullable.serializer.descriptor.serialName)
    }
}