package com.lightningkite.services.database

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Identifies a single table within a [Database]: what type it holds ([serializer]) and what it is
 * called ([name]).
 *
 * [name] is the table's identity — two definitions with the same name refer to the same underlying
 * table, and backends use it as their cache/lookup key. It must be provided explicitly to avoid
 * silent collisions between like-named types from different packages. Use the reified
 * `DatabaseTableDefinition<T>()` factory below when you want the name defaulted from the type.
 */
public class DatabaseTableDefinition<T>(
    public val serializer: KSerializer<T>,
    public val name: String,
) {
    // Equality is defined by hand over stable strings rather than the data-class default, because
    // KSerializer does not provide reliable equals/hashCode and these definitions are used as map keys.
    override fun equals(other: Any?): Boolean =
        other is DatabaseTableDefinition<*> &&
                other.name == name &&
                other.serializer.descriptor == serializer.descriptor

    override fun hashCode(): Int = name.hashCode() + serializer.descriptor.hashCode()

    override fun toString(): String = "DatabaseTableDefinition($name)"
}

/**
 * Creates a [DatabaseTableDefinition] for [T], defaulting the table [name] to the type's simple name.
 *
 * Mirrors the convenience of the older `Database.table<T>(name)` helper; prefer the explicit-name
 * constructor when two types could share a simple name.
 */
public inline fun <reified T : Any> DatabaseTableDefinition(
    name: String = T::class.simpleName!!,
): DatabaseTableDefinition<T> = DatabaseTableDefinition(serializer<T>(), name)
