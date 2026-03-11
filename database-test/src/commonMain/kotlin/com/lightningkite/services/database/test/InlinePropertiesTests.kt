package com.lightningkite.services.database.test

import com.lightningkite.services.database.Aggregate
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.SortPart
import com.lightningkite.services.database.aggregate
import com.lightningkite.services.database.aggregateOf
import com.lightningkite.services.database.all
import com.lightningkite.services.database.any
import com.lightningkite.services.database.collection
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.eq
import com.lightningkite.services.database.get
import com.lightningkite.services.database.gte
import com.lightningkite.services.database.insertMany
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.database.lte
import com.lightningkite.services.database.modification
import com.lightningkite.services.database.path
import com.lightningkite.services.database.table
import com.lightningkite.services.database.updateOneById
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.toSet
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class InlinePropertiesTests {
    abstract val database: Database

    // condition tests

    @Test
    fun test_valueclassPersist() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_valueclassPersist")
        val matching = ValueClassContainingTest(
            direct = ValueClass("1")
        )
        val notMatching = ValueClassContainingTest(
            direct = ValueClass("2")
        )
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        assertEquals(matching, collection.get(matching._id))
        assertEquals(notMatching, collection.get(notMatching._id))
        assertEquals(setOf(matching), collection.find(condition { it.direct.eq(matching.direct) }).toSet())
        assertEquals(setOf(matching), collection.find(condition { it.direct.value.eq("1") }).toSet())
    }

    @Test
    fun testInlineComparisons() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_valueclassPersist")
        val numMatching = ValueClassContainingTest(
            wrappedInt = IntWrapper(100)
        )
        collection.insertOne(numMatching)
        assertEquals(numMatching, collection.get(numMatching._id))
        assertEquals(setOf(numMatching), collection.find(condition { it.wrappedInt.lte(IntWrapper(1000)) }).toSet())
        assertEquals(setOf(numMatching), collection.find(condition { it.wrappedInt.int.lte(1000) }).toSet())
        assertEquals(setOf(numMatching), collection.find(condition { it.wrappedInt.gte(IntWrapper(0)) }).toSet())
        assertEquals(setOf(numMatching), collection.find(condition { it.wrappedInt.int.gte(0) }).toSet())
    }

    @Test
    fun test_valueclassSet() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_valueclassSet")
        val matching = ValueClassContainingTest(
            set = setOf(ValueClass("1"))
        )
        val notMatching = ValueClassContainingTest(
            set = setOf(ValueClass("2"))
        )
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        assertEquals(matching, collection.get(matching._id))
        assertEquals(notMatching, collection.get(notMatching._id))
        assertEquals(setOf(matching), collection.find(condition { it.set.eq(matching.set) }).toSet())
        assertEquals(setOf(matching), collection.find(condition { it.set.any { it.value.eq("1") } }).toSet())
    }

    // modification tets
    @Test
    fun test_inlinePathModifications() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_inlinePathModifications")

        val items = List(10) { ValueClassContainingTest(wrappedInt = IntWrapper(it), direct = ValueClass("Item $it")) }

        collection.insertMany(items)

        val id = items.first()._id
        collection.updateOneById(
            id,
            modification {
                it.wrappedInt.int assign 42
                it.direct.value assign "hello world"
            }
        )
        assertEquals(IntWrapper(42), collection.get(id)!!.wrappedInt)
        assertEquals(ValueClass("hello world"), collection.get(id)!!.direct)

        val before = collection.all().map { it.wrappedInt.int }.toList()
        collection.updateMany(
            Condition.Always,
            modification { it.wrappedInt.int += 1 }
        )
        assertEquals(before.map { IntWrapper(it + 1) }, collection.all().map { it.wrappedInt }.toList())
    }

    // sorting

    @Test
    fun testSortInlineInt() = runTest {
        val collection = database.table<ValueClassContainingTest>("SortTest_testSortInlineInt")
        val items = listOf(
            ValueClassContainingTest(wrappedInt = IntWrapper(4)),
            ValueClassContainingTest(wrappedInt = IntWrapper(5)),
            ValueClassContainingTest(wrappedInt = IntWrapper(1)),
            ValueClassContainingTest(wrappedInt = IntWrapper(2)),
            ValueClassContainingTest(wrappedInt = IntWrapper(6)),
            ValueClassContainingTest(wrappedInt = IntWrapper(3)),
        )
        val sortedPosts = items.sortedBy { it.wrappedInt.int }
        val reversePosts = items.sortedByDescending { it.wrappedInt.int }
        collection.insertMany(items)
        // Note: results without ordering are not guaranteed to match insertion order
        val results2 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<ValueClassContainingTest>().wrappedInt.int, true))).toList()
        val results3 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<ValueClassContainingTest>().wrappedInt.int, false))).toList()
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })
    }

    // aggregations


    @Test
    fun testInlineAggregates() = runTest {
        val c = database.table<ValueClassContainingTest>("inlineAggregatesTest")

        val ints = List(10) { it }

        c.insertMany(
            ints.map { ValueClassContainingTest(wrappedInt = IntWrapper(it)) }
        )

        for (type in Aggregate.entries) {
            val ram = ints.aggregate(type)
            val control = c.all().toList().asSequence().aggregateOf(type) { it.wrappedInt.int.toDouble() }
            val test = c.aggregate(type, Condition.Always, path<ValueClassContainingTest>().wrappedInt.int)
            assertEquals(ram, control)
            assertEquals(control, test)
        }
    }
}