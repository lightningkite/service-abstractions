@file:UseContextualSerialization(Uuid::class)
package com.lightningkite.services.database.test

import kotlinx.coroutines.flow.*
import com.lightningkite.services.database.*
import com.lightningkite.services.data.*
import com.lightningkite.*
import com.lightningkite.Length.Companion.kilometers
import kotlinx.coroutines.test.*
import kotlinx.serialization.UseContextualSerialization
import kotlin.test.*
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.*

abstract class MetaTest {
    abstract val database: Database

    @Test
    fun test() = runTest {
        val c = database.collection<MetaTestModel>()
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