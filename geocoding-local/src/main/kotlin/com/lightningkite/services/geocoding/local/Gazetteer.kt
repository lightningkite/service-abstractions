package com.lightningkite.services.geocoding.local

import com.lightningkite.services.geocoding.normalizedForGeocoding
import java.util.zip.GZIPInputStream

/**
 * The bundled US Census Gazetteer, decoded into flat arrays.
 *
 * Names live in one concatenated string with an offset table rather than in an
 * `Array<String>`. Two arrays of 32,000 short strings cost several megabytes in object
 * headers alone; the blob form holds the same data in about one, which matters because
 * this is the implementation people reach for when they want geocoding to be free.
 *
 * Coordinates are stored as integer milli-degrees (~111 m). Every coordinate here is
 * the centroid of a ZIP or place *area*, so finer precision would be encoding noise.
 *
 * See `scripts/pack-gazetteer.py` for the writer and the format description.
 */
internal class Gazetteer(
    val vintage: Int,
    private val states: Array<String>,
    private val designations: Array<String>,
    private val nameBlob: String,
    private val nameStart: IntArray,
    private val stateIndex: ByteArray,
    private val designationIndex: ByteArray,
    val latitude: IntArray,
    val longitude: IntArray,
    /** Place indices ordered by normalized name; parallel to [sortedNameStart]. */
    private val order: IntArray,
    private val sortedNameBlob: String,
    private val sortedNameStart: IntArray,
    private val aliasNames: Array<String>,
    private val aliasTargets: IntArray,
    private val zips: IntArray,
    val zipLatitude: IntArray,
    val zipLongitude: IntArray,
) {
    val placeCount: Int get() = latitude.size
    val zipCount: Int get() = zips.size

    fun placeName(index: Int): String = nameBlob.substring(nameStart[index], nameStart[index + 1])
    fun placeState(index: Int): String = states[stateIndex[index].toInt() and 0xFF]

    /** The Census designation, e.g. `"city"`, `"town"`, `"CDP"`. Empty for consolidated governments. */
    fun placeDesignation(index: Int): String = designations[designationIndex[index].toInt() and 0xFF]

    fun zipCode(index: Int): String = zips[index].toString().padStart(5, '0')

    private fun sortedName(position: Int): String =
        sortedNameBlob.substring(sortedNameStart[position], sortedNameStart[position + 1])

    /**
     * Place indices whose normalized name is exactly [normalized], plus any that a search
     * alias resolves to.
     *
     * Aliases are checked linearly because there are only a dozen of them — the
     * consolidated city-counties like "Augusta" for "Augusta-Richmond County".
     */
    fun placesNamed(normalized: String): List<Int> {
        val result = mutableListOf<Int>()
        var lo = lowerBound(normalized)
        while (lo < order.size && sortedName(lo) == normalized) {
            result.add(order[lo])
            lo++
        }
        for (i in aliasNames.indices) {
            if (aliasNames[i].normalizedForGeocoding() == normalized) result.add(aliasTargets[i])
        }
        return result
    }

    /** Place indices whose normalized name starts with [prefix], in name order. */
    fun placesStartingWith(prefix: String, limit: Int): List<Int> {
        val result = mutableListOf<Int>()
        var lo = lowerBound(prefix)
        while (lo < order.size && result.size < limit && sortedName(lo).startsWith(prefix)) {
            result.add(order[lo])
            lo++
        }
        for (i in aliasNames.indices) {
            if (result.size >= limit) break
            if (aliasNames[i].normalizedForGeocoding().startsWith(prefix)) result.add(aliasTargets[i])
        }
        return result
    }

    /** First position in name order whose name is >= [normalized]. */
    private fun lowerBound(normalized: String): Int {
        var low = 0
        var high = order.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedName(mid) < normalized) low = mid + 1 else high = mid
        }
        return low
    }

    /** Index into the ZIP arrays, or -1 if the code is not a ZCTA. */
    fun zipIndex(code: String): Int {
        val numeric = code.toIntOrNull() ?: return -1
        var low = 0
        var high = zips.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                zips[mid] < numeric -> low = mid + 1
                zips[mid] > numeric -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    internal companion object {
        const val RESOURCE: String = "us-gazetteer.bin.gz"
        private val MAGIC = "LKGEO".toByteArray(Charsets.US_ASCII)
        private const val FORMAT_VERSION = 1

        fun load(): Gazetteer {
            val bytes = (Gazetteer::class.java.getResourceAsStream(RESOURCE)
                ?: throw IllegalStateException(
                    "Bundled gazetteer $RESOURCE is missing from the geocoding-local jar"
                )).use { GZIPInputStream(it).readBytes() }
            return decode(Reader(bytes))
        }

        private fun decode(r: Reader): Gazetteer {
            require(r.bytes(MAGIC.size).contentEquals(MAGIC)) { "Bundled gazetteer has a bad header" }
            val version = r.u8()
            require(version == FORMAT_VERSION) {
                "Bundled gazetteer is format version $version, but this build reads $FORMAT_VERSION"
            }
            val vintage = r.varint()

            val states = Array(r.varint()) { String(r.bytes(2), Charsets.US_ASCII) }
            val designations = Array(r.varint()) { r.string(r.varint()) }

            val count = r.varint()
            val nameBlob = StringBuilder()
            val nameStart = IntArray(count + 1)
            val stateIndex = ByteArray(count)
            val designationIndex = ByteArray(count)
            val latitude = IntArray(count)
            val longitude = IntArray(count)
            var previousName = ""
            var lat = 0
            var lon = 0
            for (i in 0 until count) {
                val shared = r.varint()
                val name = previousName.substring(0, shared) + r.string(r.varint())
                nameStart[i] = nameBlob.length
                nameBlob.append(name)
                previousName = name
                stateIndex[i] = r.u8().toByte()
                designationIndex[i] = r.u8().toByte()
                lat += r.svarint()
                lon += r.svarint()
                latitude[i] = lat
                longitude[i] = lon
            }
            nameStart[count] = nameBlob.length

            val aliasCount = r.varint()
            val aliasNames = Array(aliasCount) { "" }
            val aliasTargets = IntArray(aliasCount)
            for (i in 0 until aliasCount) {
                aliasNames[i] = r.string(r.varint())
                aliasTargets[i] = r.varint()
            }

            val zipCount = r.varint()
            val zips = IntArray(zipCount)
            val zipLatitude = IntArray(zipCount)
            val zipLongitude = IntArray(zipCount)
            var zip = 0
            lat = 0
            lon = 0
            for (i in 0 until zipCount) {
                zip += r.varint()
                lat += r.svarint()
                lon += r.svarint()
                zips[i] = zip
                zipLatitude[i] = lat
                zipLongitude[i] = lon
            }
            check(r.exhausted) { "Bundled gazetteer has ${r.remaining} unread trailing bytes" }

            // The name index is built here rather than trusted from the file, so the packer's
            // sort order never has to agree with this build's normalization rules. The packer
            // sorts only to make front-coding compress well.
            val fullNames = nameBlob.toString()
            val normalized = Array(count) {
                fullNames.substring(nameStart[it], nameStart[it + 1]).normalizedForGeocoding()
            }
            // Ties break toward incorporated places: a CDP sharing a name with a real city is
            // almost always the smaller of the two, and there is no population data here to
            // rank them properly.
            val order = (0 until count).sortedWith(
                compareBy({ normalized[it] }, { if (designations[designationIndex[it].toInt() and 0xFF] == "CDP") 1 else 0 }, { it })
            ).toIntArray()
            val sortedNameBlob = StringBuilder()
            val sortedNameStart = IntArray(count + 1)
            for (i in order.indices) {
                sortedNameStart[i] = sortedNameBlob.length
                sortedNameBlob.append(normalized[order[i]])
            }
            sortedNameStart[count] = sortedNameBlob.length

            return Gazetteer(
                vintage = vintage,
                states = states,
                designations = designations,
                nameBlob = fullNames,
                nameStart = nameStart,
                stateIndex = stateIndex,
                designationIndex = designationIndex,
                latitude = latitude,
                longitude = longitude,
                order = order,
                sortedNameBlob = sortedNameBlob.toString(),
                sortedNameStart = sortedNameStart,
                aliasNames = aliasNames,
                aliasTargets = aliasTargets,
                zips = zips,
                zipLatitude = zipLatitude,
                zipLongitude = zipLongitude,
            )
        }
    }

    /** Sequential cursor over the decompressed bundle. */
    private class Reader(private val data: ByteArray) {
        private var position = 0
        val exhausted: Boolean get() = position == data.size
        val remaining: Int get() = data.size - position

        fun u8(): Int = data[position++].toInt() and 0xFF

        fun bytes(count: Int): ByteArray =
            data.copyOfRange(position, position + count).also { position += count }

        fun string(byteCount: Int): String =
            String(data, position, byteCount, Charsets.UTF_8).also { position += byteCount }

        fun varint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = u8()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun svarint(): Int {
            val n = varint()
            return (n ushr 1) xor -(n and 1)
        }
    }
}
