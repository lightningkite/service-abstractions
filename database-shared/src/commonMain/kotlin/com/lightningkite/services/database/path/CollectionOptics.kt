package com.lightningkite.services.database.path

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.listElement
import kotlinx.serialization.KSerializer
import kotlin.jvm.JvmName

public abstract class CollectionElementsOptic<A, T, COLLECTION: Collection<T>>(
    public val wraps: Optic<A, COLLECTION>
): Optic<A, T> {
    override val serializer: KSerializer<T>
        @Suppress("UNCHECKED_CAST")
        get() = wraps.serializer.listElement()!! as KSerializer<T>

    override val properties: List<SerializableProperty<*, *>>
        get() = wraps.properties

    override fun get(source: A): T? = wraps.get(source)?.firstOrNull()
    override fun getOrThrow(source: A): T = wraps.getOrThrow(source).first()

    protected abstract fun collectionOf(value: T): COLLECTION

    override fun set(source: A, value: T): A = wraps.set(source, collectionOf(value))

    override fun mapCondition(condition: Condition<T>): Condition<A> =
        wraps.mapCondition(Condition.CollectionAllElements(condition))

    override fun toString(): String = "$wraps.*"
    override fun equals(other: Any?): Boolean =
        other is CollectionElementsOptic<*, *, *> && other::class == this::class && other.wraps == wraps

    override fun hashCode(): Int = wraps.hashCode() * 31 + 1
}

public class SetElementsOptic<A, T>(
    wraps: Optic<A, Set<T>>
): Optic<A, T>, CollectionElementsOptic<A, T, Set<T>>(wraps) {
    override fun collectionOf(value: T): Set<T> = setOf(value)

    override fun mapModification(modification: Modification<T>): Modification<A> =
        wraps.mapModification(Modification.SetPerElement(Condition.Always, modification))
}

public class ListElementsOptic<A, T>(
    wraps: Optic<A, List<T>>
): Optic<A, T>, CollectionElementsOptic<A, T, List<T>>(wraps) {
    override fun collectionOf(value: T): List<T> = listOf(value)

    override fun mapModification(modification: Modification<T>): Modification<A> =
        wraps.mapModification(Modification.ListPerElement(Condition.Always, modification))
}

@get:JvmName("getListElements")
public val <K, V> Optic<K, List<V>>.elements: ListElementsOptic<K, V>
    get() = ListElementsOptic(this)

@get:JvmName("getSetElements")
public val <K, V> Optic<K, Set<V>>.elements: SetElementsOptic<K, V>
    get() = SetElementsOptic(this)
