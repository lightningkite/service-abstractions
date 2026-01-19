// by Claude
package com.lightningkite.services.database.mapformat

import kotlinx.serialization.KSerializer

/**
 * A serialization format that converts Kotlin objects to/from Map<String, Any?>.
 *
 * Features:
 * - Proper value class (inline class) support via encodeInline/decodeInline
 * - Embedded structs are flattened with configurable separator (default: __)
 * - Pluggable type conversion via ValueConverters
 * - Pluggable collection handling via CollectionHandler
 *
 * Example usage:
 * ```kotlin
 * val format = MapFormat(MapFormatConfig(
 *     serializersModule = myModule,
 *     converters = myConverters,
 *     collectionHandler = ArrayListCollectionHandler(config),
 * ))
 *
 * // Encode
 * val result = format.encode(User.serializer(), user)
 * val map = result.mainRecord // Map<String, Any?>
 *
 * // Decode
 * val user = format.decode(User.serializer(), map)
 * ```
 */
public class MapFormat(public val config: MapFormatConfig) {

    /**
     * Serialize an object to a WriteResult containing the main record and any child records.
     */
    public fun <T> encode(serializer: KSerializer<T>, value: T): WriteResult {
        val target = SimpleWriteTarget()

        // Check for top-level converter (handles types like Uuid that serialize as primitives) - by Claude
        config.converters.get(serializer.descriptor)?.let { _ ->
            val converted = if (value == null) null else config.converters.toDatabase(serializer.descriptor, value as Any)
            target.writeField("", converted)
            return target.result()
        }

        val encoder = MapEncoder(config, target)
        serializer.serialize(encoder, value)
        return target.result()
    }

    /**
     * Deserialize an object from a ReadSource.
     */
    public fun <T> decode(serializer: KSerializer<T>, source: ReadSource): T {
        // Check for top-level converter (handles types like Uuid that serialize as primitives) - by Claude
        config.converters.get(serializer.descriptor)?.let { _ ->
            val dbValue = source.readField("")
            @Suppress("UNCHECKED_CAST")
            return if (dbValue == null) null as T else config.converters.fromDatabase(serializer.descriptor, dbValue as Any) as T
        }

        val decoder = MapDecoder(config, source, serializer.descriptor)
        return serializer.deserialize(decoder)
    }

    /**
     * Deserialize an object from a simple map (convenience method).
     */
    public fun <T> decode(serializer: KSerializer<T>, map: Map<String, Any?>): T {
        return decode(serializer, SimpleReadSource(map, emptyMap(), config.fieldSeparator))
    }

    /**
     * Deserialize an object from a WriteResult (useful for round-trip testing).
     */
    public fun <T> decode(serializer: KSerializer<T>, result: WriteResult): T {
        return decode(serializer, SimpleReadSource(result.mainRecord, result.children, config.fieldSeparator))
    }

    public companion object {
        /**
         * Create a simple MapFormat with default settings and ArrayListCollectionHandler.
         */
        public fun simple(): MapFormat {
            val config = MapFormatConfig(
                collectionHandler = ArrayListCollectionHandler(
                    MapFormatConfig(
                        collectionHandler = object : CollectionHandler {
                            override fun createListEncoder(fieldPath: String, elementDescriptor: kotlinx.serialization.descriptors.SerialDescriptor, output: WriteTarget) =
                                throw UnsupportedOperationException("Nested collections not supported in simple mode")
                            override fun createListDecoder(fieldPath: String, elementDescriptor: kotlinx.serialization.descriptors.SerialDescriptor, input: ReadSource) =
                                throw UnsupportedOperationException("Nested collections not supported in simple mode")
                            override fun createMapEncoder(fieldPath: String, keyDescriptor: kotlinx.serialization.descriptors.SerialDescriptor, valueDescriptor: kotlinx.serialization.descriptors.SerialDescriptor, output: WriteTarget) =
                                throw UnsupportedOperationException("Nested collections not supported in simple mode")
                            override fun createMapDecoder(fieldPath: String, keyDescriptor: kotlinx.serialization.descriptors.SerialDescriptor, valueDescriptor: kotlinx.serialization.descriptors.SerialDescriptor, input: ReadSource) =
                                throw UnsupportedOperationException("Nested collections not supported in simple mode")
                        }
                    )
                )
            )
            return MapFormat(config)
        }
    }
}
