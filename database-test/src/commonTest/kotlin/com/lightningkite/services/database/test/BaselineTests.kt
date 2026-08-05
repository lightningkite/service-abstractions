package com.lightningkite.services.database.test

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.InMemoryDatabase
import kotlin.test.Test

class RamAggregationsTest : AggregationsTest() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}

class RamConditionTests : ConditionTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}

class RamModificationTests : ModificationTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}

class RamOperationsTests : OperationsTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}

class RamSortTest : SortTest() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}

class RamMetaTest : MetaTest() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}

class RamInlinePropertiesTest : InlinePropertiesTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())

    @Test
    fun start() {
    }
}

// by Claude
class RamVectorSearchTests : VectorSearchTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}

class RamSingleRowOperationTests : SingleRowOperationTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}

class RamIndexTests : IndexTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}

class RamReturnContractTests : ReturnContractTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}

class RamPaginationTests : PaginationTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}

class RamScaleAndBoundaryTests : ScaleAndBoundaryTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}

class RamConcurrencyTests : ConcurrencyTests() {
    override val database: Database = InMemoryDatabase("test", context = TestSettingContext())
}


class RamConformanceTest {
    @Test
    fun coversEverySharedSuite() {
        assertConformanceSuitesCovered(
            driver = "InMemoryDatabase",
            covered = listOf(
                "AggregationsTest",
                "ConcurrencyTests",
                "ConditionTests",
                "IndexTests",
                "InlinePropertiesTests",
                "MetaTest",
                "ModificationTests",
                "OperationsTests",
                "PaginationTests",
                "ReturnContractTests",
                "ScaleAndBoundaryTests",
                "SingleRowOperationTests",
                "SortTest",
            ),
        )
    }
}

class MetadataTest {
    @Test
    fun check() {
        println(LargeTestModel_uuid.serializer.descriptor.serialName)
        println(LargeTestModel_uuidNullable.serializer.descriptor.serialName)
    }
}