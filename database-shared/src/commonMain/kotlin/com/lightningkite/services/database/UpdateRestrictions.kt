package com.lightningkite.services.database

import com.lightningkite.services.data.GenerateDataClassPaths
import kotlinx.serialization.Serializable

/**
 * Defines restrictions on which fields can be modified in database update operations and under what conditions.
 *
 * [UpdateRestrictions] provides fine-grained control over update operations by specifying rules for individual
 * fields. Each field can have:
 * - Required conditions that must be met before the field can be modified
 * - Constraints on what values the field can be changed to
 *
 * ## Modes
 *
 * There are two fundamental modes that determine the default behavior:
 *
 * - **[Mode.Blacklist]** (default): All fields are modifiable by default unless explicitly restricted.
 *   Use this when you want to block specific fields or add conditions to certain fields.
 *
 * - **[Mode.Whitelist]**: All fields are blocked by default unless explicitly allowed.
 *   Use this when you want tight control and only permit modifications to specific fields.
 *
 * ## Usage Example
 *
 * ```kotlin
 * @Serializable
 * data class User(
 *     val _id: UUID,
 *     val email: String,
 *     val role: Role,
 *     val isActive: Boolean,
 *     val credits: Int
 * )
 *
 * // Blacklist mode: restrict specific fields
 * val userRestrictions = updateRestrictions<User> { user ->
 *     // Users can't modify their own role
 *     user.role.cannotBeModified()
 *
 *     // Credits can only be modified by admins
 *     user.credits requires (user.role eq Role.Admin)
 *
 *     // isActive can only be set to true (no deactivation via this path)
 *     user.isActive.mustBe { it eq true }
 * }
 *
 * // Whitelist mode: only allow specific fields
 * val restrictiveRestrictions = updateRestrictions<User>(mode = Mode.Whitelist) { user ->
 *     // Only these fields can be modified
 *     user.email.canBeModified()
 *     user.isActive.canBeModified()
 * }
 *
 * // Apply restrictions to a modification
 * val modification = user.email assign "newemail@example.com"
 * val requiredCondition = userRestrictions(modification)
 * // Use requiredCondition in your update query
 * table.updateMany(
 *     condition = requiredCondition and (user._id eq userId),
 *     modification = modification
 * )
 * ```
 *
 * @param T The data class type being restricted
 * @param mode The restriction mode ([Mode.Blacklist] or [Mode.Whitelist])
 * @param fields List of field-specific restrictions
 */
@Serializable
@GenerateDataClassPaths
public data class UpdateRestrictions<T>(
    public val mode: Mode = Mode.Blacklist,
    public val fields: List<Part<T>>,
) {
    /**
     * Determines the default behavior for fields not explicitly mentioned in [fields].
     *
     * - **[Whitelist]**: Field modifications are blocked by default.
     *
     * - **[Blacklist]**: Field modifications are allowed by default.
     */
    @Serializable
    public enum class Mode {
        /** All fields can be modified except explicitly restricted ones */
        Blacklist,

        /** Only explicitly allowed fields can be modified */
        Whitelist,
    }

    /**
     * Represents a restriction rule for a specific field.
     *
     * A [Part] defines both when a field can be modified and what values it can be changed to.
     *
     * @param property The field path being restricted (e.g., `User.path.email`)
     * @param requires The condition that must be satisfied on the *existing* record before this field can be modified.
     *   - [Condition.Always]: Field can always be modified (subject to [limitedTo])
     *   - [Condition.Never]: Field cannot be modified at all
     *   - Custom condition: Field can only be modified if the existing record matches this condition
     *
     * @param limitedTo Restricts what values the field can be changed *to*.
     *   - [Condition.Always]: Field can be changed to any value (subject to [requires])
     *   - [Condition.Never]: Field cannot be changed (used internally when modification is blocked)
     *   - Custom condition: The new value must satisfy this condition after the update
     *
     * ## Example
     *
     * ```kotlin
     * // Credits can only be increased, and only by admins
     * Part(
     *     property = User.path.credits,
     *     requires = User.path.role eq Role.Admin,  // Must be admin to modify
     *     limitedTo = User.path.credits greaterThan oldValue  // Can only increase
     * )
     * ```
     */
    @Serializable
    @GenerateDataClassPaths
    public data class Part<T>(
        val property: DataClassPathPartial<T>,
        val requires: Condition<T>,
        val limitedTo: Condition<T>,
    )

    /**
     * Evaluates the restrictions against a [Modification] and returns the [Condition] that must be met
     * for the modification to be allowed.
     *
     * This operator analyzes which fields are affected by the [Modification] and combines the [Part.requires]
     * conditions for those fields. It also validates that any [Part.limitedTo] constraints can be satisfied
     * by the modification.
     *
     * ## Return Values
     *
     * - [Condition.Always]: The modification is allowed on any record
     * - [Condition.Never]: The modification is completely blocked (no records can be modified this way)
     * - Specific condition: The modification is only allowed on records matching this condition
     *
     * ## Behavior by Mode
     *
     * - **[Mode.Blacklist]**: If no restricted fields are affected, returns [Condition.Always] (modification allowed)
     * - **[Mode.Whitelist]**: If no allowed fields are affected, returns [Condition.Never] (modification blocked)
     *
     * ## Usage
     *
     * ```kotlin
     * val restrictions = updateRestrictions<User> { user ->
     *     user.role requires (user.role eq Role.Admin)
     * }
     *
     * val mod = User.path.role assign Role.Admin
     * val condition = restrictions(mod)  // Returns: user.role eq Role.Admin
     *
     * // Use in update operation
     * table.updateMany(
     *     condition = condition and (User.path._id eq userId),
     *     modification = mod
     * )
     * ```
     *
     * @param on The modification to evaluate
     * @return The condition that must be met for this modification to be allowed
     */
    public operator fun invoke(on: Modification<T>): Condition<T> {
        val appliedConditions = LinkedHashSet<Condition<T>>()

        when (mode) {
            Mode.Blacklist -> {
                for (field in fields) {
                    if (on.affects(field.property)) {
                        if (field.limitedTo != Condition.Always) {
                            if (!field.limitedTo.guaranteedAfter(on)) return Condition.Never
                        }
                        appliedConditions.add(field.requires)
                    }
                }
            }

            Mode.Whitelist -> {
                for (path in on.affectsPaths()) {
                    val affected = fields.filter { field ->
                        field.property.properties.zip(path).all { it.first == it.second }
                    }
                    // whitelist mode - the modification is affecting a path that is unspecified: block it
                    if (affected.isEmpty()) return Condition.Never
                    for (field in affected) {
                        if (field.limitedTo != Condition.Always) {
                            if (!field.limitedTo.guaranteedAfter(on)) return Condition.Never
                        }
                        appliedConditions.add(field.requires)
                    }
                }
            }
        }

        val distinct = appliedConditions.toList()

        return when (distinct.size) {
            0 -> when (mode) {
                Mode.Whitelist -> Condition.Never
                Mode.Blacklist -> Condition.Always
            }

            1 -> distinct[0]
            else -> Condition.And(distinct)
        }
    }

    /**
     * Builder for constructing [UpdateRestrictions] using a DSL.
     *
     * The builder provides methods to define restrictions on individual fields:
     * - [cannotBeModified] / [canBeModified]: Block or allow field modifications
     * - [requires]: Require conditions on the existing record
     * - [mustBe]: Restrict what values a field can be changed to
     *
     * Typically used via the [updateRestrictions] DSL function rather than directly.
     *
     * @param mode The restriction mode ([Mode.Whitelist] or [Mode.Blacklist])
     * @param fields Accumulates the field restrictions as they're defined
     */
    public class Builder<T>(
        mode: Mode,
        public val fields: ArrayList<Part<T>> = ArrayList(),
    ) {
        public var mode: Mode = mode
            private set

        /**
         * Completely blocks modifications to this field.
         *
         * In [Mode.Blacklist], this prevents any modification to the field.
         * In [Mode.Whitelist], this is a no-op (fields are already blocked by default).
         *
         * ## Example
         * ```kotlin
         * updateRestrictions<User> { user ->
         *     // ID cannot be changed
         *     user._id.cannotBeModified()
         *
         *     // Creation timestamp cannot be changed
         *     user.createdAt.cannotBeModified()
         * }
         * ```
         */
        public fun DataClassPath<T, *>.cannotBeModified() {
            if (mode == Mode.Whitelist) return
            fields.add(Part(property = this, requires = Condition.Never, limitedTo = Condition.Always))
        }

        /**
         * Allows unrestricted modifications to this field.
         *
         * In [Mode.Whitelist], this explicitly allows the field to be modified.
         * In [Mode.Blacklist], this is a no-op (fields are already allowed by default).
         *
         * ## Example
         * ```kotlin
         * updateRestrictions<User>(mode = Mode.Whitelist) { user ->
         *     // Only these fields can be modified
         *     user.email.canBeModified()
         *     user.displayName.canBeModified()
         *     // All other fields are blocked
         * }
         * ```
         */
        public fun DataClassPath<T, *>.canBeModified() {
            if (mode == Mode.Blacklist) return
            fields.add(Part(property = this, requires = Condition.Always, limitedTo = Condition.Always))
        }

        /**
         * Makes this field only modifiable when the existing record matches the specified [condition].
         *
         * The [condition] is evaluated against the *current* state of the record before the update.
         * If the condition is not met, the update will not affect any records.
         *
         * ## Example
         * ```kotlin
         * updateRestrictions<User> { user ->
         *     // Only admins can modify the role field
         *     user.role requires (user.role eq Role.Admin)
         *
         *     // Users can only modify their own email
         *     user.email requires (user._id eq currentUserId)
         *
         *     // Suspended users cannot modify their profile
         *     user.displayName requires (user.status ne Status.Suspended)
         * }
         * ```
         *
         * @param condition The condition that must be met on the existing record
         */
        public infix fun DataClassPath<T, *>.requires(condition: Condition<T>) {
            fields.add(Part(property = this, requires = condition, limitedTo = Condition.Always))
        }

        /**
         * Restricts what values this field can be changed to, without restricting who can change it.
         *
         * The [valueMust] lambda receives a path to the field's value and returns a condition
         * that the new value must satisfy.
         *
         * ## Example
         * ```kotlin
         * updateRestrictions<User> { user ->
         *     // Email must be valid format (simplified example)
         *     user.email.mustBe { it contains "@" }
         *
         *     // Age must be reasonable
         *     user.age.mustBe { (it gte 0) and (it lt 150) }
         *
         *     // Status can only progress forward
         *     user.status.mustBe { it ne Status.Deleted }
         *
         *     // Credits can only increase (never decrease)
         *     user.credits.mustBe { it gte user.credits }
         * }
         * ```
         *
         * @param valueMust Lambda that returns a condition the new value must satisfy
         */
        public inline fun <reified V> DataClassPath<T, V>.mustBe(valueMust: (DataClassPath<V, V>) -> Condition<V>) {
            fields.add(Part(property = this, requires = Condition.Always, limitedTo = this.condition(valueMust)))
        }

        /**
         * Makes this field only modifiable when [requires] is met, and restricts what values it can be changed to.
         *
         * Combines two types of restrictions:
         * 1. The existing record must match [requires] (checked before update)
         * 2. The new value must satisfy the condition from [valueMust] after the modification is applied
         *
         * ## Example
         * ```kotlin
         * updateRestrictions<User> { user ->
         *     // Admins can change credits, but only to positive values
         *     user.credits.requires(
         *         requires = user.role eq Role.Admin,
         *         valueMust = { it gt 0 }
         *     )
         *
         *     // Moderators can change status, but not to Admin
         *     user.role.requires(
         *         requires = user.role eq Role.Moderator,
         *         valueMust = { it ne Role.Admin }
         *     )
         * }
         * ```
         *
         * @param requires Condition on the existing record
         * @param valueMust Lambda that returns a condition the new value must satisfy
         */
        public inline fun <reified V> DataClassPath<T, V>.requires(
            requires: Condition<T>,
            valueMust: (DataClassPath<V, V>) -> Condition<V>,
        ) {
            fields.add(Part(property = this, requires = requires, limitedTo = this.condition(valueMust)))
        }

        /**
         * Includes all restrictions from another [UpdateRestrictions] instance into this builder.
         *
         * This is useful for composing restrictions from multiple sources or layering
         * base restrictions with additional rules.
         *
         * **Important**: If the included [mask] is in a different [Mode], this builder will
         * be put into the most restrictive mode specified (i.e. [Mode.Whitelist])
         *
         * ## Example
         * ```kotlin
         * // Base restrictions that apply to all users
         * val baseRestrictions = updateRestrictions<User> { user ->
         *     user._id.cannotBeModified()
         *     user.createdAt.cannotBeModified()
         * }
         *
         * // Additional restrictions for non-admin operations
         * val userRestrictions = updateRestrictions<User> { user ->
         *     include(baseRestrictions)  // Include base rules
         *     user.role.cannotBeModified()  // Add additional rules
         *     user.permissions.cannotBeModified()
         * }
         * ```
         *
         * @param mask The restrictions to include
         * @throws IllegalArgumentException if [mask] has a different mode than this builder
         */
        public fun include(mask: UpdateRestrictions<T>) {
            mode = maxOf(mode, mask.mode)
            fields.addAll(mask.fields)
        }

        public fun build(): UpdateRestrictions<T> = UpdateRestrictions(mode, fields)
    }
}


/**
 * DSL for defining [UpdateRestrictions] in a type-safe way.
 *
 * This function provides a convenient builder syntax for creating update restrictions.
 * The builder lambda receives the root [DataClassPath] for the type [T], allowing you
 * to reference fields in a type-safe manner.
 *
 * ## Mode
 *
 * [mode] specifies the default behavior for unspecified fields:
 *
 *   - [UpdateRestrictions.Mode.Blacklist] (default): All fields allowed unless restricted
 *   - [UpdateRestrictions.Mode.Whitelist]: All fields blocked unless explicitly allowed
 *
 * ## Examples
 *
 * ### Basic Blacklist (restricting specific fields)
 * ```kotlin
 * val restrictions = updateRestrictions<User> { user ->
 *     user._id.cannotBeModified()
 *     user.createdAt.cannotBeModified()
 *     user.role requires (user.role eq Role.Admin)
 * }
 * ```
 *
 * ### Whitelist (only allowing specific fields)
 * ```kotlin
 * val publicProfileRestrictions = updateRestrictions<User>(mode = Mode.Whitelist) { user ->
 *     user.displayName.canBeModified()
 *     user.bio.canBeModified()
 *     user.avatarUrl.canBeModified()
 *     // Everything else is blocked
 * }
 * ```
 *
 * ### Complex restrictions with value constraints
 * ```kotlin
 * val accountRestrictions = updateRestrictions<Account> { account ->
 *     // Balance can only be modified by system
 *     account.balance requires (account.updatedBy eq "SYSTEM")
 *
 *     // Status changes require approval, and can't be set to Deleted
 *     account.status.requires(
 *         requires = account.approvedBy ne null,
 *         valueMust = { it ne Status.Deleted }
 *     )
 *
 *     // Credit limit can only increase, never decrease
 *     account.creditLimit.mustBe { it greaterThanOrEq account.creditLimit }
 * }
 * ```
 *
 * ### Composing restrictions
 * ```kotlin
 * val baseRestrictions = updateRestrictions<User> { user ->
 *     user._id.cannotBeModified()
 * }
 *
 * val extendedRestrictions = updateRestrictions<User> { user ->
 *     include(baseRestrictions)
 *     user.role.cannotBeModified()
 * }
 * ```
 *
 * @return Configured [UpdateRestrictions] instance ready to be applied to modifications
 */
public inline fun <reified T> updateRestrictions(
    mode: UpdateRestrictions.Mode = UpdateRestrictions.Mode.Blacklist,
    builder: UpdateRestrictions.Builder<T>.(DataClassPath<T, T>) -> Unit,
): UpdateRestrictions<T> {
    return UpdateRestrictions.Builder<T>(mode).apply { builder(path<T>()) }.build()
}

/**
 * Convenience function for creating blacklist-mode [UpdateRestrictions].
 *
 * This is equivalent to calling `updateRestrictions(mode = UpdateRestrictions.Mode.Blacklist)`.
 * In blacklist mode, all fields are allowed by default unless explicitly restricted.
 *
 * ## Example
 * ```kotlin
 * val restrictions = blacklistRestrictions<User> { user ->
 *     user._id.cannotBeModified()
 *     user.role requires (user.role eq Role.Admin)
 * }
 * ```
 *
 * @param T The data class type being restricted
 * @param builder Lambda to configure field restrictions
 * @return [UpdateRestrictions] in blacklist mode
 * @see updateRestrictions
 */
public inline fun <reified T> blacklistRestrictions(
    builder: UpdateRestrictions.Builder<T>.(DataClassPath<T, T>) -> Unit,
): UpdateRestrictions<T> =
    updateRestrictions(UpdateRestrictions.Mode.Blacklist, builder)

/**
 * Convenience function for creating whitelist-mode [UpdateRestrictions].
 *
 * This is equivalent to calling `updateRestrictions(mode = UpdateRestrictions.Mode.Whitelist)`.
 * In whitelist mode, all fields are blocked by default unless explicitly allowed.
 *
 * ## Example
 * ```kotlin
 * val restrictions = whitelistRestrictions<User> { user ->
 *     user.email.canBeModified()
 *     user.username.canBeModified()
 *     // All other fields are blocked
 * }
 * ```
 *
 * @param T The data class type being restricted
 * @param builder Lambda to configure field restrictions
 * @return [UpdateRestrictions] in whitelist mode
 * @see updateRestrictions
 */
public inline fun <reified T> whitelistRestrictions(
    builder: UpdateRestrictions.Builder<T>.(DataClassPath<T, T>) -> Unit,
): UpdateRestrictions<T> =
    updateRestrictions(UpdateRestrictions.Mode.Whitelist, builder)

/**
 * Creates a copy of these [ModelPermissions] with additional update restrictions layered on top.
 *
 * This extension function allows you to build upon existing restrictions without replacing them.
 * The new restrictions are added using the same [UpdateRestrictions.Mode] as the original.
 *
 * ## Usage
 *
 * This is particularly useful for:
 * - Adding context-specific restrictions to base permissions
 * - Layering user-specific constraints on top of role-based permissions
 * - Temporarily tightening permissions for specific operations
 *
 * ## Example
 * ```kotlin
 * val basePermissions = ModelPermissions<User>(
 *     read = Condition.Always,
 *     update = User.path.role eq Role.Admin,
 *     updateRestrictions = updateRestrictions {
 *         it._id.cannotBeModified()
 *     }
 * )
 *
 * // Add additional restrictions for a specific context
 * val restrictedPermissions = basePermissions.withAdditionalUpdateRestrictions { user ->
 *     // Now also prevent modifying creation date
 *     user.createdAt.cannotBeModified()
 *     // And require approval for role changes
 *     user.role requires (user.approvedBy ne null)
 * }
 * // Result has both original restrictions (_id) AND new ones (createdAt, role)
 * ```
 *
 * @param T The model type
 * @param builder Lambda to configure additional field restrictions
 * @return New [ModelPermissions] with combined restrictions
 */
public inline fun <reified T> ModelPermissions<T>.withAdditionalUpdateRestrictions(
    builder: UpdateRestrictions.Builder<T>.(DataClassPath<T, T>) -> Unit,
): ModelPermissions<T> =
    copy(
        updateRestrictions = updateRestrictions(this.updateRestrictions.mode) {
            include(updateRestrictions)
            builder(it)
        }
    )

@Deprecated("Renamed", ReplaceWith("UpdateRestrictions.Part"))
public typealias UpdateRestrictionsPart<T> = UpdateRestrictions.Part<T>
