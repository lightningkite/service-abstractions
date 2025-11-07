package com.lightningkite

import com.lightningkite.Length.Companion.miles
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.DoubleArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*
import kotlin.jvm.JvmInline
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Represents a geographic coordinate with latitude and longitude.
 *
 * By default, serializes to GeoJSON Point format: `{"type":"Point","coordinates":[lon,lat]}`.
 * Use [GeoCoordinateArraySerializer] for simple `[lon, lat]` array format.
 *
 * ## Distance Calculations
 *
 * Provides haversine formula-based distance calculation:
 * ```kotlin
 * val sf = GeoCoordinate(37.7749, -122.4194)
 * val la = GeoCoordinate(34.0522, -118.2437)
 * val distance = sf distanceTo la  // Returns Length
 * val km = sf distanceToKilometers la
 * val mi = sf distanceToMiles la
 * ```
 *
 * @property latitude Latitude in degrees (-90 to 90, negative is South)
 * @property longitude Longitude in degrees (-180 to 180, negative is West)
 */
@Serializable(GeoCoordinateGeoJsonSerializer::class)
public data class GeoCoordinate(public val latitude: Double, public val longitude: Double) {
    /**
     * Calculates great-circle distance to another coordinate in kilometers.
     *
     * @param other Destination coordinate
     * @return Distance in kilometers
     */
    public infix fun distanceToKilometers(other: GeoCoordinate): Double = distanceTo(other).kilometers

    /**
     * Calculates great-circle distance to another coordinate in miles.
     *
     * @param other Destination coordinate
     * @return Distance in miles
     */
    public infix fun distanceToMiles(other: GeoCoordinate): Double = distanceTo(other).miles

    /**
     * Calculates great-circle distance to another coordinate.
     *
     * Uses the haversine formula to calculate the shortest distance over Earth's surface.
     * Assumes Earth is a perfect sphere with radius at the equator.
     *
     * @param other Destination coordinate
     * @return Distance as a [Length] value
     */
    public infix fun distanceTo(other: GeoCoordinate): Length {
        val theta: Double = this.longitude - other.longitude
        var dist: Double = (sin(deg2rad(this.latitude))
                * sin(deg2rad(other.latitude))
                + (cos(deg2rad(this.latitude))
                * cos(deg2rad(other.latitude))
                * cos(deg2rad(theta))))
        // Coerce to [-1.0, 1.0] to handle floating-point precision errors
        // (e.g., 1.00000000000002 instead of 1.0) which would cause acos to return NaN
        dist = acos(dist.coerceIn(-1.0, 1.0))
        dist = rad2deg(dist)
        dist *= 60 * 1.1515
        return dist.miles
    }
}

/**
 * Serializer for [GeoCoordinate] using GeoJSON Point format.
 *
 * Produces JSON: `{"type":"Point","coordinates":[longitude,latitude]}`
 *
 * Note: GeoJSON specification puts longitude first, then latitude (opposite of the constructor).
 */
public object GeoCoordinateGeoJsonSerializer: KSerializer<GeoCoordinate> {

    private val das = DoubleArraySerializer()

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.lightningkite.GeoCoordinate/geojson") {
        element("type", String.serializer().descriptor)
        element("coordinates", das.descriptor)
    }

    override fun deserialize(decoder: Decoder): GeoCoordinate {
        return decoder.decodeStructure(descriptor) {
            var lat = 0.0
            var lon = 0.0
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> decodeStringElement(descriptor, 0)
                    1 -> decodeSerializableElement(descriptor, 1, das).let {
                        lat = it[1]
                        lon = it[0]
                    }
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected index: $index")
                }
            }
            GeoCoordinate(lat, lon)
        }
    }

    override fun serialize(encoder: Encoder, value: GeoCoordinate) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, "Point")
            encodeSerializableElement(descriptor, 1, das, doubleArrayOf(value.longitude, value.latitude))
        }
    }

}

/**
 * Serializer for [GeoCoordinate] using simple array format.
 *
 * Produces JSON: `[longitude, latitude]`
 *
 * Note: Like GeoJSON, this puts longitude first, then latitude.
 *
 * ## Usage
 * ```kotlin
 * @Serializable
 * data class Location(
 *     @Serializable(with = GeoCoordinateArraySerializer::class)
 *     val coords: GeoCoordinate
 * )
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
public object GeoCoordinateArraySerializer: KSerializer<GeoCoordinate> {
    private val delegate = DoubleArraySerializer()

    override val descriptor: SerialDescriptor = SerialDescriptor("com.lightningkite.GeoCoordinate/longLatArray", delegate.descriptor)

    override fun deserialize(decoder: Decoder): GeoCoordinate {
        return decoder.decodeSerializableValue(delegate).let { GeoCoordinate(longitude = it[0], latitude = it[1]) }
    }

    override fun serialize(encoder: Encoder, value: GeoCoordinate) {
        encoder.encodeSerializableValue(delegate, doubleArrayOf(value.longitude, value.latitude))
    }

}

/** Converts degrees to radians. */
private fun deg2rad(deg: Double): Double {
    return deg * PI / 180.0
}

/** Converts radians to degrees. */
private fun rad2deg(rad: Double): Double {
    return rad * 180.0 / PI
}
