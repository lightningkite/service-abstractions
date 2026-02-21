package com.lightningkite.services.database.test

import kotlinx.coroutines.flow.*
import com.lightningkite.services.database.*
import com.lightningkite.services.data.*
import kotlinx.coroutines.test.*
import kotlin.test.*

abstract class AggregationsTest() {

    abstract val database: Database

    @Test
    fun test() = runTest {

        val c = database.table<LargeTestModel>("aggregationstest")
        c.insertMany(listOf(
            LargeTestModel(int = 32, byte = 0, embedded = ClassUsedForEmbedding(value2 = 32)),
            LargeTestModel(int = 42, byte = 0, embedded = ClassUsedForEmbedding(value2 = 42)),
            LargeTestModel(int = 52, byte = 0, embedded = ClassUsedForEmbedding(value2 = 52)),
            LargeTestModel(int = 34, byte = 1, embedded = ClassUsedForEmbedding(value2 = 34)),
            LargeTestModel(int = 45, byte = 1, embedded = ClassUsedForEmbedding(value2 = 45)),
            LargeTestModel(int = 56, byte = 1, embedded = ClassUsedForEmbedding(value2 = 56)),
        ))
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
            for(type in Aggregate.entries) {
                val control = c.all().toList().asSequence().map { it.int.toDouble() }.aggregate(type)
                val test: Double? = c.aggregate(type, property = property)
                if(control == null || test == null) fail()
                assertEquals(control, test, 0.0000001)
            }
            for(type in Aggregate.entries) {
                val control = c.all().toList().asSequence().map { it.byte to it.int.toDouble() }.aggregate(type)
                val test: Map<Byte, Double?> = c.groupAggregate(type, property = property, groupBy = path<LargeTestModel>().byte)
                assertEquals(control.keys, test.keys)
                for(key in control.keys) {
                    assertEquals(control[key]!!, test[key]!!, 0.0000001)
                }
            }
            for(type in Aggregate.entries) {
                val control = c.all().toList().asSequence().map { it.int.toDouble() }.filter { false }.aggregate(type)
                val test: Double? = c.aggregate(type, property = property, condition = Condition.Never)
                if(control == null) assertNull(test)
                else assertEquals(control, test!!, 0.0000001)
            }
            for(type in Aggregate.entries) {
                val control = c.all().toList().asSequence().map { it.byte to it.int.toDouble() }.filter { false }.aggregate(type)
                val test: Map<Byte, Double?> = c.groupAggregate(type, property = property, groupBy = path<LargeTestModel>().byte, condition = Condition.Never)
                assertEquals(control.keys, test.keys)
                for(key in control.keys) {
                    assertEquals(control[key]!!, test[key]!!, 0.0000001)
                }
            }
        }
    }

    @Test
    fun testInlines() = runTest {
        val c = database.table<ValueClassContainingTest>("inlineAggregatesTest")

        val ints = List(10) { it }

        c.insertMany(
            ints.map { ValueClassContainingTest(wrappedInt = IntWrapper(it)) }
        )

        for (type in Aggregate.entries) {
            val ram = ints.asSequence().map { it.toDouble() }.aggregate(type)
            val control = c.all().toList().asSequence().map { it.wrappedInt.int.toDouble() }.aggregate(type)
            val test = c.aggregate(type, Condition.Always, path<ValueClassContainingTest>().wrappedInt.int)
            assertEquals(ram, control)
            assertEquals(control, test)
        }
    }
}