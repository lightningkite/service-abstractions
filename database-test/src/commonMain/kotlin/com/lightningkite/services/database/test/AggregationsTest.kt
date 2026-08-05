package com.lightningkite.services.database.test

import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

abstract class AggregationsTest() {

    abstract val database: Database

    @Test
    fun test() = runTest {

        val c = database.prepare(DatabaseTableDefinition<LargeTestModel>("aggregationstest"))
        c.insertMany(
            listOf(
                LargeTestModel(int = 32, byte = 0, embedded = ClassUsedForEmbedding(value2 = 32)),
                LargeTestModel(int = 42, byte = 0, embedded = ClassUsedForEmbedding(value2 = 42)),
                LargeTestModel(int = 52, byte = 0, embedded = ClassUsedForEmbedding(value2 = 52)),
                LargeTestModel(int = 34, byte = 1, embedded = ClassUsedForEmbedding(value2 = 34)),
                LargeTestModel(int = 45, byte = 1, embedded = ClassUsedForEmbedding(value2 = 45)),
                LargeTestModel(int = 56, byte = 1, embedded = ClassUsedForEmbedding(value2 = 56)),
            )
        )
        run {
            val control = c.all().toList().groupingBy { it.byte }.eachCount()
            val test: Map<Byte, Int> = c.groupCount(groupBy = path<LargeTestModel>().byte)
            assertEquals(control, test)
        }
        run {
            val control = c.all().toList().asSequence().filter { it.int > 40 }.groupingBy { it.byte }.eachCount()
            val test: Map<Byte, Int> = c.groupCount(condition { it.int gt 40 }, groupBy = path<LargeTestModel>().byte)
            assertEquals(control, test)
        }
        run {
            val control = c.all().toList().size
            val test = c.count()
            assertEquals(control, test)
        }
        listOf(
            LargeTestModel.path.int,
            LargeTestModel.path.embedded.value2
        ).forEach { property ->
            for (type in Aggregate.entries) {
                val control = c.all().toList().asSequence().map { it.int.toDouble() }.aggregate(type)
                val test: Double? = c.aggregate(type, property = property)
                if (control == null || test == null) fail()
                assertEquals(control, test, 0.0000001)
            }
            for (type in Aggregate.entries) {
                val control = c.all().toList().asSequence().groupAggregate(type) { it.byte to it.int.toDouble() }
                val test: Map<Byte, Double?> =
                    c.groupAggregate(type, property = property, groupBy = path<LargeTestModel>().byte)
                assertEquals(control.keys, test.keys)
                for (key in control.keys) {
                    assertEquals(control[key]!!, test[key]!!, 0.0000001)
                }
            }
            for (type in Aggregate.entries) {
                val control = c.all().toList().asSequence().filter { false }.aggregateOf(type) { it.int.toDouble() }
                val test: Double? = c.aggregate(type, property = property, condition = Condition.Never)
                if (control == null) assertNull(test)
                else assertEquals(control, test!!, 0.0000001)
            }
            for (type in Aggregate.entries) {
                val control =
                    c.all().toList().asSequence().filter { false }.groupAggregate(type) { it.byte to it.int.toDouble() }
                val test: Map<Byte, Double?> = c.groupAggregate(
                    type,
                    property = property,
                    groupBy = path<LargeTestModel>().byte,
                    condition = Condition.Never
                )
                assertEquals(control.keys, test.keys)
                for (key in control.keys) {
                    assertEquals(control[key]!!, test[key]!!, 0.0000001)
                }
            }
        }
    }

    // FIX 10: groupCount must not drop rows whose groupBy value is null -- they form their own group,
    // matching MongoDB's $group and Postgres's GROUP BY. The test above only groups by `byte`, a
    // non-nullable field, so it could never have caught a driver that special-cases away the null key.
    @Test
    fun test_groupCount_nullableKeyIncludesNullGroup() = runTest {
        val c = database.prepare(DatabaseTableDefinition<LargeTestModel>("aggregationstest_nullablegroup"))
        c.insertMany(
            listOf(
                LargeTestModel(stringNullable = "a"),
                LargeTestModel(stringNullable = "a"),
                LargeTestModel(stringNullable = "b"),
                LargeTestModel(stringNullable = null),
                LargeTestModel(stringNullable = null),
                LargeTestModel(stringNullable = null),
            )
        )
        val control = c.all().toList().groupingBy { it.stringNullable }.eachCount()
        val test: Map<String?, Int> = c.groupCount(groupBy = path<LargeTestModel>().stringNullable)
        assertEquals(control, test)
        assertEquals(3, test[null])
    }

    // The other half of the contract. `embeddedNullable.notNull.value2` has declared value type Int,
    // but DataClassPath.get() returns V? regardless -- DataClassPathNotNull yields null when the
    // optional it wraps is absent, and every driver has the same behaviour via a NULL column or a
    // missing document field. Returning those rows under a `null` key would put a null into a
    // Map<Int, Int>, which the map's own type says is impossible. They must be dropped instead.
    @Test
    fun test_groupCount_nonNullableKeyDropsNullGroup() = runTest {
        val c = database.prepare(DatabaseTableDefinition<LargeTestModel>("aggregationstest_nonnullablegroup"))
        c.insertMany(
            listOf(
                LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 1)),
                LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 1)),
                LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 2)),
                LargeTestModel(embeddedNullable = null),
                LargeTestModel(embeddedNullable = null),
            )
        )
        val test: Map<Int, Int> = c.groupCount(groupBy = path<LargeTestModel>().embeddedNullable.notNull.value2)
        // Asserted as whole-map equality: a surviving null key fails this, and the message shows it.
        assertEquals(mapOf(1 to 2, 2 to 1), test)
    }

    @Test
    fun test_groupAggregate_nonNullableKeyDropsNullGroup() = runTest {
        val c = database.prepare(DatabaseTableDefinition<LargeTestModel>("aggregationstest_nonnullablegroupagg"))
        c.insertMany(
            listOf(
                LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 1), int = 10),
                LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 1), int = 20),
                LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 2), int = 30),
                LargeTestModel(embeddedNullable = null, int = 40),
            )
        )
        val test: Map<Int, Double?> = c.groupAggregate(
            aggregate = Aggregate.Sum,
            groupBy = path<LargeTestModel>().embeddedNullable.notNull.value2,
            property = path<LargeTestModel>().int,
        )
        assertEquals(setOf(1, 2), test.keys)
        assertEquals(30.0, test[1]!!, 0.0000001)
        assertEquals(30.0, test[2]!!, 0.0000001)
    }
}