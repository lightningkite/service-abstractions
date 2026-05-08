package com.lightningkite.services.database.mongodb

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.mongodb.bson.KBson
import com.lightningkite.services.database.serializableProperties
import com.lightningkite.services.database.test.LargeTestModel
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.bson.Document
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BsonTest {
    @Test
    fun test() {
        val ser = LargeTestModel.serializer()
        val bson = KBson()
        val sample = LargeTestModel()
        assertEquals(sample, bson.parse(ser, bson.stringify(ser, sample).also { println(it) }))
        assertEquals(sample, bson.decodeFromByteArray(ser, bson.encodeToByteArray(ser, sample)))
    }

    @Test
    fun stringifyAnyTest() {
        println(KBson().stringifyAny(Int.serializer(), 1))
    }
}


@Serializable
@GenerateDataClassPaths
data class ObjectIdTest(
    @Contextual val _id: ObjectId,
)