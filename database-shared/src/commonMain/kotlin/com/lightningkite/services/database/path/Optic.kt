package com.lightningkite.services.database.path

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * An [Optic] on [T] whose target type is unknown, for storing or serializing paths to any type.
 *
 * Every [OpticOn] is an [Optic]; use [coerceToOptic] to get it back as one.
 */
@Serializable(OpticOnSerializer::class)
public sealed interface OpticOn<T> {
    /** Returns the target of this optic within [source], or `null` if it is not present. */
    public fun get(source: T): Any?

    /**
     * The fields this optic passes through, from the outermost to the innermost.
     *
     * Steps that aren't fields, such as [NotNullOptic], add no properties, so an [IdentityLens] has none.
     */
    public val fields: List<Field<*, *>>
}

/** Returns this as an [Optic] with an unknown target type. */
public fun <T> OpticOn<T>.coerceToOptic(): Optic<T, *> =
    when (this) {
        is Optic<T, *> -> this
    }

/**
 * A serializable reference to a part [B] of a value [A], which may not be present.
 *
 * Optics compose with [plus]: `User.address + Address.city` focuses on a user's city.
 * See [Lens] for optics whose target is always present.
 */
@Serializable(OpticSerializer::class)
public interface Optic<A, B> : OpticOn<A> {
    public val serializer: KSerializer<B>

    /** Returns the target of this optic within [source], or `null` if it is not present. */
    override fun get(source: A): B?

    /** Returns the target of this optic within [source], throwing if it is not present. */
    public fun getOrThrow(source: A): B

    /** Returns a copy of [source] with the target replaced by [value], or [source] unchanged if the target is not present. */
    public fun set(source: A, value: B): A

    /** Returns a condition on [A] that holds when [condition] holds for the target. */
    public fun mapCondition(condition: Condition<B>): Condition<A>

    /** Returns a modification of [A] that applies [modification] to the target. */
    public fun mapModification(modification: Modification<B>): Modification<A>

    override fun toString(): String

    /**
     * Returns an optic that focuses on the target of [other] within the target of this optic.
     *
     * The result is a [Lens] whenever both sides are lenses at runtime, even if they are statically typed as optics.
     */
    @Suppress("UNCHECKED_CAST")
    public operator fun <C> plus(other: Optic<B, C>): Optic<A, C> = when {
        this is IdentityLens<*> -> other as Optic<A, C>
        other is IdentityLens<*> -> this as Optic<A, C>
        // Claude: regroup a + (b + c) as (a + b) + c, so equal paths are equal however they were composed.
        other is ComposedOptic<B, *, C> -> this + other.outer + other.inner
        this is Lens<A, B> && other is Lens<B, C> -> ComposedLens(this, other)
        else -> ComposedOptic(this, other)
    }
}

/** An [Optic] whose target is always present, so [get] never fails. */
public interface Lens<A, B> : Optic<A, B> {
    override fun get(source: A): B
    override fun getOrThrow(source: A): B = get(source)

    /** Returns a lens that focuses on the target of [other] within the target of this lens. */
    @Suppress("UNCHECKED_CAST")
    public operator fun <C> plus(other: Lens<B, C>): Lens<A, C> =
        (this as Optic<A, B>).plus(other) as Lens<A, C>
}
