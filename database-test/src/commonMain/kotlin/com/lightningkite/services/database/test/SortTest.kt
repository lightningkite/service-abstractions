package com.lightningkite.services.database.test

import kotlinx.coroutines.flow.*
import com.lightningkite.services.database.*
import com.lightningkite.services.data.*
import com.lightningkite.*
import com.lightningkite.Length.Companion.kilometers
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.*

abstract class SortTest {
    abstract val database: Database

    @Test
    fun testSortInt()= runTest {
        val collection = database.table<LargeTestModel>("SortTest_testSortInt")
        val items = listOf(
            LargeTestModel(int = 4),
            LargeTestModel(int = 5),
            LargeTestModel(int = 1),
            LargeTestModel(int = 2),
            LargeTestModel(int = 6),
            LargeTestModel(int = 3),
        )
        val sortedPosts = items.sortedBy { it.int }
        val reversePosts = items.sortedByDescending { it.int }
        collection.insertMany(items)
        val results1 = collection.find(Condition.Always).toList()
        val results2 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().int, true))).toList()
        val results3 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().int, false))).toList()
        assertEquals(items.map { it._id }, results1.map { it._id })
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })
    }

    @Test
    fun testSortIntEmbedded()= runTest {
        val collection = database.table<LargeTestModel>("SortTest_testSortIntEmbedded")
        val items = listOf(
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 4)),
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 5)),
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 1)),
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 2)),
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 6)),
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 3)),
        )
        val sortedPosts = items.sortedBy { it.embedded.value2 }
        val reversePosts = items.sortedByDescending { it.embedded.value2 }
        collection.insertMany(items)
        val results1 = collection.find(Condition.Always).toList()
        val results2 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().embedded.value2, true))).toList()
        val results3 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().embedded.value2, false))).toList()
        assertEquals(items.map { it._id }, results1.map { it._id })
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })
    }

    @Test
    fun testSortIntEmbeddedNullable()= runTest {
        val collection = database.table<LargeTestModel>("SortTest_testSortIntEmbeddedNullable")
        val items = listOf(
            LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 4)),
            LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 5)),
            LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 1)),
            LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 2)),
            LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 6)),
            LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 3)),
        )
        val sortedPosts = items.sortedBy { it.embeddedNullable?.value2 }
        val reversePosts = items.sortedByDescending { it.embeddedNullable?.value2 }
        collection.insertMany(items)
        val results1 = collection.find(Condition.Always).toList()
        val results2 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().embeddedNullable.notNull.value2, true))).toList()
        val results3 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().embeddedNullable.notNull.value2, false))).toList()
        assertEquals(items.map { it._id }, results1.map { it._id })
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })
    }

    @Test
    fun testSortTime()= runTest {
        val collection = database.table<LargeTestModel>("SortTest_testSortTime")
        val items = listOf(
            LargeTestModel(instant = Clock.System.now().minus(4.minutes)),
            LargeTestModel(instant = Clock.System.now().minus(5.minutes)),
            LargeTestModel(instant = Clock.System.now()),
            LargeTestModel(instant = Clock.System.now().minus(2.minutes)),
            LargeTestModel(instant = Clock.System.now().minus(6.minutes)),
            LargeTestModel(instant = Clock.System.now().minus(3.minutes)),
        )
        val sortedPosts = items.sortedBy { it.instant }
        val reversePosts = items.sortedByDescending { it.instant }
        collection.insertMany(items)
        val results1 = collection.find(Condition.Always).toList()
        val results2 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().instant, true))).toList()
        val results3 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().instant, false))).toList()
        assertEquals(items.map { it._id }, results1.map { it._id })
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })
    }

    @Test
    fun testSortCase()= runTest {
        val collection = database.table<LargeTestModel>("SortTest_testSortCase")
        val items = listOf(
            LargeTestModel(string = "aa"),
            LargeTestModel(string = "Ab"),
            LargeTestModel(string = "ac"),
            LargeTestModel(string = "Ad"),
            LargeTestModel(string = "ae"),
            LargeTestModel(string = "Af"),
        )
        val sortedPosts = items.sortedBy { it.string }
        val reversePosts = items.sortedByDescending { it.string }
        collection.insertMany(items)
        val results2 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().string, true))).toList()
        val results3 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().string, false))).toList()
        assertEquals(sortedPosts.map { it.string }, results2.map { it.string })
        assertEquals(reversePosts.map { it.string }, results3.map { it.string })
    }

    @Test
    fun testSortCaseInsensitive()= runTest {
        val collection = database.table<LargeTestModel>("SortTest_testSortCaseInsensitive")
        val items = listOf(
            LargeTestModel(string = "aa"),
            LargeTestModel(string = "Ab"),
            LargeTestModel(string = "ac"),
            LargeTestModel(string = "Ad"),
            LargeTestModel(string = "ae"),
            LargeTestModel(string = "Af"),
        )
        val sortedPosts = items.sortedBy { it.string.lowercase() }
        val reversePosts = items.sortedByDescending { it.string.lowercase() }
        collection.insertMany(items)
        val results2 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().string, true, true))).toList()
        val results3 = collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().string, false, true))).toList()
        assertEquals(sortedPosts.map { it.string }, results2.map { it.string })
        assertEquals(reversePosts.map { it.string }, results3.map { it.string })
    }


    @Test
    fun testSortCaseInsensitiveCrash()= runTest {
        val collection = database.table<LargeTestModel>("testSortCaseInsensitiveCrash")
        val items = listOf(
            LargeTestModel(string = "aa"),
            LargeTestModel(string = "Ab"),
            LargeTestModel(string = "ac"),
            LargeTestModel(string = "Ad"),
            LargeTestModel(string = "ae"),
            LargeTestModel(string = "Af"),
        )
        collection.find(Condition.Always, orderBy = listOf(SortPart(path<LargeTestModel>().long, true, true))).toList()
    }

}