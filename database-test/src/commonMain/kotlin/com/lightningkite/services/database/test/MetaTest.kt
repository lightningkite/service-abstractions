package com.lightningkite.services.database.test

import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains

abstract class MetaTest {
    abstract val database: Database

    @Test
    fun test() = runTest {
        val c = database.table<MetaTestModel>()
        val toInsert = MetaTestModel(
            condition = condition { it.int gt 3 },
            modification = modification { it.int += 2 }
        )
        c.insertOne(toInsert)
        val results = c.find(Condition.Always).toList()
        results.forEach { println(it) }
        assertContains(results, toInsert)
    }
}