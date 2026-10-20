package com.lightningkite.services.database.path

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import kotlinx.serialization.KSerializer

/** A [Lens] that focuses on the whole value. It serializes as `this`. */
public class IdentityLens<T>(override val serializer: KSerializer<T>) : Lens<T, T> {
    override fun get(source: T): T = source
    override fun set(source: T, value: T): T = value
    override fun mapCondition(condition: Condition<T>): Condition<T> = condition
    override fun mapModification(modification: Modification<T>): Modification<T> = modification

    override val fields: List<Field<*, *>> get() = listOf()

    override fun toString(): String = "this"
    override fun equals(other: Any?): Boolean =
        other is IdentityLens<*> && this.serializer.descriptor == other.serializer.descriptor
    override fun hashCode(): Int = serializer.descriptor.hashCode()
}

