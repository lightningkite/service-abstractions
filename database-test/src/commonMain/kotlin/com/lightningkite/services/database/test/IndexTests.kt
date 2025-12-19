package com.lightningkite.services.database.test

import com.lightningkite.services.database.Database
import com.lightningkite.services.database.UniqueViolationException
import com.lightningkite.services.database.insertMany
import com.lightningkite.services.database.table
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

abstract class IndexTests {
    abstract val database: Database

    @Test
    fun testNotUniqueIndexes() = runTest {
        val table = database.table<NotUniqueIndexTestModel>()

        table.insertMany(
            listOf(
                NotUniqueIndexTestModel(),
                NotUniqueIndexTestModel()
            )
        )
    }

    private inline fun assertUniqueViolation(action: () -> Unit) {
        var error: UniqueViolationException? = null
        try {
            action()
        } catch (e: UniqueViolationException) {
            error = e
        }
        if (error == null) throw IllegalStateException("No unique violation")
    }

    @Test
    fun testUniqueIndexes() = runTest {
        val table = database.table<UniqueIndexTestModel>()

        // all different

        table.insertMany(
            listOf(
                UniqueIndexTestModel(
                    value = "first",
                    set1 = "test",
                    set2 = "sets"
                ),
                UniqueIndexTestModel(
                    value = "second",
                    set1 = "test",
                    set2 = "sets2"
                ),
            )
        )

        println("unique value violation")

        assertUniqueViolation {
            table.insertMany(
                listOf(
                    UniqueIndexTestModel(
                        value = "unique",
                    ),
                    UniqueIndexTestModel(
                        value = "unique",
                    )
                )
            )
        }

        println("unique set violation")

        assertUniqueViolation {
            table.insertMany(
                listOf(
                    UniqueIndexTestModel(
                        set1 = "unique1",
                        set2 = "unique2"
                    ),
                    UniqueIndexTestModel(
                        set1 = "unique1",
                        set2 = "unique2"
                    )
                )
            )
        }

        // unique null value violation

        assertUniqueViolation {
            table.insertMany(
                listOf(
                    UniqueIndexTestModel(
                        value = null,
                    ),
                    UniqueIndexTestModel(
                        value = null,
                    )
                )
            )
        }

        // unique null set violation

        assertUniqueViolation {
            table.insertMany(
                listOf(
                    UniqueIndexTestModel(
                        set1 = null,
                        set2 = null
                    ),
                    UniqueIndexTestModel(
                        set1 = null,
                        set2 = null
                    )
                )
            )
        }

        assertUniqueViolation {
            table.insertMany(
                listOf(
                    UniqueIndexTestModel(
                        set1 = "unique",
                        set2 = null
                    ),
                    UniqueIndexTestModel(
                        set1 = "unique",
                        set2 = null
                    )
                )
            )
        }
    }

    @Test
    fun testUniqueNullSparseIndexes() = runTest {
        val table = database.table<UniqueNullSparseIndexTestModel>()

        // all different

        table.insertMany(
            listOf(
                UniqueNullSparseIndexTestModel(
                    value = "first",
                    set1 = "test",
                    set2 = "sets"
                ),
                UniqueNullSparseIndexTestModel(
                    value = "second",
                    set1 = "test",
                    set2 = "sets2"
                ),
            )
        )

        println("unique value violations")

        assertUniqueViolation {
            table.insertMany(
                listOf(
                    UniqueNullSparseIndexTestModel(
                        value = "unique",
                    ),
                    UniqueNullSparseIndexTestModel(
                        value = "unique",
                    )
                )
            )
        }

        println("unique set violations")

        assertUniqueViolation {
            table.insertMany(
                listOf(
                    UniqueNullSparseIndexTestModel(
                        set1 = "unique1",
                        set2 = "unique2"
                    ),
                    UniqueNullSparseIndexTestModel(
                        set1 = "unique1",
                        set2 = "unique2"
                    )
                )
            )
        }

        // null values aren't unique

        table.insertMany(
            listOf(
                UniqueNullSparseIndexTestModel(
                    value = null,
                ),
                UniqueNullSparseIndexTestModel(
                    value = null,
                )
            )
        )

        // null in sets aren't unique

        table.insertMany(
            listOf(
                UniqueNullSparseIndexTestModel(
                    set1 = null,
                    set2 = null
                ),
                UniqueNullSparseIndexTestModel(
                    set1 = null,
                    set2 = null
                )
            )
        )

        table.insertMany(
            listOf(
                UniqueNullSparseIndexTestModel(
                    set1 = "unique",
                    set2 = null
                ),
                UniqueNullSparseIndexTestModel(
                    set1 = "unique",
                    set2 = null
                )
            )
        )
    }
}