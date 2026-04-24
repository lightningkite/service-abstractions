@file:OptIn(InlineProperty::class)

package com.lightningkite.services.database.test

// by Claude - Additional imports for comprehensive value class testing
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.uuid.Uuid

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
        val results2 = collection.find(
            Condition.Always,
            orderBy = listOf(SortPart(path<ValueClassContainingTest>().wrappedInt.int, true))
        ).toList()
        val results3 = collection.find(
            Condition.Always,
            orderBy = listOf(SortPart(path<ValueClassContainingTest>().wrappedInt.int, false))
        ).toList()
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

    // region by Claude - Comprehensive Value Class Tests

    // region Equality Condition Tests

    @Test
    fun test_valueclass_neq() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_valueclass_neq")
        val matching = ValueClassContainingTest(direct = ValueClass("a"))
        val notMatching = ValueClassContainingTest(direct = ValueClass("b"))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ValueClassContainingTest> { it.direct neq ValueClass("b") }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_wrappedInt_eq_direct() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_eq_direct")
        val matching = ValueClassContainingTest(wrappedInt = IntWrapper(42))
        val notMatching = ValueClassContainingTest(wrappedInt = IntWrapper(100))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ValueClassContainingTest> { it.wrappedInt eq IntWrapper(42) }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_wrappedInt_neq() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_neq")
        val matching = ValueClassContainingTest(wrappedInt = IntWrapper(42))
        val notMatching = ValueClassContainingTest(wrappedInt = IntWrapper(100))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ValueClassContainingTest> { it.wrappedInt neq IntWrapper(100) }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_wrappedUuid_persist() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_wrappedUuid_persist")
        val testUuid = Uuid.random()
        val item = ExtendedValueClassTest(wrappedUuid = UuidWrapper(testUuid))
        collection.insertOne(item)
        val retrieved = collection.get(item._id)
        assertEquals(item, retrieved)
        assertEquals(UuidWrapper(testUuid), retrieved?.wrappedUuid)
    }

    @Test
    fun test_wrappedUuid_eq() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_wrappedUuid_eq")
        val testUuid = Uuid.random()
        val matching = ExtendedValueClassTest(wrappedUuid = UuidWrapper(testUuid))
        val notMatching = ExtendedValueClassTest(wrappedUuid = UuidWrapper(Uuid.random()))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ExtendedValueClassTest> { it.wrappedUuid eq UuidWrapper(testUuid) }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_wrappedUuid_neq() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_wrappedUuid_neq")
        val testUuid = Uuid.random()
        val matching = ExtendedValueClassTest(wrappedUuid = UuidWrapper(testUuid))
        val notMatching = ExtendedValueClassTest(wrappedUuid = UuidWrapper(Uuid.random()))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ExtendedValueClassTest> { it.wrappedUuid neq notMatching.wrappedUuid }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_wrappedUuid_eq_innerValue() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_wrappedUuid_eq_innerValue")
        val testUuid = Uuid.random()
        val matching = ExtendedValueClassTest(wrappedUuid = UuidWrapper(testUuid))
        val notMatching = ExtendedValueClassTest(wrappedUuid = UuidWrapper(Uuid.random()))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ExtendedValueClassTest> { it.wrappedUuid.uuid eq testUuid }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    // endregion

    // region Comparison Condition Tests

    @Test
    fun test_wrappedInt_gt() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_gt")
        val lower = ValueClassContainingTest(wrappedInt = IntWrapper(10))
        val higher = ValueClassContainingTest(wrappedInt = IntWrapper(100))
        val manualList = listOf(lower, higher)
        collection.insertOne(lower)
        collection.insertOne(higher)
        val condition = condition<ValueClassContainingTest> { it.wrappedInt gt IntWrapper(50) }
        val results = collection.find(condition).toList()
        assertContains(results, higher)
        assertTrue(lower !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_wrappedInt_lt() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_lt")
        val lower = ValueClassContainingTest(wrappedInt = IntWrapper(10))
        val higher = ValueClassContainingTest(wrappedInt = IntWrapper(100))
        val manualList = listOf(lower, higher)
        collection.insertOne(lower)
        collection.insertOne(higher)
        val condition = condition<ValueClassContainingTest> { it.wrappedInt lt IntWrapper(50) }
        val results = collection.find(condition).toList()
        assertContains(results, lower)
        assertTrue(higher !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_wrappedInt_gt_innerValue() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_gt_innerValue")
        val lower = ValueClassContainingTest(wrappedInt = IntWrapper(10))
        val higher = ValueClassContainingTest(wrappedInt = IntWrapper(100))
        val manualList = listOf(lower, higher)
        collection.insertOne(lower)
        collection.insertOne(higher)
        val condition = condition<ValueClassContainingTest> { it.wrappedInt.int gt 50 }
        val results = collection.find(condition).toList()
        assertContains(results, higher)
        assertTrue(lower !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_wrappedInt_lt_innerValue() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_lt_innerValue")
        val lower = ValueClassContainingTest(wrappedInt = IntWrapper(10))
        val higher = ValueClassContainingTest(wrappedInt = IntWrapper(100))
        val manualList = listOf(lower, higher)
        collection.insertOne(lower)
        collection.insertOne(higher)
        val condition = condition<ValueClassContainingTest> { it.wrappedInt.int lt 50 }
        val results = collection.find(condition).toList()
        assertContains(results, lower)
        assertTrue(higher !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    // endregion

    // region Inside/NotInside Condition Tests

    @Test
    fun test_wrappedInt_inside() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_inside")
        val matching = ValueClassContainingTest(wrappedInt = IntWrapper(42))
        val notMatching = ValueClassContainingTest(wrappedInt = IntWrapper(100))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition =
            condition<ValueClassContainingTest> { it.wrappedInt inside listOf(IntWrapper(42), IntWrapper(50)) }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_wrappedInt_notInside() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_notInside")
        val matching = ValueClassContainingTest(wrappedInt = IntWrapper(42))
        val notMatching = ValueClassContainingTest(wrappedInt = IntWrapper(100))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition =
            condition<ValueClassContainingTest> { it.wrappedInt notInside listOf(IntWrapper(100), IntWrapper(200)) }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_valueclass_inside() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_valueclass_inside")
        val matching = ValueClassContainingTest(direct = ValueClass("a"))
        val notMatching = ValueClassContainingTest(direct = ValueClass("c"))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition =
            condition<ValueClassContainingTest> { it.direct inside listOf(ValueClass("a"), ValueClass("b")) }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_wrappedUuid_inside() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_wrappedUuid_inside")
        val testUuid1 = Uuid.random()
        val testUuid2 = Uuid.random()
        val matching = ExtendedValueClassTest(wrappedUuid = UuidWrapper(testUuid1))
        val notMatching = ExtendedValueClassTest(wrappedUuid = UuidWrapper(Uuid.random()))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ExtendedValueClassTest> {
            it.wrappedUuid inside listOf(
                UuidWrapper(testUuid1),
                UuidWrapper(testUuid2)
            )
        }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    // endregion

    // region String and Collection Condition Tests

    @Test
    fun test_valueclass_value_contains() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_valueclass_value_contains")
        val matching = ValueClassContainingTest(direct = ValueClass("hello world"))
        val notMatching = ValueClassContainingTest(direct = ValueClass("goodbye"))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ValueClassContainingTest> { it.direct.value.contains("world") }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_valueclassSet_all() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_valueclassSet_all")
        val matching = ValueClassContainingTest(set = setOf(ValueClass("aa"), ValueClass("ab")))
        val notMatching = ValueClassContainingTest(set = setOf(ValueClass("aa"), ValueClass("bc")))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ValueClassContainingTest> { it.set.all { it.value.contains("a") } }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_valueclassList_any() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_valueclassList_any")
        val matching = ExtendedValueClassTest(list = listOf(ValueClass("match"), ValueClass("other")))
        val notMatching = ExtendedValueClassTest(list = listOf(ValueClass("no"), ValueClass("other")))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ExtendedValueClassTest> { it.list.any { it.value eq "match" } }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_valueclassList_all() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_valueclassList_all")
        val matching = ExtendedValueClassTest(list = listOf(ValueClass("aa"), ValueClass("ab")))
        val notMatching = ExtendedValueClassTest(list = listOf(ValueClass("aa"), ValueClass("bc")))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ExtendedValueClassTest> { it.list.all { it.value.contains("a") } }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Suppress("DEPRECATION")
    @Test
    fun test_valueclassSet_sizesEquals() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_valueclassSet_sizesEquals")
        val matching = ValueClassContainingTest(set = setOf(ValueClass("a"), ValueClass("b"), ValueClass("c")))
        val notMatching = ValueClassContainingTest(set = setOf(ValueClass("a")))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ValueClassContainingTest> { it.set.sizesEquals(3) }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Suppress("DEPRECATION")
    @Test
    fun test_valueclassList_sizesEquals() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_valueclassList_sizesEquals")
        val matching = ExtendedValueClassTest(list = listOf(ValueClass("a"), ValueClass("b")))
        val notMatching = ExtendedValueClassTest(list = listOf(ValueClass("a")))
        val manualList = listOf(matching, notMatching)
        collection.insertOne(matching)
        collection.insertOne(notMatching)
        val condition = condition<ExtendedValueClassTest> { it.list.sizesEquals(2) }
        val results = collection.find(condition).toList()
        assertContains(results, matching)
        assertTrue(notMatching !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    // endregion

    // region Nullable Handling Condition Tests

    @Test
    fun test_nullableValueclass_notNull_eq() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_nullableValueclass_notNull_eq")
        val lower = ExtendedValueClassTest(directNullable = null)
        val higher = ExtendedValueClassTest(directNullable = ValueClass("match"))
        val manualList = listOf(lower, higher)
        collection.insertOne(lower)
        collection.insertOne(higher)
        val condition = condition<ExtendedValueClassTest> { it.directNullable.notNull eq ValueClass("match") }
        val results = collection.find(condition).toList()
        assertContains(results, higher)
        assertTrue(lower !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_nullableValueclass_eq_null() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_nullableValueclass_eq_null")
        val lower = ExtendedValueClassTest(directNullable = null)
        val higher = ExtendedValueClassTest(directNullable = ValueClass("value"))
        val manualList = listOf(lower, higher)
        collection.insertOne(lower)
        collection.insertOne(higher)
        val condition = condition<ExtendedValueClassTest> { it.directNullable eq null }
        val results = collection.find(condition).toList()
        assertContains(results, lower)
        assertTrue(higher !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_nullableWrappedInt_notNull_gt() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_nullableWrappedInt_notNull_gt")
        val nullItem = ExtendedValueClassTest(wrappedIntNullable = null)
        val lowItem = ExtendedValueClassTest(wrappedIntNullable = IntWrapper(10))
        val highItem = ExtendedValueClassTest(wrappedIntNullable = IntWrapper(100))
        val manualList = listOf(nullItem, lowItem, highItem)
        collection.insertMany(listOf(nullItem, lowItem, highItem))
        val condition = condition<ExtendedValueClassTest> { it.wrappedIntNullable.notNull gt IntWrapper(50) }
        val results = collection.find(condition).toList()
        assertContains(results, highItem)
        assertTrue(nullItem !in results)
        assertTrue(lowItem !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_nullableWrappedInt_notNull_lt() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_nullableWrappedInt_notNull_lt")
        val nullItem = ExtendedValueClassTest(wrappedIntNullable = null)
        val lowItem = ExtendedValueClassTest(wrappedIntNullable = IntWrapper(10))
        val highItem = ExtendedValueClassTest(wrappedIntNullable = IntWrapper(100))
        val manualList = listOf(nullItem, lowItem, highItem)
        collection.insertMany(listOf(nullItem, lowItem, highItem))
        val condition = condition<ExtendedValueClassTest> { it.wrappedIntNullable.notNull lt IntWrapper(50) }
        val results = collection.find(condition).toList()
        assertContains(results, lowItem)
        assertTrue(nullItem !in results)
        assertTrue(highItem !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_nullableWrappedUuid_notNull_eq() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_nullableWrappedUuid_notNull_eq")
        val testUuid = Uuid.random()
        val nullItem = ExtendedValueClassTest(wrappedUuidNullable = null)
        val matchingItem = ExtendedValueClassTest(wrappedUuidNullable = UuidWrapper(testUuid))
        val otherItem = ExtendedValueClassTest(wrappedUuidNullable = UuidWrapper(Uuid.random()))
        val manualList = listOf(nullItem, matchingItem, otherItem)
        collection.insertMany(listOf(nullItem, matchingItem, otherItem))
        val condition = condition<ExtendedValueClassTest> { it.wrappedUuidNullable.notNull eq UuidWrapper(testUuid) }
        val results = collection.find(condition).toList()
        assertContains(results, matchingItem)
        assertTrue(nullItem !in results)
        assertTrue(otherItem !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    @Test
    fun test_nullableWrappedUuid_eq_null() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_nullableWrappedUuid_eq_null")
        val nullItem = ExtendedValueClassTest(wrappedUuidNullable = null)
        val nonNullItem = ExtendedValueClassTest(wrappedUuidNullable = UuidWrapper(Uuid.random()))
        val manualList = listOf(nullItem, nonNullItem)
        collection.insertOne(nullItem)
        collection.insertOne(nonNullItem)
        val condition = condition<ExtendedValueClassTest> { it.wrappedUuidNullable eq null }
        val results = collection.find(condition).toList()
        assertContains(results, nullItem)
        assertTrue(nonNullItem !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
    }

    // endregion

    // region Arithmetic and Coercion Modification Tests

    @Test
    fun test_wrappedInt_multiply() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_multiply")
        val item = ValueClassContainingTest(wrappedInt = IntWrapper(10))
        collection.insertOne(item)
        val modification = modification<ValueClassContainingTest> { it.wrappedInt.int *= 3 }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(IntWrapper(30), result.wrappedInt)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_wrappedInt_decrement() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_decrement")
        val item = ValueClassContainingTest(wrappedInt = IntWrapper(100))
        collection.insertOne(item)
        val modification = modification<ValueClassContainingTest> { it.wrappedInt.int += -30 }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(IntWrapper(70), result.wrappedInt)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_wrappedInt_coerceAtMost() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_coerceAtMost")
        val item = ValueClassContainingTest(wrappedInt = IntWrapper(100))
        collection.insertOne(item)
        val modification = modification<ValueClassContainingTest> { it.wrappedInt.int coerceAtMost 50 }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(IntWrapper(50), result.wrappedInt)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_wrappedInt_coerceAtMost_miss() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_coerceAtMost_miss")
        val item = ValueClassContainingTest(wrappedInt = IntWrapper(30))
        collection.insertOne(item)
        val modification = modification<ValueClassContainingTest> { it.wrappedInt.int coerceAtMost 50 }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(IntWrapper(30), result.wrappedInt)  // Unchanged, already below threshold
        assertEquals(modification(item), result)
    }

    @Test
    fun test_wrappedInt_coerceAtLeast() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_coerceAtLeast")
        val item = ValueClassContainingTest(wrappedInt = IntWrapper(10))
        collection.insertOne(item)
        val modification = modification<ValueClassContainingTest> { it.wrappedInt.int coerceAtLeast 50 }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(IntWrapper(50), result.wrappedInt)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_wrappedInt_coerceAtLeast_miss() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_wrappedInt_coerceAtLeast_miss")
        val item = ValueClassContainingTest(wrappedInt = IntWrapper(100))
        collection.insertOne(item)
        val modification = modification<ValueClassContainingTest> { it.wrappedInt.int coerceAtLeast 50 }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(IntWrapper(100), result.wrappedInt)  // Unchanged, already above threshold
        assertEquals(modification(item), result)
    }

    // endregion

    // region Set Collection Modification Tests

    @Test
    fun test_valueclassSet_addAll() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_valueclassSet_addAll")
        val item = ValueClassContainingTest(set = setOf(ValueClass("a")))
        collection.insertOne(item)
        val modification =
            modification<ValueClassContainingTest> { it.set.addAll(setOf(ValueClass("b"), ValueClass("c"))) }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(setOf(ValueClass("a"), ValueClass("b"), ValueClass("c")), result.set)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_valueclassSet_removeAll_byCondition() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_valueclassSet_removeAll_byCondition")
        val item = ValueClassContainingTest(set = setOf(ValueClass("aa"), ValueClass("ab"), ValueClass("bc")))
        collection.insertOne(item)
        val modification = modification<ValueClassContainingTest> { it.set.removeAll { it.value.contains("a") } }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(setOf(ValueClass("bc")), result.set)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_valueclassSet_removeAll_byValues() = runTest {
        val collection = database.table<ValueClassContainingTest>("test_valueclassSet_removeAll_byValues")
        val item = ValueClassContainingTest(set = setOf(ValueClass("a"), ValueClass("b"), ValueClass("c")))
        collection.insertOne(item)
        val modification =
            modification<ValueClassContainingTest> { it.set.removeAll(setOf(ValueClass("a"), ValueClass("c"))) }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(setOf(ValueClass("b")), result.set)
        assertEquals(modification(item), result)
    }

    // endregion

    // region List Collection Modification Tests

    @Test
    fun test_valueclassList_addAll() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_valueclassList_addAll")
        val item = ExtendedValueClassTest(list = listOf(ValueClass("a")))
        collection.insertOne(item)
        val modification =
            modification<ExtendedValueClassTest> { it.list.addAll(listOf(ValueClass("b"), ValueClass("c"))) }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(listOf(ValueClass("a"), ValueClass("b"), ValueClass("c")), result.list)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_valueclassList_removeAll_byCondition() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_valueclassList_removeAll_byCondition")
        val item = ExtendedValueClassTest(list = listOf(ValueClass("aa"), ValueClass("ab"), ValueClass("bc")))
        collection.insertOne(item)
        val modification = modification<ExtendedValueClassTest> { it.list.removeAll { it.value.contains("a") } }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(listOf(ValueClass("bc")), result.list)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_valueclassList_removeAll_byValues() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_valueclassList_removeAll_byValues")
        val item = ExtendedValueClassTest(list = listOf(ValueClass("a"), ValueClass("b"), ValueClass("c")))
        collection.insertOne(item)
        val modification =
            modification<ExtendedValueClassTest> { it.list.removeAll(listOf(ValueClass("a"), ValueClass("c"))) }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(listOf(ValueClass("b")), result.list)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_valueclassList_dropFirst() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_valueclassList_dropFirst")
        val item = ExtendedValueClassTest(list = listOf(ValueClass("a"), ValueClass("b"), ValueClass("c")))
        collection.insertOne(item)
        val modification = modification<ExtendedValueClassTest> { it.list.dropFirst() }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(listOf(ValueClass("b"), ValueClass("c")), result.list)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_valueclassList_dropLast() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_valueclassList_dropLast")
        val item = ExtendedValueClassTest(list = listOf(ValueClass("a"), ValueClass("b"), ValueClass("c")))
        collection.insertOne(item)
        val modification = modification<ExtendedValueClassTest> { it.list.dropLast() }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(listOf(ValueClass("a"), ValueClass("b")), result.list)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_intWrapperList_addAll() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_intWrapperList_addAll")
        val item = ExtendedValueClassTest(listInt = listOf(IntWrapper(1)))
        collection.insertOne(item)
        val modification =
            modification<ExtendedValueClassTest> { it.listInt.addAll(listOf(IntWrapper(2), IntWrapper(3))) }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(listOf(IntWrapper(1), IntWrapper(2), IntWrapper(3)), result.listInt)
        assertEquals(modification(item), result)
    }

    // endregion

    // region Nullable Value Class Modification Tests

    @Test
    fun test_nullableValueclass_assign_null() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_nullableValueclass_assign_null")
        val item = ExtendedValueClassTest(directNullable = ValueClass("initial"))
        collection.insertOne(item)
        val modification = modification<ExtendedValueClassTest> { it.directNullable assign null }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(null, result.directNullable)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_nullableValueclass_assign_value() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_nullableValueclass_assign_value")
        val item = ExtendedValueClassTest(directNullable = null)
        collection.insertOne(item)
        val modification = modification<ExtendedValueClassTest> { it.directNullable assign ValueClass("assigned") }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(ValueClass("assigned"), result.directNullable)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_nullableWrappedInt_assign_null() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_nullableWrappedInt_assign_null")
        val item = ExtendedValueClassTest(wrappedIntNullable = IntWrapper(42))
        collection.insertOne(item)
        val modification = modification<ExtendedValueClassTest> { it.wrappedIntNullable assign null }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(null, result.wrappedIntNullable)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_nullableWrappedInt_assign_value() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_nullableWrappedInt_assign_value")
        val item = ExtendedValueClassTest(wrappedIntNullable = null)
        collection.insertOne(item)
        val modification = modification<ExtendedValueClassTest> { it.wrappedIntNullable assign IntWrapper(100) }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(IntWrapper(100), result.wrappedIntNullable)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_nullableWrappedUuid_assign_null() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_nullableWrappedUuid_assign_null")
        val item = ExtendedValueClassTest(wrappedUuidNullable = UuidWrapper(Uuid.random()))
        collection.insertOne(item)
        val modification = modification<ExtendedValueClassTest> { it.wrappedUuidNullable assign null }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(null, result.wrappedUuidNullable)
        assertEquals(modification(item), result)
    }

    @Test
    fun test_nullableWrappedUuid_assign_value() = runTest {
        val collection = database.table<ExtendedValueClassTest>("test_nullableWrappedUuid_assign_value")
        val testUuid = Uuid.random()
        val item = ExtendedValueClassTest(wrappedUuidNullable = null)
        collection.insertOne(item)
        val modification = modification<ExtendedValueClassTest> { it.wrappedUuidNullable assign UuidWrapper(testUuid) }
        collection.updateOneById(item._id, modification)
        val result = collection.get(item._id)!!
        assertEquals(UuidWrapper(testUuid), result.wrappedUuidNullable)
        assertEquals(modification(item), result)
    }

    // endregion

    // region SerializableProperty get/setCopy Tests

    @Test
    fun test_serializableProperty_get_valueclass() {
        val instance = ValueClassContainingTest(direct = ValueClass("test"))
        val value = ValueClassContainingTest_direct.get(instance)
        assertEquals(ValueClass("test"), value)
    }

    @Test
    fun test_serializableProperty_setCopy_valueclass() {
        val instance = ValueClassContainingTest(direct = ValueClass("old"))
        val newInstance = ValueClassContainingTest_direct.setCopy(instance, ValueClass("new"))
        assertEquals(ValueClass("new"), newInstance.direct)
        assertEquals(ValueClass("old"), instance.direct)  // Original unchanged
    }

    @Test
    fun test_serializableProperty_get_wrappedInt() {
        val instance = ValueClassContainingTest(wrappedInt = IntWrapper(42))
        val value = ValueClassContainingTest_wrappedInt.get(instance)
        assertEquals(IntWrapper(42), value)
    }

    @Test
    fun test_serializableProperty_setCopy_wrappedInt() {
        val instance = ValueClassContainingTest(wrappedInt = IntWrapper(10))
        val newInstance = ValueClassContainingTest_wrappedInt.setCopy(instance, IntWrapper(99))
        assertEquals(IntWrapper(99), newInstance.wrappedInt)
        assertEquals(IntWrapper(10), instance.wrappedInt)  // Original unchanged
    }

    @Test
    fun test_serializableProperty_get_wrappedUuid() {
        val testUuid = Uuid.random()
        val instance = ExtendedValueClassTest(wrappedUuid = UuidWrapper(testUuid))
        val value = ExtendedValueClassTest_wrappedUuid.get(instance)
        assertEquals(UuidWrapper(testUuid), value)
    }

    @Test
    fun test_serializableProperty_setCopy_wrappedUuid() {
        val originalUuid = Uuid.random()
        val newUuid = Uuid.random()
        val instance = ExtendedValueClassTest(wrappedUuid = UuidWrapper(originalUuid))
        val newInstance = ExtendedValueClassTest_wrappedUuid.setCopy(instance, UuidWrapper(newUuid))
        assertEquals(UuidWrapper(newUuid), newInstance.wrappedUuid)
        assertEquals(UuidWrapper(originalUuid), instance.wrappedUuid)  // Original unchanged
    }

    @Test
    fun test_serializableProperty_get_set() {
        val instance = ValueClassContainingTest(set = setOf(ValueClass("a"), ValueClass("b")))
        val value = ValueClassContainingTest_set.get(instance)
        assertEquals(setOf(ValueClass("a"), ValueClass("b")), value)
    }

    @Test
    fun test_serializableProperty_setCopy_set() {
        val instance = ValueClassContainingTest(set = setOf(ValueClass("old")))
        val newSet = setOf(ValueClass("new1"), ValueClass("new2"))
        val newInstance = ValueClassContainingTest_set.setCopy(instance, newSet)
        assertEquals(newSet, newInstance.set)
        assertEquals(setOf(ValueClass("old")), instance.set)  // Original unchanged
    }

    // endregion

    // region by Claude - Inline SerializableProperty get/setCopy Tests (raw inner value accessors)

    @Test
    fun test_serializableProperty_inline_get_ValueClass_value() {
        val instance = ValueClass("test string")
        val value = ValueClass_value.get(instance)
        assertEquals("test string", value)
    }

    @Test
    fun test_serializableProperty_inline_setCopy_ValueClass_value() {
        val instance = ValueClass("old")
        val newInstance = ValueClass_value.setCopy(instance, "new")
        assertEquals(ValueClass("new"), newInstance)
        assertEquals(ValueClass("old"), instance)  // Original unchanged (value class is immutable anyway)
    }

    @Test
    fun test_serializableProperty_inline_get_IntWrapper_int() {
        val instance = IntWrapper(42)
        val value = IntWrapper_int.get(instance)
        assertEquals(42, value)
    }

    @Test
    fun test_serializableProperty_inline_setCopy_IntWrapper_int() {
        val instance = IntWrapper(10)
        val newInstance = IntWrapper_int.setCopy(instance, 99)
        assertEquals(IntWrapper(99), newInstance)
        assertEquals(IntWrapper(10), instance)  // Original unchanged
    }

    @Test
    fun test_serializableProperty_inline_get_UuidWrapper_uuid() {
        val testUuid = Uuid.random()
        val instance = UuidWrapper(testUuid)
        val value = UuidWrapper_uuid.get(instance)
        assertEquals(testUuid, value)
    }

    @Test
    fun test_serializableProperty_inline_setCopy_UuidWrapper_uuid() {
        val originalUuid = Uuid.random()
        val newUuid = Uuid.random()
        val instance = UuidWrapper(originalUuid)
        val newInstance = UuidWrapper_uuid.setCopy(instance, newUuid)
        assertEquals(UuidWrapper(newUuid), newInstance)
        assertEquals(UuidWrapper(originalUuid), instance)  // Original unchanged
    }

    @Test
    fun test_serializableProperty_inline_name() {
        // Verify the property names are correct
        assertEquals("value", ValueClass_value.name)
        assertEquals("int", IntWrapper_int.name)
        assertEquals("uuid", UuidWrapper_uuid.name)
    }

    @Test
    fun test_serializableProperty_inline_isInline() {
        // Verify the inline flag is set correctly for inline properties
        assertTrue(ValueClass_value.inline)
        assertTrue(IntWrapper_int.inline)
        assertTrue(UuidWrapper_uuid.inline)
    }

    // endregion

    // endregion
}