package com.lightningkite.services.database.path

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.SerializableProperty
import kotlinx.serialization.KSerializer

public interface Optic<K, V> {
    public val serializer: KSerializer<V>

    public fun get(key: K): V?
    public fun getOrThrow(key: K): V

    public fun set(key: K, value: V): K

    public fun mapCondition(condition: Condition<V>): Condition<K>
    public fun mapModification(modification: Modification<V>): Modification<K>
}

public interface Lens<K, V> : Optic<K, V> {
    override fun get(key: K): V
    override fun getOrThrow(key: K): V = get(key)
}

public class IdentityLens<T>(override val serializer: KSerializer<T>) : Lens<T, T> {
    override fun get(key: T): T = key
    override fun set(key: T, value: T): T = value
    override fun mapCondition(condition: Condition<T>): Condition<T> = condition
    override fun mapModification(modification: Modification<T>): Modification<T> = modification
    override fun toString(): String = "this"
    override fun equals(other: Any?): Boolean =
        other is IdentityLens<*> && this.serializer.descriptor == other.serializer.descriptor
    override fun hashCode(): Int = 0
}
