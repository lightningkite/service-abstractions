// by Claude - Tests for BSON condition conversion with the vectorSearchFilter flag,
// specifically verifying that CollectionAnyElements on sets and lists generate
// Atlas-compatible operators ($eq/$in) instead of $elemMatch when vectorSearchFilter=true.
package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.database.*
import com.lightningkite.services.database.mongodb.bson.KBson
import com.lightningkite.services.database.test.*
import org.bson.Document
import kotlin.test.*

/**
 * Tests that the vectorSearchFilter flag in BSON condition conversion correctly handles
 * CollectionAnyElements conditions on sets and lists.
 *
 * Atlas $vectorSearch pre-filters only support: $eq, $ne, $gt, $gte, $lt, $lte, $in, $nin, $exists, $not.
 * $elemMatch is NOT supported and causes runtime errors on Atlas.
 *
 * When vectorSearchFilter=false (normal queries): use $elemMatch (existing behavior)
 * When vectorSearchFilter=true (vector search): dump inner condition directly on key (Atlas-compatible)
 */
class BsonConditionVectorSearchFilterTest {

    private val bson = KBson()
    private val serializer = LargeTestModel.serializer()
    private val props = serializer.fields!!

    // Get properties by name to build conditions manually (like KnowledgeBaseTools does)
    @Suppress("UNCHECKED_CAST")
    private val setProp = props.first { it.name == "set" } as Field<LargeTestModel, Set<Int>>

    @Suppress("UNCHECKED_CAST")
    private val listProp = props.first { it.name == "list" } as Field<LargeTestModel, List<Int>>

    @Suppress("UNCHECKED_CAST")
    private val intProp = props.first { it.name == "int" } as Field<LargeTestModel, Int>

    // Helper: build OnField(setProp, CollectionAnyElements(innerCondition))
    private fun setAny(inner: Condition<Int>): Condition<LargeTestModel> =
        Condition.OnField(setProp, Condition.CollectionAnyElements(inner))

    // Helper: build OnField(listProp, CollectionAnyElements(innerCondition))
    private fun listAny(inner: Condition<Int>): Condition<LargeTestModel> =
        Condition.OnField(listProp, Condition.CollectionAnyElements(inner))

    // ============================================================
    // SetAnyElements - vectorSearchFilter=false (existing behavior preserved)
    // ============================================================

    @Test
    fun setAnyElements_normalQuery_usesElemMatch() {
        val cond = setAny(Condition.Equal(42))
        val doc = cond.bson(serializer, vectorSearchFilter = false, bson = bson)
        // Should produce: {"set": {"$elemMatch": {"$eq": 42}}}
        val setDoc = doc["set"] as Document
        assertNotNull(setDoc["\$elemMatch"], "Expected \$elemMatch for SetAnyElements when vectorSearchFilter=false")
        assertNull(setDoc["\$eq"], "Should NOT have direct \$eq when vectorSearchFilter=false")
    }

    // ============================================================
    // SetAnyElements - vectorSearchFilter=true (new Atlas-compatible behavior)
    // ============================================================

    @Test
    fun setAnyElements_vectorSearchFilter_usesDirectEq() {
        val cond = setAny(Condition.Equal(42))
        val doc = cond.bson(serializer, vectorSearchFilter = true, bson = bson)
        // Should produce: {"set": {"$eq": 42}} instead of {"set": {"$elemMatch": {"$eq": 42}}}
        val setDoc = doc["set"] as Document
        assertNotNull(setDoc["\$eq"], "Expected \$eq for SetAnyElements when vectorSearchFilter=true")
        assertNull(setDoc["\$elemMatch"], "Should NOT have \$elemMatch when vectorSearchFilter=true")
    }

    @Test
    fun setAnyElements_vectorSearchFilter_usesDirectGt() {
        val cond = setAny(Condition.GreaterThan(10))
        val doc = cond.bson(serializer, vectorSearchFilter = true, bson = bson)
        val setDoc = doc["set"] as Document
        assertNotNull(setDoc["\$gt"], "Expected \$gt when vectorSearchFilter=true")
        assertNull(setDoc["\$elemMatch"], "Should NOT have \$elemMatch when vectorSearchFilter=true")
    }

    // ============================================================
    // ListAnyElements - vectorSearchFilter=false (existing behavior preserved)
    // ============================================================

    @Test
    fun listAnyElements_normalQuery_usesElemMatch() {
        val cond = listAny(Condition.Equal(42))
        val doc = cond.bson(serializer, vectorSearchFilter = false, bson = bson)
        val listDoc = doc["list"] as Document
        assertNotNull(listDoc["\$elemMatch"], "Expected \$elemMatch for ListAnyElements when vectorSearchFilter=false")
        assertNull(listDoc["\$eq"], "Should NOT have direct \$eq when vectorSearchFilter=false")
    }

    // ============================================================
    // ListAnyElements - vectorSearchFilter=true (new Atlas-compatible behavior)
    // ============================================================

    @Test
    fun listAnyElements_vectorSearchFilter_usesDirectEq() {
        val cond = listAny(Condition.Equal(42))
        val doc = cond.bson(serializer, vectorSearchFilter = true, bson = bson)
        val listDoc = doc["list"] as Document
        assertNotNull(listDoc["\$eq"], "Expected \$eq for ListAnyElements when vectorSearchFilter=true")
        assertNull(listDoc["\$elemMatch"], "Should NOT have \$elemMatch when vectorSearchFilter=true")
    }

    @Test
    fun listAnyElements_vectorSearchFilter_usesDirectGt() {
        val cond = listAny(Condition.GreaterThan(10))
        val doc = cond.bson(serializer, vectorSearchFilter = true, bson = bson)
        val listDoc = doc["list"] as Document
        assertNotNull(listDoc["\$gt"], "Expected \$gt when vectorSearchFilter=true")
        assertNull(listDoc["\$elemMatch"], "Should NOT have \$elemMatch when vectorSearchFilter=true")
    }

    // ============================================================
    // Combined with $and (mimics SearchKnowledgeTool tag filter)
    // ============================================================

    @Test
    fun multipleSetAnyElements_vectorSearchFilter_usesAndWithDirectOperators() {
        // Mirrors SearchKnowledgeTool:
        // Condition.And(tags.map { tag -> OnField(tagsProperty, SetAnyElements(Equal(tag))) })
        val cond = Condition.And(listOf(setAny(Condition.Equal(1)), setAny(Condition.Equal(2))))
        val doc = cond.bson(serializer, vectorSearchFilter = true, bson = bson)

        // Should produce: {"$and": [{"set": {"$eq": 1}}, {"set": {"$eq": 2}}]}
        @Suppress("UNCHECKED_CAST")
        val andList = doc["\$and"] as List<Document>
        assertEquals(2, andList.size)

        for (sub in andList) {
            val setDoc = sub["set"] as Document
            assertNotNull(setDoc["\$eq"], "Each \$and sub-condition should use \$eq")
            assertNull(setDoc["\$elemMatch"], "No \$elemMatch in a vector search filter")
        }
    }

    @Test
    fun multipleSetAnyElements_normalQuery_usesAndWithElemMatch() {
        val cond = Condition.And(listOf(setAny(Condition.Equal(1)), setAny(Condition.Equal(2))))
        val doc = cond.bson(serializer, vectorSearchFilter = false, bson = bson)

        @Suppress("UNCHECKED_CAST")
        val andList = doc["\$and"] as List<Document>
        assertEquals(2, andList.size)

        for (sub in andList) {
            val setDoc = sub["set"] as Document
            assertNotNull(setDoc["\$elemMatch"], "Each \$and sub-condition should use \$elemMatch")
            assertNull(setDoc["\$eq"], "No direct \$eq in a normal query")
        }
    }

    // ============================================================
    // Non-collection conditions unaffected by vectorSearchFilter
    // ============================================================

    @Test
    fun scalarConditions_identicalRegardlessOfVectorSearchFilter() {
        val cond: Condition<LargeTestModel> = Condition.OnField(intProp, Condition.Equal(42))
        val withAtlas = cond.bson(serializer, vectorSearchFilter = true, bson = bson)
        val withoutAtlas = cond.bson(serializer, vectorSearchFilter = false, bson = bson)
        assertEquals(
            withAtlas.toJson(),
            withoutAtlas.toJson(),
            "Scalar conditions should be identical regardless of vectorSearchFilter"
        )
    }

    // ============================================================
    // Element conditions that flattening would change
    // ============================================================

    private val bothFieldsOnOneElement = Condition<LargeTestModel> { it.listEmbedded.any { (it.value1 eq "a") and (it.value2 eq 1) } }

    @Test
    fun compoundAnyElements_normalQuery_usesElemMatch() {
        val listDoc = bothFieldsOnOneElement.bson(serializer, bson = bson)["listEmbedded"] as Document
        assertNotNull(listDoc["\$elemMatch"])
    }

    @Test
    fun compoundAnyElements_vectorSearchFilter_throws() {
        val e = assertFailsWith<IllegalArgumentException> {
            bothFieldsOnOneElement.bson(serializer, vectorSearchFilter = true, bson = bson)
        }
        assertContains(e.message!!, "\$elemMatch")
    }

    @Test
    fun negatedAnyElements_vectorSearchFilter_throws() {
        // Flattened, `$ne` would mean "no element equals 1" rather than "some element isn't 1".
        assertFailsWith<IllegalArgumentException> { setAny(Condition.NotEqual(1)).bson(serializer, vectorSearchFilter = true, bson = bson) }
        assertFailsWith<IllegalArgumentException> { setAny(Condition.Always).bson(serializer, vectorSearchFilter = true, bson = bson) }
    }

    @Test
    fun orAnyElements_vectorSearchFilter_flattens() {
        // Some element matching `a or b` is the same as some element matching a, or some matching b.
        val cond = Condition<LargeTestModel> { it.listEmbedded.any { (it.value1 eq "a") or (it.value2 eq 1) } }
        val doc = cond.bson(serializer, vectorSearchFilter = true, bson = bson)
        assertEquals(
            Document("\$or", listOf(
                Document("listEmbedded.value1", Document("\$eq", "a")),
                Document("listEmbedded.value2", Document("\$eq", 1)),
            )).toJson(),
            doc.toJson()
        )
    }

}
