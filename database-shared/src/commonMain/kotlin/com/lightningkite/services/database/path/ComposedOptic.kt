package com.lightningkite.services.database.path

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import kotlinx.serialization.KSerializer

/** An optic that focuses on the target of [inner] within the target of [outer]. Create one with [plus]. */
public open class ComposedOptic<A, B, C> internal constructor(
    public open val outer: Optic<A, B>,
    public open val inner: Optic<B, C>,
) : Optic<A, C> {
    override val serializer: KSerializer<C> get() = inner.serializer
    override val fields: List<Field<*, *>> by lazy {
        outer.fields + inner.fields
    }
    override fun get(source: A): C? = outer.get(source)?.let(inner::get)
    override fun getOrThrow(source: A): C = inner.getOrThrow(outer.getOrThrow(source))
    override fun set(source: A, value: C): A = outer.get(source)?.let { outer.set(source, inner.set(it, value)) } ?: source
    override fun mapCondition(condition: Condition<C>): Condition<A> = outer.mapCondition(inner.mapCondition(condition))
    override fun mapModification(modification: Modification<C>): Modification<A> =
        outer.mapModification(inner.mapModification(modification))

    override fun toString(): String = "$outer.$inner"
    override fun equals(other: Any?): Boolean =
        other is ComposedOptic<*, *, *> && other.outer == outer && other.inner == inner

    override fun hashCode(): Int = outer.hashCode() * 31 + inner.hashCode()
}

/** A [ComposedOptic] of two lenses, which is itself a lens. Create one with [plus]. */
public class ComposedLens<A, B, C> internal constructor(
    override val outer: Lens<A, B>,
    override val inner: Lens<B, C>,
) : ComposedOptic<A, B, C>(outer, inner), Lens<A, C> {
    override fun get(source: A): C = inner.get(outer.get(source))
    override fun getOrThrow(source: A): C = get(source)
    override fun set(source: A, value: C): A = outer.set(source, inner.set(outer.get(source), value))
}
