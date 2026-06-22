package com.lightningkite.services.database.mongodb

import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.database.*
import com.lightningkite.services.database.mongodb.bson.KBson
import com.lightningkite.services.database.test.GeoTest
import com.lightningkite.services.database.test.LargeTestModel
import org.bson.BsonRegularExpression
import org.bson.Document
import kotlin.test.*

/**
 * Verifies that BSON condition serialization produces DocumentDB-compatible output.
 *
 * DocumentDB 5.0+ rejects {$regex: "str", $options: "i"} — options must be embedded
 * in the BSON regex type. DocumentDB also doesn't support $centerSphere, requiring
 * $nearSphere with a GeoJSON geometry instead.
 */
class BsonConditionDocumentDbTest {

    private val bson = KBson()
    private val largeSerializer = LargeTestModel.serializer()
    private val geoSerializer = GeoTest.serializer()

    @Suppress("UNCHECKED_CAST")
    private val stringProp = largeSerializer.serializableProperties!!
        .first { it.name == "string" } as SerializableProperty<LargeTestModel, String>

    @Suppress("UNCHECKED_CAST")
    private val geoProp = geoSerializer.serializableProperties!!
        .first { it.name == "geo" } as SerializableProperty<GeoTest, GeoCoordinate>

    // ─── Regex conditions ────────────────────────────────────────────────────

    @Test
    fun stringContains_usesBsonRegexType_notSeparateOptionsField() {
        val cond = Condition.OnField(stringProp, Condition.StringContains("hello", ignoreCase = true))
        val doc = cond.bson(largeSerializer, atlasSearch = false, bson = bson)

        val stringDoc = doc["string"] as Document
        val regexValue = stringDoc["\$regex"]

        assertIs<BsonRegularExpression>(regexValue, "Expected BsonRegularExpression, got ${regexValue?.javaClass}")
        val bsonRegex = regexValue as BsonRegularExpression
        assertTrue(bsonRegex.options.contains("i"), "Expected case-insensitive flag in regex options")
        assertNull(stringDoc["\$options"], "Must NOT have a separate \$options field — DocumentDB 5.0+ rejects it")
    }

    @Test
    fun stringContains_caseSensitive_emptyOptions() {
        val cond = Condition.OnField(stringProp, Condition.StringContains("hello", ignoreCase = false))
        val doc = cond.bson(largeSerializer, atlasSearch = false, bson = bson)

        val stringDoc = doc["string"] as Document
        val regexValue = stringDoc["\$regex"]

        assertIs<BsonRegularExpression>(regexValue)
        val bsonRegex = regexValue as BsonRegularExpression
        assertFalse(bsonRegex.options.contains("i"), "Should not have case-insensitive flag")
        assertNull(stringDoc["\$options"], "Must NOT have a separate \$options field")
    }

    @Test
    fun regexMatches_usesBsonRegexType_notSeparateOptionsField() {
        val cond = Condition.OnField(stringProp, Condition.RegexMatches("^hello.*", ignoreCase = true))
        val doc = cond.bson(largeSerializer, atlasSearch = false, bson = bson)

        val stringDoc = doc["string"] as Document
        val regexValue = stringDoc["\$regex"]

        assertIs<BsonRegularExpression>(regexValue, "Expected BsonRegularExpression")
        assertNull(stringDoc["\$options"], "Must NOT have a separate \$options field — DocumentDB 5.0+ rejects it")
    }

    // ─── Geo conditions ──────────────────────────────────────────────────────

    @Test
    fun geoDistance_usesNearSphere_notGeoWithinCenterSphere() {
        val lk = GeoCoordinate(41.727019, -111.8443002)
        val cond = Condition.OnField(geoProp, Condition.GeoDistance(lk, lessThanKilometers = 50.0))
        val doc = cond.bson(geoSerializer, atlasSearch = false, bson = bson)

        val geoDoc = doc["geo"] as Document
        assertNotNull(geoDoc["\$nearSphere"], "Expected \$nearSphere for DocumentDB compatibility")
        assertNull(geoDoc["\$geoWithin"], "Must NOT use \$geoWithin — its \$centerSphere argument is unsupported by DocumentDB")

        val nearDoc = geoDoc["\$nearSphere"] as Document
        assertNotNull(nearDoc["\$geometry"], "Expected GeoJSON \$geometry in \$nearSphere")
        assertNotNull(nearDoc["\$maxDistance"], "\$nearSphere must include \$maxDistance")

        val geometry = nearDoc["\$geometry"] as Document
        assertEquals("Point", geometry["type"])
        @Suppress("UNCHECKED_CAST")
        val coords = geometry["coordinates"] as List<Double>
        assertEquals(lk.longitude, coords[0], 0.0001)
        assertEquals(lk.latitude, coords[1], 0.0001)

        val maxDist = (nearDoc["\$maxDistance"] as Number).toDouble()
        assertEquals(50_000.0, maxDist, 1.0, "50 km = 50,000 meters")
    }

    @Test
    fun geoDistance_withMinDistance_includesMinDistanceField() {
        val lk = GeoCoordinate(41.727019, -111.8443002)
        val cond = Condition.OnField(geoProp, Condition.GeoDistance(lk, greaterThanKilometers = 50.0))
        val doc = cond.bson(geoSerializer, atlasSearch = false, bson = bson)

        val geoDoc = doc["geo"] as Document
        val nearDoc = geoDoc["\$nearSphere"] as Document

        assertNotNull(nearDoc["\$minDistance"], "greaterThan distance should set \$minDistance")
        val minDist = (nearDoc["\$minDistance"] as Number).toDouble()
        assertEquals(50_000.0, minDist, 1.0, "50 km = 50,000 meters")
    }
}
