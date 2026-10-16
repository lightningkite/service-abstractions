package com.lightningkite.services.database.test

import com.lightningkite.services.data.IndexUniqueness
import com.lightningkite.services.database.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

abstract class IndexTests {
    abstract val database: Database

    /**
     * Whether this database can enforce [IndexUniqueness.Unique] across NULLs — treating two NULL
     * rows as a collision, so at most one may exist.
     *
     * Document databases do. SQL does not: a UNIQUE index treats every NULL as distinct, which is
     * precisely [IndexUniqueness.UniqueNullSparse]. A driver that cannot honor the stronger rule
     * must reject the model when the table is prepared rather than build a weaker index and let
     * duplicates through later, and overriding this to `false` asserts exactly that.
     */
    open val supportsUniqueAcrossNulls: Boolean = true

    @Test
    fun testNotUniqueIndexes() = runTest {
        val table = database.prepare(DatabaseTableDefinition<NotUniqueIndexTestModel>())

        table.insert(
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
        if (!supportsUniqueAcrossNulls) {
            // UniqueIndexTestModel marks nullable fields Unique. A driver that cannot enforce that
            // has to say so while preparing the table — silently creating a weaker index would let
            // duplicate NULL rows through at some later insert that should have been rejected.
            assertFailsWith<IllegalArgumentException> {
                database.prepare(DatabaseTableDefinition<UniqueIndexTestModel>())
            }
            return@runTest
        }

        val table = database.prepare(DatabaseTableDefinition<UniqueIndexTestModel>())

        // all different

        table.insert(
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
            table.insert(
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
            table.insert(
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
            table.insert(
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
            table.insert(
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
            table.insert(
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
        val table = database.prepare(DatabaseTableDefinition<UniqueNullSparseIndexTestModel>())

        // all different

        table.insert(
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

        //  unique value violations

        assertUniqueViolation {
            table.insert(
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

        // unique set violations

        assertUniqueViolation {
            table.insert(
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

        table.insert(
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

        table.insert(
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

        table.insert(
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