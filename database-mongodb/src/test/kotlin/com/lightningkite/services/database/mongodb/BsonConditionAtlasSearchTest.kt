// by Claude - Tests for BSON condition conversion with atlasSearch flag,
// specifically verifying that SetAnyElements and ListAnyElements generate
// Atlas-compatible operators ($eq/$in) instead of $elemMatch when atlasSearch=true.
package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.mongodb.bson.KBson
import com.lightningkite.services.database.serializableProperties
import com.lightningkite.services.database.test.LargeTestModel
import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests that the atlasSearch flag in BSON condition conversion correctly handles
 * SetAnyElements and ListAnyElements conditions.
 *
 * Atlas $vectorSearch pre-filters only support: $eq, $ne, $gt, $gte, $lt, $lte, $in, $nin, $exists, $not.
 * $elemMatch is NOT supported and causes runtime errors on Atlas.
 *
 * When atlasSearch=false (normal queries): use $elemMatch (existing behavior)
 * When atlasSearch=true (vector search): dump inner condition directly on key (Atlas-compatible)
 */
class BsonConditionAtlasSearchTest {

    private val bson = KBson()
    private val serializer = LargeTestModel.serializer()
    private val props = serializer.serializableProperties!!

    // Get properties by name to build conditions manually (like KnowledgeBaseTools does)
    @Suppress("UNCHECKED_CAST")
    private val setProp = props.first { it.name == "set" } as SerializableProperty<LargeTestModel, Set<Int>>
    @Suppress("UNCHECKED_CAST")
    private val listProp = props.first { it.name == "list" } as SerializableProperty<LargeTestModel, List<Int>>
    @Suppress("UNCHECKED_CAST")
    private val intProp = props.first { it.name == "int" } as SerializableProperty<LargeTestModel, Int>

    // Helper: build OnField(setProp, SetAnyElements(innerCondition))
    private fun setAny(inner: Condition<Int>): Condition<LargeTestModel> =
        Condition.OnField(setProp, Condition.SetAnyElements(inner))

    // Helper: build OnField(listProp, ListAnyElements(innerCondition))
    private fun listAny(inner: Condition<Int>): Condition<LargeTestModel> =
        Condition.OnField(listProp, Condition.ListAnyElements(inner))

    // ============================================================
    // SetAnyElements - atlasSearch=false (existing behavior preserved)
    // ============================================================

    @Test
    fun setAnyElements_withoutAtlasSearch_usesElemMatch() {
        val cond = setAny(Condition.Equal(42))
        val doc = cond.bson(serializer, atlasSearch = false, bson = bson)
        // Should produce: {"set": {"$elemMatch": {"$eq": 42}}}
        val setDoc = doc["set"] as Document
        assertNotNull(setDoc["\$elemMatch"], "Expected \$elemMatch for SetAnyElements when atlasSearch=false")
        assertNull(setDoc["\$eq"], "Should NOT have direct \$eq when atlasSearch=false")
    }

    // ============================================================
    // SetAnyElements - atlasSearch=true (new Atlas-compatible behavior)
    // ============================================================

    @Test
    fun setAnyElements_withAtlasSearch_usesDirectEq() {
        val cond = setAny(Condition.Equal(42))
        val doc = cond.bson(serializer, atlasSearch = true, bson = bson)
        // Should produce: {"set": {"$eq": 42}} instead of {"set": {"$elemMatch": {"$eq": 42}}}
        val setDoc = doc["set"] as Document
        assertNotNull(setDoc["\$eq"], "Expected \$eq for SetAnyElements when atlasSearch=true")
        assertNull(setDoc["\$elemMatch"], "Should NOT have \$elemMatch when atlasSearch=true")
    }

    @Test
    fun setAnyElements_withAtlasSearch_usesDirectGt() {
        val cond = setAny(Condition.GreaterThan(10))
        val doc = cond.bson(serializer, atlasSearch = true, bson = bson)
        val setDoc = doc["set"] as Document
        assertNotNull(setDoc["\$gt"], "Expected \$gt when atlasSearch=true")
        assertNull(setDoc["\$elemMatch"], "Should NOT have \$elemMatch when atlasSearch=true")
    }

    // ============================================================
    // ListAnyElements - atlasSearch=false (existing behavior preserved)
    // ============================================================

    @Test
    fun listAnyElements_withoutAtlasSearch_usesElemMatch() {
        val cond = listAny(Condition.Equal(42))
        val doc = cond.bson(serializer, atlasSearch = false, bson = bson)
        val listDoc = doc["list"] as Document
        assertNotNull(listDoc["\$elemMatch"], "Expected \$elemMatch for ListAnyElements when atlasSearch=false")
        assertNull(listDoc["\$eq"], "Should NOT have direct \$eq when atlasSearch=false")
    }

    // ============================================================
    // ListAnyElements - atlasSearch=true (new Atlas-compatible behavior)
    // ============================================================

    @Test
    fun listAnyElements_withAtlasSearch_usesDirectEq() {
        val cond = listAny(Condition.Equal(42))
        val doc = cond.bson(serializer, atlasSearch = true, bson = bson)
        val listDoc = doc["list"] as Document
        assertNotNull(listDoc["\$eq"], "Expected \$eq for ListAnyElements when atlasSearch=true")
        assertNull(listDoc["\$elemMatch"], "Should NOT have \$elemMatch when atlasSearch=true")
    }

    @Test
    fun listAnyElements_withAtlasSearch_usesDirectGt() {
        val cond = listAny(Condition.GreaterThan(10))
        val doc = cond.bson(serializer, atlasSearch = true, bson = bson)
        val listDoc = doc["list"] as Document
        assertNotNull(listDoc["\$gt"], "Expected \$gt when atlasSearch=true")
        assertNull(listDoc["\$elemMatch"], "Should NOT have \$elemMatch when atlasSearch=true")
    }

    // ============================================================
    // Combined with $and (mimics SearchKnowledgeTool tag filter)
    // ============================================================

    @Test
    fun multipleSetAnyElements_withAtlasSearch_usesAndWithDirectOperators() {
        // Mirrors SearchKnowledgeTool:
        // Condition.And(tags.map { tag -> OnField(tagsProperty, SetAnyElements(Equal(tag))) })
        val cond = Condition.And(listOf(setAny(Condition.Equal(1)), setAny(Condition.Equal(2))))
        val doc = cond.bson(serializer, atlasSearch = true, bson = bson)

        // Should produce: {"$and": [{"set": {"$eq": 1}}, {"set": {"$eq": 2}}]}
        @Suppress("UNCHECKED_CAST")
        val andList = doc["\$and"] as List<Document>
        assertEquals(2, andList.size)

        for (sub in andList) {
            val setDoc = sub["set"] as Document
            assertNotNull(setDoc["\$eq"], "Each \$and sub-condition should use \$eq")
            assertNull(setDoc["\$elemMatch"], "No \$elemMatch in atlas mode")
        }
    }

    @Test
    fun multipleSetAnyElements_withoutAtlasSearch_usesAndWithElemMatch() {
        val cond = Condition.And(listOf(setAny(Condition.Equal(1)), setAny(Condition.Equal(2))))
        val doc = cond.bson(serializer, atlasSearch = false, bson = bson)

        @Suppress("UNCHECKED_CAST")
        val andList = doc["\$and"] as List<Document>
        assertEquals(2, andList.size)

        for (sub in andList) {
            val setDoc = sub["set"] as Document
            assertNotNull(setDoc["\$elemMatch"], "Each \$and sub-condition should use \$elemMatch")
            assertNull(setDoc["\$eq"], "No direct \$eq in non-atlas mode")
        }
    }

    // ============================================================
    // Non-collection conditions unaffected by atlasSearch flag
    // ============================================================

    @Test
    fun scalarConditions_identicalRegardlessOfAtlasSearchFlag() {
        val cond: Condition<LargeTestModel> = Condition.OnField(intProp, Condition.Equal(42))
        val withAtlas = cond.bson(serializer, atlasSearch = true, bson = bson)
        val withoutAtlas = cond.bson(serializer, atlasSearch = false, bson = bson)
        assertEquals(withAtlas.toJson(), withoutAtlas.toJson(), "Scalar conditions should be identical regardless of atlasSearch flag")
    }
}
