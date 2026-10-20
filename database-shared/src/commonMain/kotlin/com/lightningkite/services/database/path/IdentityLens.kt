package com.lightningkite.services.database.path

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** A [Lens] that focuses on the whole value. It serializes as `this`. */
public class IdentityLens<T>(override val serializer: KSerializer<T>) : Lens<T, T> {
    override fun get(key: T): T = key
    override fun set(key: T, value: T): T = value
    override fun mapCondition(condition: Condition<T>): Condition<T> = condition
    override fun mapModification(modification: Modification<T>): Modification<T> = modification

    override fun toString(): String = "this"
    override fun equals(other: Any?): Boolean =
        other is IdentityLens<*> && this.serializer.descriptor == other.serializer.descriptor
    override fun hashCode(): Int = serializer.descriptor.hashCode()
}

/** Returns an [IdentityLens] on [T], the starting point for building a path with [plus]. */
public fun <T> pathOf(serializer: KSerializer<T>): IdentityLens<T> = IdentityLens(serializer)
/** Returns an [IdentityLens] on [T], the starting point for building a path with [plus]. */
public inline fun <reified T> pathOf(): IdentityLens<T> = IdentityLens(serializer())