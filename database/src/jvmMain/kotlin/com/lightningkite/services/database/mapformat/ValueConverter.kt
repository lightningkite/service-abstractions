// by Claude
@file:Suppress("OPT_IN_USAGE")

package com.lightningkite.services.database.mapformat

import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * Converts between Kotlin serialization values and database-native values.
 *
 * Database layers provide implementations for types they handle specially.
 * For example, Postgres might convert Uuid to java.util.UUID, while
 * Cassandra converts it to its own UUID type.
 */
public interface ValueConverter<K : Any, D : Any> {
    /**
     * The SerialDescriptor this converter handles.
     * Used for lookup during serialization.
     */
    public val descriptor: SerialDescriptor

    /**
     * Convert from Kotlin value to database-native value.
     */
    public fun toDatabase(value: K): D

    /**
     * Convert from database-native value back to Kotlin.
     */
    public fun fromDatabase(value: D): K
}

/**
 * Registry of value converters, keyed by serial name.
 * Database layers create these with their specific converters.
 */
public class ValueConverterRegistry(
    converters: List<ValueConverter<*, *>> = emptyList()
) {
    private val bySerialName: Map<String, ValueConverter<*, *>> =
        converters.associateBy { it.descriptor.serialName.removeSuffix("?") }

    public fun has(descriptor: SerialDescriptor): Boolean =
        bySerialName.containsKey(descriptor.serialName.removeSuffix("?"))

    public fun get(descriptor: SerialDescriptor): ValueConverter<*, *>? =
        bySerialName[descriptor.serialName.removeSuffix("?")]

    @Suppress("UNCHECKED_CAST")
    public fun <K : Any> toDatabase(descriptor: SerialDescriptor, value: K): Any {
        val conv = get(descriptor) as? ValueConverter<K, Any> ?: return value
        return conv.toDatabase(value)
    }

    @Suppress("UNCHECKED_CAST")
    public fun <K : Any> fromDatabase(descriptor: SerialDescriptor, dbValue: Any): K {
        val conv = get(descriptor) as? ValueConverter<K, Any> ?: return dbValue as K
        return conv.fromDatabase(dbValue)
    }

    public companion object {
        public val EMPTY: ValueConverterRegistry = ValueConverterRegistry()
    }
}
