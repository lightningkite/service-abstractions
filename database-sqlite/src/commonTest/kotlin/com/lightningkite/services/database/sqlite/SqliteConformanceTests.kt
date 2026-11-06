package com.lightningkite.services.database.sqlite

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.test.*
import kotlin.test.Test
import kotlin.uuid.Uuid

/** A database in a file of its own, so no test sees another's rows. */
internal fun freshDatabase(): SqliteDatabase =
    SqliteDatabase("test", testDatabaseLocation("${Uuid.random()}.db"), TestSettingContext())

/** Where a test database file can go on this platform. */
internal expect fun testDatabaseLocation(fileName: String): String

class SqliteAggregationsTest : AggregationsTest() { override val database: Database = freshDatabase() }
class SqliteConcurrencyTests : ConcurrencyTests() { override val database: Database = freshDatabase() }
class SqliteConditionTests : ConditionTests() { override val database: Database = freshDatabase() }
class SqliteIndexTests : IndexTests() { override val database: Database = freshDatabase() }
class SqliteInlinePropertiesTests : InlinePropertiesTests() { override val database: Database = freshDatabase() }
class SqliteMetaTest : MetaTest() { override val database: Database = freshDatabase() }
class SqliteModificationTests : ModificationTests() { override val database: Database = freshDatabase() }
class SqliteOperationsTests : OperationsTests() { override val database: Database = freshDatabase() }
class SqlitePaginationTests : PaginationTests() { override val database: Database = freshDatabase() }
class SqliteReturnContractTests : ReturnContractTests() { override val database: Database = freshDatabase() }
class SqliteScaleAndBoundaryTests : ScaleAndBoundaryTests() { override val database: Database = freshDatabase() }
class SqliteSingleRowOperationTests : SingleRowOperationTests() { override val database: Database = freshDatabase() }
class SqliteSortTest : SortTest() { override val database: Database = freshDatabase() }
class SqliteSealedLensTests : SealedLensTests() { override val database: Database = freshDatabase() }
class SqliteSealedPathTests : SealedPathTests() { override val database: Database = freshDatabase() }

class SqliteConformanceCoverageTest {
    @Test
    fun everySuiteIsCovered() = assertConformanceSuitesCovered(
        "SQLite",
        listOf(
            "AggregationsTest", "ConcurrencyTests", "ConditionTests", "IndexTests", "InlinePropertiesTests", "MetaTest",
            "ModificationTests", "OperationsTests", "PaginationTests", "ReturnContractTests", "ScaleAndBoundaryTests",
            "SingleRowOperationTests", "SortTest",
        ),
    )
}
