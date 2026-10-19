package com.lightningkite.services.database.path

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import kotlinx.serialization.KSerializer

/**
 * Returns an optic that focuses on the target of [other] within the target of this optic.
 *
 * The result is a [Lens] whenever both sides are lenses at runtime, even if they are statically typed as optics.
 */
@Suppress("UNCHECKED_CAST")
public operator fun <A, B, C> Optic<A, B>.plus(other: Optic<B, C>): Optic<A, C> = when {
    this is IdentityLens<*> -> other as Optic<A, C>
    other is IdentityLens<*> -> this as Optic<A, C>
    // Claude: regroup a + (b + c) as (a + b) + c, so equal paths are equal however they were composed.
    other is ComposedOptic<*, *, *> -> {
        val composed = other as ComposedOptic<B, Any?, C>
        this + composed.outer + composed.inner
    }
    this is Lens<A, B> && other is Lens<B, C> -> ComposedLens(this, other)
    else -> ComposedOptic(this, other)
}

/** Returns a lens that focuses on the target of [other] within the target of this lens. */
@Suppress("UNCHECKED_CAST")
public operator fun <A, B, C> Lens<A, B>.plus(other: Lens<B, C>): Lens<A, C> =
    (this as Optic<A, B>).plus(other) as Lens<A, C>

/** An optic that focuses on the target of [inner] within the target of [outer]. Create one with [plus]. */
public open class ComposedOptic<A, B, C> internal constructor(
    public open val outer: Optic<A, B>,
    public open val inner: Optic<B, C>,
) : Optic<A, C> {
    override val serializer: KSerializer<C> get() = inner.serializer
    override fun get(key: A): C? = outer.get(key)?.let(inner::get)
    override fun getOrThrow(key: A): C = inner.getOrThrow(outer.getOrThrow(key))
    override fun set(key: A, value: C): A = outer.get(key)?.let { outer.set(key, inner.set(it, value)) } ?: key
    override fun mapCondition(condition: Condition<C>): Condition<A> = outer.mapCondition(inner.mapCondition(condition))
    override fun mapModification(modification: Modification<C>): Modification<A> =
        outer.mapModification(inner.mapModification(modification))

    override fun toString(): String = "$outer + $inner"
    override fun equals(other: Any?): Boolean =
        other is ComposedOptic<*, *, *> && other.outer == outer && other.inner == inner

    override fun hashCode(): Int = outer.hashCode() * 31 + inner.hashCode()
}

/** A [ComposedOptic] of two lenses, which is itself a lens. Create one with [plus]. */
public class ComposedLens<A, B, C> internal constructor(
    override val outer: Lens<A, B>,
    override val inner: Lens<B, C>,
) : ComposedOptic<A, B, C>(outer, inner), Lens<A, C> {
    override fun get(key: A): C = inner.get(outer.get(key))
    override fun getOrThrow(key: A): C = get(key)
    override fun set(key: A, value: C): A = outer.set(key, inner.set(outer.get(key), value))
}
