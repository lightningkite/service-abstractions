package com.lightningkite.services.database.test

import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The edges: empty inputs, empty tables, row counts past a driver's internal batching threshold,
 * nested structures, and values at the limits of their type.
 *
 * The row count in [deleteMany_acrossChunkBoundary] is deliberately above 1000, because at least one
 * driver batches its bulk work in chunks of that size and a boundary that no test crosses is a
 * boundary nobody has checked.
 */
abstract class ScaleAndBoundaryTests {

    abstract val database: Database

    private suspend fun table(name: String) =
        database.prepare(DatabaseTableDefinition<LargeTestModel>(name))

    private suspend fun Table<LargeTestModel>.all() = find(Condition.Always).toList()

    // region empty inputs and empty tables

    @Test
    fun insertOfNothingReturnsNothing() = runTest {
        val collection = table("sbt_insertEmpty")
        assertEquals(emptyList(), collection.insert(emptyList()))
        assertEquals(0, collection.count())
    }

    @Test
    fun readsOnAnEmptyTableAreEmptyNotNull() = runTest {
        val collection = table("sbt_emptyReads")
        assertEquals(emptyList(), collection.all())
        assertEquals(0, collection.count())
        assertEquals(0, collection.count(condition { it.int gt 0 }))
        assertNull(collection.findOne(Condition.Always))
        assertEquals(emptyMap(), collection.groupCount(Condition.Always, path<LargeTestModel>().int))
    }

    @Test
    fun writesOnAnEmptyTableReportDoingNothing() = runTest {
        val collection = table("sbt_emptyWrites")
        assertNull(collection.updateOne(Condition.Always, modification { it.boolean assign true }).old)
        assertEquals(false, collection.updateOneIgnoringResult(Condition.Always, modification { it.boolean assign true }))
        assertEquals(0, collection.updateManyIgnoringResult(Condition.Always, modification { it.boolean assign true }))
        assertNull(collection.deleteOne(Condition.Always))
        assertEquals(false, collection.deleteOneIgnoringOld(Condition.Always))
        assertEquals(emptyList(), collection.deleteMany(Condition.Always))
        assertEquals(0, collection.deleteManyIgnoringOld(Condition.Always))
    }

    @Test
    fun deleteManyAlwaysEmptiesTheTable() = runTest {
        val collection = table("sbt_deleteAll")
        collection.insert((1..20).map { LargeTestModel(int = it) })
        assertEquals(20, collection.deleteMany(Condition.Always).size)
        assertEquals(0, collection.count())
        assertEquals(emptyList(), collection.all())
    }

    // endregion

    // region scale

    @Test
    fun insertAndReadBackAcrossChunkBoundary() = runTest {
        val collection = table("sbt_bulkInsert")
        val models = (1..BULK).map { LargeTestModel(int = it) }
        assertEquals(BULK, collection.insert(models).size)
        assertEquals(BULK, collection.count())
        assertEquals(models.map { it.int }.toSet(), collection.all().map { it.int }.toSet())
    }

    @Test
    fun deleteMany_acrossChunkBoundary() = runTest {
        val collection = table("sbt_bulkDelete")
        collection.insert((1..BULK).map { LargeTestModel(int = it) })

        val deleted = collection.deleteMany(Condition.Always)

        assertEquals(BULK, deleted.size, "every row must be reported, including past any internal chunk size")
        assertEquals(BULK, deleted.map { it._id }.toSet().size, "no row may be reported twice")
        assertEquals(0, collection.count())
    }

    @Test
    fun updateMany_acrossChunkBoundary() = runTest {
        val collection = table("sbt_bulkUpdate")
        collection.insert((1..BULK).map { LargeTestModel(int = it) })

        assertEquals(BULK, collection.updateManyIgnoringResult(Condition.Always, modification { it.boolean assign true }))
        assertEquals(BULK, collection.count(condition { it.boolean eq true }))
    }

    // endregion

    // region nested structures

    @Test
    fun listOfEmbeddedRoundTrips() = runTest {
        val collection = table("sbt_listEmbedded")
        val model = LargeTestModel(
            int = 1,
            listEmbedded = listOf(
                ClassUsedForEmbedding("a", 1),
                ClassUsedForEmbedding("b", 2),
                ClassUsedForEmbedding("c", 3),
            ),
        )
        collection.insert(listOf(model))
        // Order matters for a list, unlike the set below.
        assertEquals(model.listEmbedded, collection.findOne(Condition.Always)?.listEmbedded)
    }

    @Test
    fun setOfEmbeddedRoundTrips() = runTest {
        val collection = table("sbt_setEmbedded")
        val model = LargeTestModel(
            int = 1,
            setEmbedded = setOf(ClassUsedForEmbedding("a", 1), ClassUsedForEmbedding("b", 2)),
        )
        collection.insert(listOf(model))
        assertEquals(model.setEmbedded, collection.findOne(Condition.Always)?.setEmbedded)
    }

    @Test
    fun nullableEmbeddedRoundTripsBothWays() = runTest {
        val collection = table("sbt_nullableEmbedded")
        val absent = LargeTestModel(int = 1, embeddedNullable = null)
        val present = LargeTestModel(int = 2, embeddedNullable = ClassUsedForEmbedding("here", 9))
        collection.insert(listOf(absent, present))

        assertNull(assertNotNull(collection.findOne(condition { it.int eq 1 })).embeddedNullable)
        assertEquals(present.embeddedNullable, assertNotNull(collection.findOne(condition { it.int eq 2 })).embeddedNullable)
    }

    @Test
    fun emptyCollectionIsNotConfusedWithNull() = runTest {
        val collection = table("sbt_emptyVsNull")
        val empty = LargeTestModel(int = 1, list = listOf(), listNullable = listOf(), map = mapOf(), mapNullable = mapOf())
        val nulled = LargeTestModel(int = 2, list = listOf(), listNullable = null, map = mapOf(), mapNullable = null)
        collection.insert(listOf(empty, nulled))

        val readEmpty = assertNotNull(collection.findOne(condition { it.int eq 1 }))
        assertEquals(emptyList(), readEmpty.listNullable, "an empty list must not come back as null")
        assertEquals(emptyMap(), readEmpty.mapNullable, "an empty map must not come back as null")

        val readNulled = assertNotNull(collection.findOne(condition { it.int eq 2 }))
        assertNull(readNulled.listNullable, "a null list must not come back as empty")
        assertNull(readNulled.mapNullable, "a null map must not come back as empty")
    }

    @Test
    fun mapWithNonTrivialValuesRoundTrips() = runTest {
        val collection = table("sbt_map")
        val model = LargeTestModel(int = 1, map = mapOf("a" to 1, "b" to 2, "" to 3))
        collection.insert(listOf(model))
        assertEquals(model.map, collection.findOne(Condition.Always)?.map)
    }

    // endregion

    // region value boundaries

    @Test
    fun numericExtremesRoundTrip() = runTest {
        val collection = table("sbt_numericExtremes")
        val min = LargeTestModel(
            int = Int.MIN_VALUE,
            long = Long.MIN_VALUE,
            short = Short.MIN_VALUE,
            byte = Byte.MIN_VALUE,
        )
        val max = LargeTestModel(
            int = Int.MAX_VALUE,
            long = Long.MAX_VALUE,
            short = Short.MAX_VALUE,
            byte = Byte.MAX_VALUE,
        )
        collection.insert(listOf(min, max))

        assertEquals(min, collection.findOne(condition { it.byte eq Byte.MIN_VALUE }))
        assertEquals(max, collection.findOne(condition { it.byte eq Byte.MAX_VALUE }))
        assertEquals(min, collection.findOne(condition { it.short eq Short.MIN_VALUE }))
        assertEquals(max, collection.findOne(condition { it.short eq Short.MAX_VALUE }))
        assertEquals(min, collection.findOne(condition { it.int eq Int.MIN_VALUE }))
        assertEquals(max, collection.findOne(condition { it.int eq Int.MAX_VALUE }))
        assertEquals(min, collection.findOne(condition { it.long eq Long.MIN_VALUE }))
        assertEquals(max, collection.findOne(condition { it.long eq Long.MAX_VALUE }))
    }

    @Test
    fun numericExtremesCompareCorrectly() = runTest {
        val collection = table("sbt_extremeCompare")
        collection.insert(
            listOf(
                LargeTestModel(int = Int.MIN_VALUE),
                LargeTestModel(int = 0),
                LargeTestModel(int = Int.MAX_VALUE),
            )
        )
        // A driver that round-trips extremes but compares them as unsigned would fail here.
        assertEquals(
            listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE),
            collection.find(Condition.Always, sort { it.int.ascending() }).toList().map { it.int },
        )
        assertEquals(1, collection.count(condition { it.int lt 0 }))
    }

    @Test
    fun awkwardStringsRoundTrip() = runTest {
        val collection = table("sbt_strings")
        val strings = listOf(
            "",
            " leading and trailing ",
            "emoji 🙂 and accents éüñ",
            "quotes ' \" and backslash \\",
            "newline\nand\ttab",
            "x".repeat(4000),
        )
        collection.insert(strings.mapIndexed { index, s -> LargeTestModel(int = index, string = s) })

        for ((index, expected) in strings.withIndex()) {
            assertEquals(expected, collection.findOne(condition { it.int eq index })?.string, "string $index")
        }
    }

    @Test
    fun awkwardStringsAreMatchableByEquality() = runTest {
        val collection = table("sbt_stringMatch")
        val awkward = "quotes ' \" and percent %_"
        collection.insert(listOf(LargeTestModel(int = 1, string = awkward), LargeTestModel(int = 2, string = "other")))
        // Also a light check that special characters are parameterized rather than interpolated.
        assertEquals(listOf(1), collection.find(condition { it.string eq awkward }).toList().map { it.int })
    }
    @Test
    fun awkwardStringsAreContainsCheckable() = runTest {
        val collection = table("sbt_contains")
        val awkward = "quotes ' \" and percent %_"
        collection.insert(listOf(LargeTestModel(int = 1, string = awkward), LargeTestModel(int = 2, string = "other")))
        // Also a light check that special characters are parameterized rather than interpolated.
        assertEquals(listOf(1), collection.find(condition { it.string.contains("\" and percent %") }).toList().map { it.int })
    }

    @Test
    fun nullableFieldsRoundTripAsNull() = runTest {
        val collection = table("sbt_nulls")
        val model = LargeTestModel(int = 1)
        collection.insert(listOf(model))
        // Asserted non-null first: `assertNull(read?.field)` would pass vacuously on a missing row.
        val read = assertNotNull(collection.findOne(Condition.Always))
        assertNull(read.intNullable)
        assertNull(read.stringNullable)
        assertNull(read.instantNullable)
        assertNull(read.uuidNullable)
        assertEquals(model, read)
    }

    @Test
    fun floatingPointSpecialValuesRoundTrip() = runTest {
        val collection = table("sbt_floats")
        val model = LargeTestModel(int = 1, double = 0.1 + 0.2, float = 1.5f)
        collection.insert(listOf(model))
        val read = assertNotNull(collection.findOne(Condition.Always))
        assertEquals(model.double, read.double)
        assertEquals(model.float, read.float)
    }

    // endregion

    private companion object {
        /** Above the 1000-row chunk size at least one driver uses internally. */
        const val BULK = 2500
    }
}
