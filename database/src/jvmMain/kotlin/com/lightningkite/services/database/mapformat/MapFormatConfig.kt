// by Claude
package com.lightningkite.services.database.mapformat

import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Configuration for the map format.
 * Intentionally minimal - database-specific concerns go in database layers.
 */
public class MapFormatConfig(
    /** SerializersModule for contextual serializers */
    public val serializersModule: SerializersModule = EmptySerializersModule(),

    /** Value converters for type mapping */
    public val converters: ValueConverterRegistry = ValueConverterRegistry.EMPTY,

    /** How to handle collections */
    public val collectionHandler: CollectionHandler,

    /** Separator for embedded struct field paths: "address__city" */
    public val fieldSeparator: String = "__",

    /**
     * by Claude - When true, embedded objects inside collections (List/Set) are serialized as JSON strings
     * instead of nested Maps. This is needed for databases like Cassandra where list<text>
     * columns store embedded objects as JSON text.
     */
    public val serializeCollectionEmbeddedAsJson: Boolean = false,
)
