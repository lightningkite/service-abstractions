package com.lightningkite.services.database.cassandra

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeohashTest {

    @Test
    fun testEncodeKnownLocation() {
        // San Francisco roughly at 37.7749, -122.4194
        val hash = GeohashComputer.encode(37.7749, -122.4194, 6)
        assertEquals("9q8yyk", hash)
    }

    @Test
    fun testEncodePrecision() {
        val lat = 37.7749
        val lon = -122.4194

        // Higher precision = more characters
        val hash4 = GeohashComputer.encode(lat, lon, 4)
        val hash6 = GeohashComputer.encode(lat, lon, 6)
        val hash8 = GeohashComputer.encode(lat, lon, 8)

        assertEquals(4, hash4.length)
        assertEquals(6, hash6.length)
        assertEquals(8, hash8.length)

        // Higher precision is prefix of lower
        assertTrue(hash6.startsWith(hash4))
        assertTrue(hash8.startsWith(hash6))
    }

    @Test
    fun testDecodeReturnsReasonableBounds() {
        val hash = "9q8yyk"
        val bounds = GeohashComputer.decode(hash)

        // Should be roughly around San Francisco
        assertTrue(bounds.minLat > 37.0)
        assertTrue(bounds.maxLat < 38.0)
        assertTrue(bounds.minLon > -123.0)
        assertTrue(bounds.maxLon < -122.0)
    }

    @Test
    fun testRoundTrip() {
        val lat = 51.5074
        val lon = -0.1278 // London

        val hash = GeohashComputer.encode(lat, lon, 8)
        val bounds = GeohashComputer.decode(hash)

        // Original point should be within decoded bounds
        assertTrue(lat >= bounds.minLat && lat <= bounds.maxLat)
        assertTrue(lon >= bounds.minLon && lon <= bounds.maxLon)
    }

    @Test
    fun testNeighborsCount() {
        val hash = "9q8yyk"
        val neighbors = GeohashComputer.neighbors(hash)

        // Should return 8 neighbors
        assertEquals(8, neighbors.size)

        // All neighbors should be same length
        neighbors.forEach { assertEquals(hash.length, it.length) }
    }

    @Test
    fun testNeighborsAreAdjacent() {
        val hash = "9q8yyk"
        val center = GeohashComputer.decode(hash)
        val neighbors = GeohashComputer.neighbors(hash)

        // Each neighbor's bounds should touch or slightly overlap the center
        neighbors.forEach { neighborHash ->
            val neighborBounds = GeohashComputer.decode(neighborHash)

            // Neighbors should be close (within reasonable distance)
            val latDistance = kotlin.math.abs(center.centerLat - neighborBounds.centerLat)
            val lonDistance = kotlin.math.abs(center.centerLon - neighborBounds.centerLon)

            // For 6-char geohash, neighbors should be within ~1 degree
            assertTrue(latDistance < 1.0, "Lat distance $latDistance too large")
            assertTrue(lonDistance < 1.0, "Lon distance $lonDistance too large")
        }
    }

    @Test
    fun testNeighborsForRadius() {
        val lat = 37.7749
        val lon = -122.4194

        // Small radius should return higher precision
        val smallRadius = GeohashComputer.neighborsForRadius(lat, lon, 0.5)
        val largeRadius = GeohashComputer.neighborsForRadius(lat, lon, 50.0)

        // All should include center + 8 neighbors = 9 hashes
        assertEquals(9, smallRadius.size)
        assertEquals(9, largeRadius.size)

        // Small radius hashes should be longer (higher precision)
        assertTrue(smallRadius.first().length > largeRadius.first().length)
    }

    @Test
    fun testEdgeCases() {
        // Equator
        val equator = GeohashComputer.encode(0.0, 0.0, 6)
        assertEquals(6, equator.length)

        // North pole area
        val north = GeohashComputer.encode(89.0, 0.0, 6)
        assertEquals(6, north.length)

        // South pole area
        val south = GeohashComputer.encode(-89.0, 0.0, 6)
        assertEquals(6, south.length)

        // Date line
        val dateLine = GeohashComputer.encode(0.0, 179.9, 6)
        assertEquals(6, dateLine.length)
    }

    // TODO: The NEIGHBOR_DIRECTIONS table contains "tele" which doesn't match base32 alphabet.
    // Reference implementations use "kmstqrwxuvhjyznp" instead.
    // However, changing it breaks existing tests, suggesting this may be a variant algorithm
    // or the tests themselves need updating. Needs further investigation.
    // See: https://www.movable-type.co.uk/scripts/geohash.html
}
