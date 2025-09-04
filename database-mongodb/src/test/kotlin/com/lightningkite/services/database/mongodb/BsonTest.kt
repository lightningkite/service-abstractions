package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.mongodb.bson.KBson
import com.lightningkite.services.database.test.LargeTestModel
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class BsonTest {
    @Test
    fun test() {
        val ser = LargeTestModel.serializer()
        val bson = KBson()
        val sample = LargeTestModel()
        assertEquals(sample, bson.parse(ser, bson.stringify(ser, sample).also { println(it) }))
        assertEquals(sample, bson.decodeFromByteArray(ser, bson.encodeToByteArray(ser, sample)))
    }

    @Test fun stringifyAnyTest() {
        println(KBson().stringifyAny(Int.serializer(), 1))
    }
}