package com.lightningkite.services.database

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Defines restrictions on which fields can be modified in database update operations and under what conditions.
 *
 * Each field path listed in [perField] carries a list of *alternative* [RestrictionOption]s -- OR'd together --
 * so different actors can be permitted different things on the same field (e.g. "admins may set any role; users
 * may set their own role only to Member"). Fields not listed in [perField] fall back to [default].
 *
 * Use `default = emptyList()` for whitelist-style behavior (fields not listed are blocked), or
 * `default = listOf(RestrictionOption(Condition.Always, Condition.Always))` for blacklist-style behavior
 * (fields not listed are unrestricted). See [Mode] and the [updateRestrictions] DSL for the common cases.
 *
 * ## Usage
 * ```kotlin
 * val userRestrictions = updateRestrictions<User> { user ->
 *     // Users can't modify their own role
 *     user.role.cannotBeModified()
 *     // Credits can only be modified by admins
 *     user.credits requires (user.role eq Role.Admin)
 *     // isActive can only be set to true (no deactivation via this path)
 *     user.isActive.mustBe { it eq true }
 * }
 *
 * val condition = userRestrictions(modification)
 * table.updateMany(condition = condition and (User.path._id eq userId), modification = modification)
 * ```
 *
 * ## Wire format
 * [UpdateRestrictionsSerializer] writes the real `perField`/`default` data *and* a lossy projection of it into the
 * v1 `{mode, fields}` shape, so clients released against v1 (e.g. the admin UI) keep working. Reads accept either
 * shape and prefer `perField`/`default`. See that class for the projection's two lossy cases.
 *
 * @param T The data class type being restricted
 * @param perField Alternative restriction rules, keyed by field path
 * @param default The rules applied to any field not present in [perField]
 */
@Serializable(UpdateRestrictionsSerializer::class)
public data class UpdateRestrictions<T>(
    public val perField: Map<DataClassPathPartial<T>, List<RestrictionOption<T>>> = emptyMap(),
    public val default: List<RestrictionOption<T>> = listOf(RestrictionOption(Condition.Always, Condition.Always)),
) {
    /**
     * One alternative rule for a field. The field may be modified when the *existing* record matches
     * [ifCurrentItem], and only if the record matches [newValueMustBe] *after* the modification is applied.
     */
    @Serializable
    public data class RestrictionOption<in T>(
        public val ifCurrentItem: Condition<T>,
        public val newValueMustBe: Condition<T>,
    )

    /** The default behavior for fields not explicitly mentioned in [perField], as selected by the DSL. */
    @Serializable
    public enum class Mode(public val default: List<RestrictionOption<Any?>>) {
        /** All fields can be modified except explicitly restricted ones */
        Blacklist(
            listOf(RestrictionOption(
                ifCurrentItem = Condition.Always,
                newValueMustBe = Condition.Always
            ))
        ),

        /** Only explicitly allowed fields can be modified */
        Whitelist(
            emptyList()
        );
    }

    /**
     * Evaluates the restrictions against a [Modification] and returns the [Condition] that must be met on the
     * existing record for the modification to be allowed. Returns [Condition.Never] if no rule can be satisfied.
     *
     * For every path the modification affects, this uses the nearest-ancestor entry in [perField] (or [default]
     * if none matches), and also applies the rules of any *descendant* fields the modification overwrites -- so a
     * restriction on a parent field applies to whole-object assignments, and a restriction on a child field still
     * applies when a parent object containing it is overwritten wholesale.
     */
    public operator fun invoke(on: Modification<T>): Condition<T> {
        val byProps: Map<List<SerializableProperty<*, *>>, List<RestrictionOption<T>>> = perField.entries
            .groupBy({ it.key.properties }, { it.value })
            .mapValues { (_, optionLists) -> optionLists.reduce(::andMerge) }

        val affected = on.affectsPaths().ifEmpty {
            if (!on.modifiesRoot()) return Condition.Always
            listOf(emptyList())
        }

        val total = ArrayList<Condition<T>>()
        for (path in affected) {
            val direct = (path.size downTo 1).firstNotNullOfOrNull { byProps[path.subList(0, it)] } ?: default
            val descendants = byProps.filterKeys { it.size > path.size && it.subList(0, path.size) == path }.values

            for (restrictions in listOf(direct) + descendants) {
                val matching = restrictions.filter { it.newValueMustBe.guaranteedAfter(on) }
                if (matching.isEmpty()) return Condition.Never
                total.add(Condition.Or(matching.map { it.ifCurrentItem }))
            }
        }
        return Condition.And(total).simplify()
    }

    /**
     * Builder for constructing [UpdateRestrictions] using a DSL. Typically used via the [updateRestrictions] DSL
     * function rather than directly.
     *
     * Each field-targeting call adds an *alternative* [RestrictionOption] to that field (OR'd with any existing
     * alternatives on the same field); calling the same field multiple times AND-merges the calls together
     * (cross product of both calls' alternatives), matching the v1 builder's "multiple rules AND together"
     * behavior.
     */
    public class Builder<T> @PublishedApi internal constructor(private var default: List<RestrictionOption<T>>) {
        @PublishedApi
        internal val perField: LinkedHashMap<DataClassPathPartial<T>, List<RestrictionOption<T>>> = LinkedHashMap()

        /**
         * Completely blocks modifications to this field.
         *
         * **Warning:** This overrides all previous restrictions
         */
        public fun DataClassPath<T, *>.cannotBeModified() {
            perField[this] = emptyList()
        }

        /**
         * Allows unrestricted modifications to this field.
         *
         * **Warning:** This overrides all previous restrictions
         */
        public fun DataClassPath<T, *>.canBeModified() {
            perField[this] = listOf(RestrictionOption(Condition.Always, Condition.Always))
        }

        /**
         * Makes this field only modifiable when the existing record matches [condition].
         */
        public infix fun DataClassPath<T, *>.requires(condition: Condition<T>) {
            mergeInto(this, listOf(RestrictionOption(condition, Condition.Always)))
        }

        /**
         * Restricts what values this field can be changed to, via a lambda that returns a condition the new
         * value must satisfy.
         */
        public inline fun <reified V> DataClassPath<T, V>.mustBe(
            valueMust: (DataClassPath<V, V>) -> Condition<V>,
        ) {
            mergeInto(this, listOf(RestrictionOption(Condition.Always, this.condition(valueMust))))
        }

        /**
         * Makes this field only modifiable when [requires] is met on the existing record, and restricts what
         * values it can be changed to via [valueMust].
         */
        public inline fun <reified V> DataClassPath<T, V>.requires(
            requires: Condition<T>,
            valueMust: (DataClassPath<V, V>) -> Condition<V>,
        ) {
            mergeInto(this, listOf(RestrictionOption(requires, this.condition(valueMust))))
        }

        /**
         * Declares alternative rules (OR'd) for this field in a single call -- the capability the v1
         * one-rule-per-field model didn't have. AND-merges with any prior rules on this field, same as the other
         * builder methods.
         *
         * ```kotlin
         * // Admins may set any role; anyone may demote themselves to plain User
         * user.role.anyOf(
         *     Option(user.role eq Role.Admin),
         *     Option(Condition.Always) { it.role eq Role.User },
         * )
         * ```
         */
        public fun <V> DataClassPath<T, V>.requiresAnyOf(vararg options: Option<T, V>) {
            require(options.isNotEmpty()) {
                "anyOf needs at least one option; use cannotBeModified() to block the field entirely"
            }
            mergeInto(this, options.map {
                when (it) {
                    is Option.OnClass<T> -> RestrictionOption(it.ifCurrentItem, it.newValueMustBe)
                    is Option.OnValue<T, V> -> RestrictionOption(it.ifCurrentItem, mapCondition(it.newValueMustBe))
                }
            })
        }

        public sealed interface Option<T, in V> {
            public class OnClass<T>(
                public val ifCurrentItem: Condition<T>,
                public val newValueMustBe: Condition<T>
            ): Option<T, Any?>

            public class OnValue<T, V>(
                public val ifCurrentItem: Condition<T>,
                public val newValueMustBe: Condition<V>,
            ): Option<T, V>
        }

        public fun Option(
            ifCurrentItem: Condition<T>,
            newValueMustBe: Condition<T> = Condition.Always,
        ): Option<T, Any?> = Option.OnClass(ifCurrentItem, newValueMustBe)

        public inline fun <reified V> Option(
            ifCurrentItem: Condition<T>,
            newValueMustBe: (DataClassPath<V, V>) -> Condition<V>,
        ): Option<T, V> = Option.OnValue(
            ifCurrentItem,
            newValueMustBe(com.lightningkite.services.database.path())
        )

        /**
         * Declares alternative rules (OR'd) for this field in a single call -- the capability the v1
         * one-rule-per-field model didn't have. AND-merges with any prior rules on this field, same as the other
         * builder methods.
         *
         * ```kotlin
         * // Admins may set any role; anyone may demote themselves to plain User
         * user.role.anyOf(
         *     Option(user.role eq Role.Admin),
         *     Option(Condition.Always) { it.role eq Role.User },
         * )
         * ```
         */
        public fun DataClassPath<T, *>.anyOfRestrictions(vararg options: RestrictionOption<T>) {
            require(options.isNotEmpty()) {
                "anyOf needs at least one option; use cannotBeModified() to block the field entirely"
            }
            mergeInto(this, options.toList())
        }


        @PublishedApi
        internal fun mergeInto(path: DataClassPathPartial<T>, options: List<RestrictionOption<T>>) {
            // No prior declaration on this field: use `options` as-is rather than AND-merging against a
            // synthetic identity, so a single declaration stores cleanly instead of picking up redundant
            // `Always AND ...` noise.
            val existing = perField[path]
            perField[path] = if (existing == null) options else andMerge(existing, options)
        }

        public fun include(mask: UpdateRestrictions<T>) {
            default = default.flatMap { mine ->
                mask.default.map {
                    mine.copy(
                        ifCurrentItem = (mine.ifCurrentItem and it.ifCurrentItem).simplify(),
                        newValueMustBe = (mine.newValueMustBe and it.newValueMustBe).simplify()
                    )
                }
            }.distinct()

            for ((key, options) in mask.perField) {
                mergeInto(key, options)
            }
        }

        public fun build(): UpdateRestrictions<T> = UpdateRestrictions(
            default = default,
            perField = perField.filter { it.value.all { option -> option == default } } // filter fields where all options are the same as the default
        )
    }

    // =================================================================================================
    // Deprecated: the v1 `(mode, fields)` representation.
    //
    // Retained only so old serialized payloads and old source keep working; everything above is
    // independent of it, and [UpdateRestrictionsSerializer] is the only thing in this library that still
    // reads or writes it.
    // =================================================================================================

    /** Derived from whether [default] is empty. Kept for source compatibility with the v1 API. */
    @Deprecated("Derived from `default`; construct perField/default directly instead of branching on mode")
    public val mode: Mode get() = if (default.isEmpty()) Mode.Whitelist else Mode.Blacklist

    /**
     * A single field-specific restriction rule, as used by the v1 [UpdateRestrictions] representation.
     * Retained so old serialized payloads (`{mode, fields}`) can still be read/converted.
     */
    @Deprecated("Replaced by RestrictionOption stored in perField/default")
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    public data class Part<T>(
        @JsonNames("path") public val property: DataClassPathPartial<T>,
        @JsonNames("limitedIf") public val requires: Condition<T>,
        public val limitedTo: Condition<T>,
    )

    /**
     * Converts the v1 (mode, fields) shape into the current perField/default representation. A `Part` with
     * `requires == Never` (what `cannotBeModified()` produces) collapses to an empty option list -- v1's
     * "unconditionally blocked" -- rather than being AND-merged in literally, both to match what the [Builder]
     * itself would produce and because `Never` is absorbing under AND regardless of `limitedTo` either way.
     */
    @Suppress("DEPRECATION")
    @Deprecated("Use the perField/default constructor, or the updateRestrictions {} DSL, instead")
    public constructor(mode: Mode = Mode.Blacklist, fields: List<Part<T>>) : this(
        perField = fields.groupBy { it.property }.mapValues { (_, parts) ->
            parts.fold(null as List<RestrictionOption<T>>?) { acc, part ->
                val partOptions =
                    if (part.requires == Condition.Never) emptyList()
                    else listOf(RestrictionOption(part.requires, part.limitedTo))
                if (acc == null) partOptions else andMerge(acc, partOptions)
            }!!
        },
        default = if (mode == Mode.Whitelist) emptyList() else listOf(RestrictionOption(Condition.Always, Condition.Always)),
    )
}

/**
 * Cross-product AND of two alternative-lists: every combination of an option from [a] and an option from [b] is
 * ANDed together. An empty list is absorbing (`[] x anything = []`), matching `cannotBeModified()`'s "never".
 */
@PublishedApi
internal fun <T> andMerge(
    a: List<UpdateRestrictions.RestrictionOption<T>>,
    b: List<UpdateRestrictions.RestrictionOption<T>>,
): List<UpdateRestrictions.RestrictionOption<T>> = a.flatMap { x ->
    b.map { y ->
        UpdateRestrictions.RestrictionOption(
            ifCurrentItem = x.ifCurrentItem and y.ifCurrentItem,
            newValueMustBe = x.newValueMustBe and y.newValueMustBe,
        )
    }
}

/**
 * Whether this modification writes the root value directly (e.g. [Modification.Assign] on `T` itself), as
 * opposed to only navigating into specific fields via [Modification.OnField]. Used by [UpdateRestrictions.invoke]
 * to treat whole-object writes as affecting every field, since [Modification.affectsPaths] reports no paths for
 * them.
 */
private fun Modification<*>.modifiesRoot(): Boolean = when (this) {
    is Modification.OnField<*, *> -> false
    is Modification.Chain<*> -> modifications.any { it.modifiesRoot() }
    is Modification.IfNotNull<*> -> modification.modifiesRoot()
    is Modification.SetPerElement<*> -> modification.modifiesRoot()
    is Modification.ListPerElement<*> -> modification.modifiesRoot()
    else -> true
}

/**
 * DSL for defining [UpdateRestrictions] in a type-safe way.
 *
 * [mode] specifies the default behavior for unspecified fields:
 * - [UpdateRestrictions.Mode.Blacklist] (default): All fields allowed unless restricted
 * - [UpdateRestrictions.Mode.Whitelist]: All fields blocked unless explicitly allowed
 *
 * ```kotlin
 * val restrictions = updateRestrictions<User> { user ->
 *     user._id.cannotBeModified()
 *     user.role requires (user.role eq Role.Admin)
 * }
 * ```
 *
 * @return Configured [UpdateRestrictions] instance ready to be applied to modifications
 */
public inline fun <reified T> updateRestrictions(
    mode: UpdateRestrictions.Mode = UpdateRestrictions.Mode.Blacklist,
    builder: UpdateRestrictions.Builder<T>.(DataClassPath<T, T>) -> Unit,
): UpdateRestrictions<T> {
    return UpdateRestrictions.Builder<T>(mode.default).apply { builder(path<T>()) }.build()
}

public inline fun <reified T> updateRestrictions(
    default: List<UpdateRestrictions.RestrictionOption<T>>,
    builder: UpdateRestrictions.Builder<T>.(DataClassPath<T, T>) -> Unit,
): UpdateRestrictions<T> {
    return UpdateRestrictions.Builder(default).apply { builder(path<T>()) }.build()
}

/**
 * Convenience function for creating blacklist-mode [UpdateRestrictions]: all fields allowed by default unless
 * explicitly restricted.
 * @see updateRestrictions
 */
public inline fun <reified T> blacklistRestrictions(
    builder: UpdateRestrictions.Builder<T>.(DataClassPath<T, T>) -> Unit,
): UpdateRestrictions<T> =
    updateRestrictions(UpdateRestrictions.Mode.Blacklist, builder)

/**
 * Convenience function for creating whitelist-mode [UpdateRestrictions]: all fields blocked by default unless
 * explicitly allowed.
 * @see updateRestrictions
 */
public inline fun <reified T> whitelistRestrictions(
    builder: UpdateRestrictions.Builder<T>.(DataClassPath<T, T>) -> Unit,
): UpdateRestrictions<T> =
    updateRestrictions(UpdateRestrictions.Mode.Whitelist, builder)

/**
 * Creates a copy of these [ModelPermissions] with additional update restrictions layered on top of the existing
 * ones, in the same [UpdateRestrictions.Mode] as the original.
 */
public inline fun <reified T> ModelPermissions<T>.withAdditionalUpdateRestrictions(
    builder: UpdateRestrictions.Builder<T>.(DataClassPath<T, T>) -> Unit,
): ModelPermissions<T> =
    copy(
        updateRestrictions = updateRestrictions(default = updateRestrictions.default) {
            include(updateRestrictions)
            builder(it)
        }
    )

// =====================================================================================================
// Deprecated
// =====================================================================================================

@Deprecated("Renamed", ReplaceWith("UpdateRestrictions.Part"))
public typealias UpdateRestrictionsPart<T> = UpdateRestrictions.Part<T>
