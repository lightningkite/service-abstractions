// by Claude
package com.lightningkite.services.database.mapformat

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder

/**
 * Handles how collections (List, Set, Map) are serialized.
 *
 * Different databases need different approaches:
 * - Postgres: array columns
 * - Cassandra: native list/set/map types
 * - Generic SQL: child tables (future)
 * - Fallback: JSON string
 *
 * Database layers provide implementations for their storage strategy.
 */
public interface CollectionHandler {
    /**
     * Create an encoder for a List/Set field.
     *
     * @param fieldPath The path to this field (e.g., "tags" or "address__phones")
     * @param elementDescriptor Descriptor of list elements
     * @param output Where to write the results
     * @return A CompositeEncoder that will receive the list elements
     */
    public fun createListEncoder(
        fieldPath: String,
        elementDescriptor: SerialDescriptor,
        output: WriteTarget,
    ): CompositeEncoder

    /**
     * Create a decoder for a List/Set field.
     */
    public fun createListDecoder(
        fieldPath: String,
        elementDescriptor: SerialDescriptor,
        input: ReadSource,
    ): CompositeDecoder

    /**
     * Create an encoder for a Map field.
     */
    public fun createMapEncoder(
        fieldPath: String,
        keyDescriptor: SerialDescriptor,
        valueDescriptor: SerialDescriptor,
        output: WriteTarget,
    ): CompositeEncoder

    /**
     * Create a decoder for a Map field.
     */
    public fun createMapDecoder(
        fieldPath: String,
        keyDescriptor: SerialDescriptor,
        valueDescriptor: SerialDescriptor,
        input: ReadSource,
    ): CompositeDecoder
}
