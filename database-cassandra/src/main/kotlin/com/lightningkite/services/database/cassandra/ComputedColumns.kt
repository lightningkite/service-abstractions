package com.lightningkite.services.database.cassandra

import com.lightningkite.services.database.serializableProperties
import kotlinx.serialization.KSerializer

/**
 * Handles computation of derived columns for Cassandra.
 * Computed columns are automatically populated on insert/update based on source properties.
 */
public class ComputedColumnsHandler<T : Any>(
    private val schema: CassandraSchema<T>,
    private val serializer: KSerializer<T>
) {
    /**
     * Computes all derived column values for a model.
     * Returns a new map with computed values added/updated.
     */
    @Suppress("UNCHECKED_CAST")
    public fun computeDerivedValues(model: T, currentValues: Map<String, Any?>): Map<String, Any?> {
        val result = currentValues.toMutableMap()
        val properties = serializer.serializableProperties ?: return result

        for ((columnName, info) in schema.computedColumns) {
            val computedValue = computeValue(model, info, properties)
            result[columnName] = computedValue
        }

        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun computeValue(
        model: T,
        info: ComputedColumnInfo<T>,
        properties: Array<out com.lightningkite.services.database.SerializableProperty<T, *>>
    ): Any? {
        return when (info.transform) {
            ComputedTransform.LOWERCASE -> {
                val sourceName = info.sourceProperties.firstOrNull() ?: return null
                val sourceProp = properties.find { it.name == sourceName } ?: return null
                val sourceValue = sourceProp.get(model)
                (sourceValue as? String)?.lowercase()
            }

            ComputedTransform.REVERSED -> {
                val sourceName = info.sourceProperties.firstOrNull() ?: return null
                val sourceProp = properties.find { it.name == sourceName } ?: return null
                val sourceValue = sourceProp.get(model)
                (sourceValue as? String)?.reversed()
            }

            ComputedTransform.GEOHASH -> {
                computeGeohash(model, info.sourceProperties, properties, precision = 8)
            }

            ComputedTransform.GEOHASH_PRECISION_4 -> {
                computeGeohash(model, info.sourceProperties, properties, precision = 4)
            }

            ComputedTransform.GEOHASH_PRECISION_6 -> {
                computeGeohash(model, info.sourceProperties, properties, precision = 6)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun computeGeohash(
        model: T,
        sourceProperties: List<String>,
        properties: Array<out com.lightningkite.services.database.SerializableProperty<T, *>>,
        precision: Int
    ): String? {
        if (sourceProperties.size < 2) return null

        val latProp = properties.find { it.name == sourceProperties[0] } ?: return null
        val lonProp = properties.find { it.name == sourceProperties[1] } ?: return null

        val lat = (latProp.get(model) as? Number)?.toDouble() ?: return null
        val lon = (lonProp.get(model) as? Number)?.toDouble() ?: return null

        return GeohashComputer.encode(lat, lon, precision)
    }
}

/**
 * Geohash encoding utilities for geospatial queries.
 */
public object GeohashComputer {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /**
     * Computes a geohash from latitude/longitude.
     */
    public fun encode(latitude: Double, longitude: Double, precision: Int = 8): String {
        var latRange = -90.0 to 90.0
        var lonRange = -180.0 to 180.0
        var isEven = true
        var bit = 0
        var ch = 0
        val hash = StringBuilder()

        while (hash.length < precision) {
            if (isEven) {
                val mid = (lonRange.first + lonRange.second) / 2
                if (longitude >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lonRange = mid to lonRange.second
                } else {
                    lonRange = lonRange.first to mid
                }
            } else {
                val mid = (latRange.first + latRange.second) / 2
                if (latitude >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latRange = mid to latRange.second
                } else {
                    latRange = latRange.first to mid
                }
            }

            isEven = !isEven
            if (bit < 4) {
                bit++
            } else {
                hash.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }

        return hash.toString()
    }

    /**
     * Decodes a geohash to a bounding box.
     */
    public fun decode(geohash: String): GeoBoundingBox {
        var latRange = -90.0 to 90.0
        var lonRange = -180.0 to 180.0
        var isEven = true

        for (char in geohash) {
            val cd = BASE32.indexOf(char.lowercaseChar())
            if (cd < 0) continue

            for (mask in listOf(16, 8, 4, 2, 1)) {
                if (isEven) {
                    val mid = (lonRange.first + lonRange.second) / 2
                    if ((cd and mask) != 0) {
                        lonRange = mid to lonRange.second
                    } else {
                        lonRange = lonRange.first to mid
                    }
                } else {
                    val mid = (latRange.first + latRange.second) / 2
                    if ((cd and mask) != 0) {
                        latRange = mid to latRange.second
                    } else {
                        latRange = latRange.first to mid
                    }
                }
                isEven = !isEven
            }
        }

        return GeoBoundingBox(
            minLat = latRange.first,
            maxLat = latRange.second,
            minLon = lonRange.first,
            maxLon = lonRange.second
        )
    }

    /**
     * Returns neighboring geohashes that cover a radius from a center point.
     */
    public fun neighborsForRadius(
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double
    ): List<String> {
        // Calculate appropriate precision based on radius
        val precision = when {
            radiusKm > 5000 -> 1
            radiusKm > 600 -> 2
            radiusKm > 150 -> 3
            radiusKm > 20 -> 4
            radiusKm > 5 -> 5
            radiusKm > 1 -> 6
            radiusKm > 0.15 -> 7
            else -> 8
        }

        val centerHash = encode(centerLat, centerLon, precision)
        return listOf(centerHash) + neighbors(centerHash)
    }

    /**
     * Returns the 8 neighbors of a geohash.
     */
    public fun neighbors(geohash: String): List<String> {
        return listOf(
            neighbor(geohash, 1, 0),   // North
            neighbor(geohash, 1, 1),   // Northeast
            neighbor(geohash, 0, 1),   // East
            neighbor(geohash, -1, 1),  // Southeast
            neighbor(geohash, -1, 0),  // South
            neighbor(geohash, -1, -1), // Southwest
            neighbor(geohash, 0, -1),  // West
            neighbor(geohash, 1, -1)   // Northwest
        ).filterNotNull()
    }

    private val NEIGHBOR_DIRECTIONS = mapOf(
        "n" to arrayOf("p0r21436x8zb9dcf5h7kjnmqesgutwvy", "bc01fg45238967deuvhjyznpkmstqrwx"),
        "s" to arrayOf("14365h7k9dcfesgujnmqp0r2twvyx8zb", "238967debc01fg45uvhjyznpkmstqrwx"),
        "e" to arrayOf("bc01fg45238967deuvhjyznpkmstqrwx", "p0r21436x8zb9dcf5h7kjnmqesgutwvy"),
        "w" to arrayOf("238967debc01fg45uvhjyznpkmstqrwx", "14365h7k9dcfesgujnmqp0r2twvyx8zb")
    )

    private val BORDER = mapOf(
        "n" to arrayOf("prxz", "bcfguvyz"),
        "s" to arrayOf("028b", "0145hjnp"),
        "e" to arrayOf("bcfguvyz", "prxz"),
        "w" to arrayOf("0145hjnp", "028b")
    )

    private fun neighbor(geohash: String, latDir: Int, lonDir: Int): String? {
        if (geohash.isEmpty()) return null

        val direction = when {
            latDir > 0 && lonDir == 0 -> "n"
            latDir < 0 && lonDir == 0 -> "s"
            latDir == 0 && lonDir > 0 -> "e"
            latDir == 0 && lonDir < 0 -> "w"
            latDir > 0 && lonDir > 0 -> return neighbor(neighbor(geohash, 1, 0) ?: return null, 0, 1)
            latDir > 0 && lonDir < 0 -> return neighbor(neighbor(geohash, 1, 0) ?: return null, 0, -1)
            latDir < 0 && lonDir > 0 -> return neighbor(neighbor(geohash, -1, 0) ?: return null, 0, 1)
            latDir < 0 && lonDir < 0 -> return neighbor(neighbor(geohash, -1, 0) ?: return null, 0, -1)
            else -> return geohash
        }

        val lastChar = geohash.last().lowercaseChar()
        val parent = geohash.dropLast(1)
        val type = geohash.length % 2 // 0 = even, 1 = odd

        val border = BORDER[direction]!![type]
        val neighborChars = NEIGHBOR_DIRECTIONS[direction]!![type]

        return if (border.contains(lastChar) && parent.isNotEmpty()) {
            val newParent = neighbor(parent, latDir, lonDir) ?: return null
            newParent + BASE32[neighborChars.indexOf(lastChar)]
        } else {
            parent + BASE32[neighborChars.indexOf(lastChar)]
        }
    }
}

/**
 * A geographic bounding box.
 */
public data class GeoBoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    val centerLat: Double get() = (minLat + maxLat) / 2
    val centerLon: Double get() = (minLon + maxLon) / 2
}
