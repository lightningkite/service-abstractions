package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.path.*

import com.lightningkite.services.database.*
import com.lightningkite.services.database.mongodb.bson.KBson
import com.lightningkite.services.database.test.*
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The generated BSON for notNull steps: updates stay plain operator documents with the checks in the filter, and
 * common queries don't get an extra null check.
 */
class CheckedUpdateBsonTest {
    private val bson = KBson()
    private val serializer = LargeTestModel.serializer()

    @Test
    fun checks_goIntoFilter() {
        val update = modification<LargeTestModel> {
            it.int += 1
            it.embeddedNullable.notNull.value1 assign "x"
            it.intNullable.notNull += 1
        }.bson(serializer, bson)
        assertEquals(
            Document("\$inc", Document("int", BsonInt32(1)).append("intNullable", BsonInt32(1)))
                .append("\$set", Document("embeddedNullable.value1", BsonString("x"))),
            update.document
        )
        assertEquals(
            Document(
                "\$and", listOf(
                    Document("\$and", listOf(Document("embeddedNullable", Document("\$ne", null)))),
                    Document("\$and", listOf(Document("intNullable", Document("\$ne", null)))),
                )
            ),
            update.check
        )
    }

    // DocumentDB applies only the first array filter an element matches, so no element may match two.
    @Test
    fun withoutOverlappingArrayFilters_eachElementMatchesOneFilter() {
        val update = modification<NullableInListTestModel> {
            it.items.forEach { it.embed1.notNull.value2 += 1 }
            it.items.forEachIf(
                condition = { it.name eq "a" },
                modification = {
                    it.name assign "changed"
                    it.embed1.notNull.value1 assign "changed"
                }
            )
        }.bson(NullableInListTestModel.serializer(), bson, overlappingArrayFilters = false)
        assertEquals(
            Document("\$inc", Document("items.$[f0].embed1.value2", BsonInt32(1)).append("items.$[f2].embed1.value2", BsonInt32(1)))
                .append(
                    "\$set", Document("items.$[f1].name", BsonString("changed"))
                        .append("items.$[f2].name", BsonString("changed"))
                        .append("items.$[f2].embed1.value1", BsonString("changed"))
                ),
            update.document
        )
        assertEquals(3, update.options.arrayFilters!!.size)
    }

    private fun query(condition: Condition<LargeTestModel>) = condition.bson(serializer, bson = bson)

    @Test
    fun notNull_nullRejectingConditions_keepTheirShape() {
        assertEquals(Document("intNullable", Document("\$eq", BsonInt32(1))), query(condition { it.intNullable.notNull eq 1 }))
        assertEquals(Document("intNullable", Document("\$gt", BsonInt32(1))), query(condition { it.intNullable.notNull gt 1 }))
        assertEquals(
            Document("embeddedNullable.value1", Document("\$eq", BsonString("a"))),
            query(condition { it.embeddedNullable.notNull.value1 eq "a" })
        )
    }
}
