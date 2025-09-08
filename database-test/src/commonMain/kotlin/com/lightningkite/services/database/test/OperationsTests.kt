package com.lightningkite.services.database.test

import kotlinx.coroutines.flow.*
import com.lightningkite.services.database.*
import com.lightningkite.services.data.*
import com.lightningkite.*
import com.lightningkite.Length.Companion.kilometers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.*

abstract class OperationsTests() {

    abstract val database: Database

    @Test fun test_enumkey() = runTest {
        val collection = database.table<HasWeirdMap>("test_enumkey")
        val m = HasWeirdMap(map = mapOf(TestEnum.One to "1", TestEnum.Two to "2"))
        collection.insertOne(m)
        val result = collection.get(m._id)
        assertEquals(m, result)
    }

    @Test fun test_partials() = runTest {
        val collection = database.table<LargeTestModel>("test_partials")
        var m = LargeTestModel(int = 42)
        collection.insertOne(m)
        val result = collection.findPartial(
            fields = setOf(path<LargeTestModel>().int),
            condition = Condition.Always
        ).toList()
        assertEquals(partialOf { it.int assign m.int }, result.first())
    }

    @Test fun test_massUpdate() = runTest {
        val collection = database.table<LargeTestModel>("test_massUpdate")
        val basis = (0..100).map { LargeTestModel(int = it) }
        collection.insert(basis)
        val cond = condition<LargeTestModel> { it.int gt 50 }
        val mod = modification<LargeTestModel> { it.boolean assign true }
        val out = collection.updateMany(cond, mod)
        assertEquals(basis.map { if(cond(it)) mod(it) else it }, collection.all().toList().sortedBy { it.int })
        assertEquals(basis.filter { cond(it) }.map { EntryChange(it, mod(it)) }.sortedBy { it.old?.int }, out.changes.sortedBy { it.old?.int })
    }

    @Test fun test_replace() = runTest {
        val collection = database.table<LargeTestModel>("test_replace")
        var m = LargeTestModel()
        collection.insertOne(m)
        try {
            m = m.copy(int = 1)
            collection.replaceOneIgnoringResult(condition { it._id eq m._id }, m)
            assertEquals(m, collection.get(m._id))
        } catch(u: UnsupportedOperationException) {
            println("fine...")
        }
    }

    @Test fun test_wackyUpsert() = runTest {
        val collection = database.table<LargeTestModel>("test_wackyUpsert")
        var m = LargeTestModel(int = 2, byte = 1)
        var updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.boolean assign true }, m)
        assertEquals(null, updated.old)
        assertEquals(m, updated.new)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte assign 1 }, LargeTestModel(byte = 1))
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
    }
    @Test fun test_normalUpsert() = runTest {
        val collection = database.table<LargeTestModel>("test_normalUpsert")
        var m = LargeTestModel(int = 2, boolean = true)
        var updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.boolean assign true }, m)
        assertEquals(null, updated.old)
        assertEquals(m, updated.new)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte assign 1 }, LargeTestModel(byte = 1))
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
    }
    @Test fun test_modUpsert() = runTest {
        val collection = database.table<LargeTestModel>("test_modUpsert")
        var m = LargeTestModel(int = 2, boolean = true, byte = 1)
        var updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, m)
        assertEquals(null, updated.old)
        assertEquals(m, updated.new)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, LargeTestModel(byte = 1))
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, LargeTestModel(byte = 1))
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
    }
    @Test fun test_wackyUpsertIgnoring() = runTest {
        val collection = database.table<LargeTestModel>("test_wackyUpsertIgnoring")
        var m = LargeTestModel(int = 2)
        var updated = collection.upsertOneIgnoringResult(condition { it._id eq m._id }, modification { it.boolean assign true }, m)
        m = collection.get(m._id)!!
        assertEquals(false, updated)
        updated = collection.upsertOneIgnoringResult(condition { it._id eq m._id }, modification { it.byte assign 1 }, m)
        m = collection.get(m._id)!!
        assertEquals(true, updated)
    }
    @Test fun test_normalUpsertIgnoring() = runTest {
        val collection = database.table<LargeTestModel>("test_normalUpsertIgnoring")
        var m = LargeTestModel(int = 2, boolean = true)
        var updated = collection.upsertOneIgnoringResult(condition { it._id eq m._id }, modification { it.boolean assign true }, m)
        m = collection.get(m._id)!!
        assertEquals(false, updated)
        updated = collection.upsertOneIgnoringResult(condition { it._id eq m._id }, modification { it.byte assign 1 }, m)
        m = collection.get(m._id)!!
        assertEquals(true, updated)
    }
    @Test fun test_modUpsertIgnoring() = runTest {
        val collection = database.table<LargeTestModel>("test_modUpsert")
        var m = LargeTestModel(int = 2, boolean = true, byte = 1)
        var updated = collection.upsertOneIgnoringResult(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, m)
        m = collection.get(m._id)!!
        assertEquals(false, updated)
        updated = collection.upsertOneIgnoringResult(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, m)
        m = collection.get(m._id)!!
        assertEquals(true, updated)
    }

    @Test fun test_upsertOneById() = runTest {
        val collection = database.table<LargeTestModel>("test_upsertOneById")
        var m = LargeTestModel(int = 2, boolean = true)
        var updated = collection.upsertOneById(m._id, m)
        assertEquals(null, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOneById(m._id, m)
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
    }

//    @Test fun test_concurrency() = runTest {
//        fun collection() = database.table<LargeTestModel>("test_concurrency")
//        val operations = (1..1000).map { modification<LargeTestModel> { it.int assign Random.nextInt() } }
//        var m = LargeTestModel(int = 2, boolean = true)
//        val opCount = AtomicInteger(0)
//        val opMax = AtomicInteger(0)
//        collection().insertOne(m)
//        coroutineScope {
//            withContext(Dispatchers.IO) {
//                operations.map {
//                    async {
//                        val c = opCount.incrementAndGet()
//                        opMax.getAndUpdate { max(it, c) }
//                        collection().updateOneById(m._id, it)
//                        opCount.decrementAndGet()
//                    }
//                }.awaitAll()
//            }
//        }
//        // This assert won't usually work as ops are not necessarily run in order.
////        assertEquals(operations.fold(m) { acc, op -> op(acc) }, collection().get(m._id))
//        assertEquals(0, opCount.get())
//        println("Concurrent ops: ${opMax.get()}")
//    }

    @Test fun test_compounds() = runTest {
        fun collection() = database.table<CompoundKeyTestModel>("test_compounds")
        collection().insertOne(CompoundKeyTestModel(CompoundTestKey("A", "B")))
        collection().insertOne(CompoundKeyTestModel(CompoundTestKey("A", "C")))
        collection().insertOne(CompoundKeyTestModel(CompoundTestKey("C", "B")))
        try {
            collection().insertOne(CompoundKeyTestModel(CompoundTestKey("A", "C")))
            fail()
        } catch(e: UniqueViolationException) {
            // OK!
        }
        collection().find(condition(true)).collect { println(it) }
    }
    @Test fun test_compoundUpdate() = runTest {
        fun collection() = database.table<CompoundKeyTestModel>("test_compoundUpdate")
        collection().insertOne(CompoundKeyTestModel(CompoundTestKey("A", "B")))
        collection().updateOne(condition {
            it._id.eq(CompoundTestKey("A", "B")) and it._id.first.eq("A")
        }, modification {
            it.value assign 1
        })
        collection().find(condition(true)).collect { println(it) }
    }
}