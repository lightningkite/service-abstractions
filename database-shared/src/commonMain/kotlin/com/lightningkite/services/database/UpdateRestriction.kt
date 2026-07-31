package com.lightningkite.services.database

import kotlinx.serialization.Serializable

/**
 * A restriction on database updates, expressed as a boolean formula over the *pair* of the record being updated
 * and the [Modification] about to be applied to it -- conceptually a `Condition<Pair<T, Modification<T>>>`.
 *
 * At check time the modification is known and the record is not, so [invoke] *partially applies* the formula:
 * leaves that talk about the modification collapse to true/false immediately, and what survives is an ordinary
 * [Condition] on the existing record -- exactly the filter an `updateMany` needs.
 *
 * ## Leaves
 * - [OnCurrentItem] talks about the record, so it survives partial application.
 * - [Preserves], [Untouched] and [OnlyTouches] talk about the modification, so they resolve immediately.
 *
 * ## Usage
 * ```kotlin
 * val restriction = updateRestriction<User> { user ->
 *     user.role.cannotBeModified()
 *     user.credits requires (user.role eq Role.Admin)
 *     user.isActive.mustBe { it eq true }
 *     // Admins may set any status; anyone may close their own account
 *     anyOf(
 *         { user.status requires (user.role eq Role.Admin) },
 *         { user.status.mustBe { it eq Status.Closed } },
 *     )
 * }
 *
 * val condition = restriction(modification)
 * table.updateMany(condition = condition and (User.path._id eq userId), modification = modification)
 * ```
 *
 * ## Why there is no `not`
 * [Preserves] is decided by [guaranteedAfter], which is deliberately incomplete -- it answers "no" whenever it
 * cannot *prove* the condition still holds afterward. Under [All] and [AnyOf] that incompleteness always fails
 * closed: a modification the analyzer can't reason about is denied. Negation would turn "could not prove" into
 * "permitted", making every blind spot in the analyzer an authorization hole, so the language omits it. Negate
 * inside [OnCurrentItem] instead, where [Condition] is exact.
 */
@Serializable(UpdateRestrictionSerializer::class)
public sealed class UpdateRestriction<T> {

    public companion object {
        /** Permits every modification -- the identity of [All], and the default for [ModelPermissions]. */
        public fun <T> unrestricted(): UpdateRestriction<T> = All(emptyList())
    }

    /**
     * Partially applies this restriction to [on], returning the condition the *existing* record must satisfy for
     * the modification to be allowed. [Condition.Never] means no record may be modified this way.
     */
    public operator fun invoke(on: Modification<T>): Condition<T> = residual(on).simplify()

    /**
     * The residual before [Condition.simplify] tidies it. [All] and [AnyOf] fold their own [Condition.Always] and
     * [Condition.Never] children as they go rather than leaving that to `simplify`, both because the resolved
     * leaves are genuinely constants at this point and because `simplify` does not reliably drop them: its
     * `reduceOr` has no case for a compound left operand, so `Or(And(..), Never)` survives unreduced.
     */
    internal abstract fun residual(on: Modification<T>): Condition<T>

    /** Every one of [restrictions] must hold. An empty list permits everything. */
    @Serializable
    public data class All<T>(public val restrictions: List<UpdateRestriction<T>>) : UpdateRestriction<T>() {
        override fun residual(on: Modification<T>): Condition<T> {
            val parts = ArrayList<Condition<T>>(restrictions.size)
            for (restriction in restrictions) {
                when (val part = restriction.residual(on)) {
                    Condition.Never -> return Condition.Never
                    Condition.Always -> {}
                    else -> parts.add(part)
                }
            }
            return if (parts.isEmpty()) Condition.Always else Condition.And(parts)
        }
    }

    /** At least one of [restrictions] must hold. An empty list permits nothing. */
    @Serializable
    public data class AnyOf<T>(public val restrictions: List<UpdateRestriction<T>>) : UpdateRestriction<T>() {
        override fun residual(on: Modification<T>): Condition<T> {
            val parts = ArrayList<Condition<T>>(restrictions.size)
            for (restriction in restrictions) {
                when (val part = restriction.residual(on)) {
                    Condition.Always -> return Condition.Always
                    Condition.Never -> {}
                    else -> parts.add(part)
                }
            }
            return if (parts.isEmpty()) Condition.Never else Condition.Or(parts)
        }
    }

    /** The record being modified must match [condition]. This is the only leaf that survives into the result. */
    @Serializable
    public data class OnCurrentItem<T>(public val condition: Condition<T>) : UpdateRestriction<T>() {
        override fun residual(on: Modification<T>): Condition<T> = condition
    }

    /**
     * The modification must not be able to falsify [condition]: either it leaves the fields [condition] reads
     * alone, or the value it writes provably satisfies it. Note that a modification touching nothing relevant
     * satisfies this vacuously -- "you may not *make* this false", not "this must be true afterward".
     */
    @Serializable
    public data class Preserves<T>(public val condition: Condition<T>) : UpdateRestriction<T>() {
        override fun residual(on: Modification<T>): Condition<T> =
            if (condition.guaranteedAfter(on)) Condition.Always else Condition.Never
    }

    /** The modification must not write anything at or below [path]. */
    @Serializable
    public data class Untouched<T>(public val path: DataClassPathPartial<T>) : UpdateRestriction<T>() {
        override fun residual(on: Modification<T>): Condition<T> =
            if (on.affects(path)) Condition.Never else Condition.Always
    }

    /**
     * The modification must write nothing outside [paths] -- every path it touches must be at or below one of
     * them. This is what whitelisting means; note that a path *containing* an allowed path doesn't qualify, since
     * overwriting a parent wholesale also overwrites its other children.
     */
    @Serializable
    public data class OnlyTouches<T>(public val paths: Set<DataClassPathPartial<T>>) : UpdateRestriction<T>() {
        override fun residual(on: Modification<T>): Condition<T> {
            val allowed = paths.map { it.properties }
            val permitted = on.touchedPaths().all { touched -> allowed.any { touched.startsWith(it) } }
            return if (permitted) Condition.Always else Condition.Never
        }
    }
}

/**
 * The paths this modification writes to, reporting a whole-object write as the root path (an empty list) rather
 * than as nothing at all, which is what [affectsPaths] does. Only [UpdateRestriction.OnlyTouches] needs this;
 * [Modification.affects] already treats whole-object writes as affecting every path.
 */
internal fun Modification<*>.touchedPaths(): List<List<SerializableProperty<*, *>>> =
    affectsPaths().ifEmpty { if (modifiesRoot()) listOf(emptyList()) else emptyList() }

/**
 * Whether this modification writes the root value directly (e.g. [Modification.Assign] on `T` itself), as opposed
 * to only navigating into specific fields via [Modification.OnField].
 */
private fun Modification<*>.modifiesRoot(): Boolean = when (this) {
    is Modification.OnField<*, *> -> false
    is Modification.Chain<*> -> modifications.any { it.modifiesRoot() }
    is Modification.IfNotNull<*> -> modification.modifiesRoot()
    is Modification.SetPerElement<*> -> modification.modifiesRoot()
    is Modification.ListPerElement<*> -> modification.modifiesRoot()
    else -> true
}

private fun List<SerializableProperty<*, *>>.startsWith(prefix: List<SerializableProperty<*, *>>): Boolean =
    prefix.size <= size && subList(0, prefix.size) == prefix

/**
 * DSL for building an [UpdateRestriction]. Every statement in the block is a clause that must hold, so the block
 * as a whole is an [UpdateRestriction.All].
 *
 * @see UpdateRestriction for an example
 */
public inline fun <reified T> updateRestriction(
    builder: UpdateRestrictionBuilder<T>.(DataClassPath<T, T>) -> Unit,
): UpdateRestriction<T> = UpdateRestrictionBuilder<T>().apply { builder(path<T>()) }.build()

/**
 * Collects the clauses of an [updateRestriction] block. Because [anyOf] nests this same builder, alternatives are
 * written with the very same verbs as top-level clauses, at any depth.
 */
public class UpdateRestrictionBuilder<T> {
    @PublishedApi
    internal val clauses: MutableList<UpdateRestriction<T>> = ArrayList()

    /** Adds an already-built restriction as a clause. The other verbs are conveniences over this. */
    public fun add(restriction: UpdateRestriction<T>) {
        clauses.add(restriction)
    }

    public fun build(): UpdateRestriction<T> = UpdateRestriction.All(clauses.toList())

    /** This field may not be modified at all. */
    public fun DataClassPath<T, *>.cannotBeModified() {
        add(UpdateRestriction.Untouched(this))
    }

    /** This field may only be modified when the existing record matches [condition]. */
    public infix fun DataClassPath<T, *>.requires(condition: Condition<T>) {
        add(
            UpdateRestriction.AnyOf(
                listOf(UpdateRestriction.Untouched(this), UpdateRestriction.OnCurrentItem(condition))
            )
        )
    }

    /** Whatever this field is changed to must satisfy [valueMust]. Leaving it alone is always fine. */
    public inline fun <reified V> DataClassPath<T, V>.mustBe(
        noinline valueMust: (DataClassPath<V, V>) -> Condition<V>,
    ) {
        add(UpdateRestriction.Preserves(this.condition(valueMust)))
    }

    /** Nothing outside [paths] may be modified -- the whitelist case. */
    public fun onlyModifiable(vararg paths: DataClassPathPartial<T>) {
        add(UpdateRestriction.OnlyTouches(paths.toSet()))
    }

    /**
     * At least one of [alternatives] must hold. Each alternative is an ordinary block of the same verbs, so it
     * may span several fields and may itself contain [anyOf].
     *
     * ```kotlin
     * anyOf(
     *     { user.role requires (user.role eq Role.Admin) },
     *     { user.role.mustBe { it eq Role.User } },
     * )
     * ```
     */
    public fun anyOf(vararg alternatives: UpdateRestrictionBuilder<T>.() -> Unit) {
        require(alternatives.isNotEmpty()) {
            "anyOf needs at least one alternative; an empty one would block everything"
        }
        add(UpdateRestriction.AnyOf(alternatives.map { UpdateRestrictionBuilder<T>().apply(it).build() }))
    }
}

/**
 * Layers additional restrictions on top of the ones these [ModelPermissions] already carry. Both sets apply, so
 * the result is never more permissive than either.
 */
public inline fun <reified T> ModelPermissions<T>.withAdditionalUpdateRestriction(
    builder: UpdateRestrictionBuilder<T>.(DataClassPath<T, T>) -> Unit,
): ModelPermissions<T> = copy(
    updateRestriction = UpdateRestriction.All(listOf(updateRestriction, updateRestriction(builder)))
)
