package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.bson.BsonDocument
import org.bson.BsonNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Nulls are normally stored as missing fields, which the shared suites cover. A document can still hold an explicit
 * null (written by another client or an older version), and notNull must reject that too.
 */
class ExplicitNullConditionTest {
    @Test
    fun notNull_rejectsExplicitNull() = runTest {
        val name = "ExplicitNullConditionTest_notNull"
        val collection = db().prepare(DatabaseTableDefinition<LargeTestModel>(name))
        collection.insertOne(LargeTestModel())
        db().database.getCollection(name, BsonDocument::class.java)
            .updateOne(Filters.empty(), BsonDocument("\$set", BsonDocument("intNullable", BsonNull.VALUE)))

        assertEquals(listOf(), collection.find(condition { it.intNullable.notNull neq 1 }).toList())
        assertEquals(listOf(), collection.find(condition { it.intNullable.notNull notInside listOf(1) }).toList())
    }
}
