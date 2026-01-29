package com.lightningkite.services.database

import com.lightningkite.GeoCoordinate
import com.lightningkite.IsRawString
import com.lightningkite.services.data.DisplayName
import com.lightningkite.services.data.DoesNotNeedLabel
import com.lightningkite.services.data.TextIndex
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.jvm.JvmName

/**
 * Represents a boolean query condition for filtering database records.
 *
 * Condition forms a composable query DSL that can be:
 * - **Serialized**: All conditions are @Serializable for network transport/storage
 * - **Evaluated**: Can test against in-memory objects via invoke()
 * - **Translated**: Database implementations convert to native queries (MongoDB, SQL, etc.)
 *
 * ## Core Conditions
 *
 * - [Never]/[Always] - Constant true/false (useful for dynamic query building)
 * - [Equal]/[NotEqual] - Equality comparisons
 * - [Inside]/[NotInside] - Set membership
 * - [GreaterThan]/[LessThan] etc. - Numeric comparisons
 * - [And]/[Or]/[Not] - Boolean logic combinators
 *
 * ## Special Conditions
 *
 * - [StringContains]/[RawStringContains] - Substring search (case-insensitive by default)
 * - [FullTextSearch] - Full-text search with fuzzy matching and Levenshtein distance
 * - [RegexMatches] - Regular expression matching (may not be supported by all backends)
 * - [GeoDistance] - Geospatial queries by distance
 * - [IntBitsClear]/[IntBitsSet] etc. - Bitwise operations
 *
 * ## Collection Conditions
 *
 * - [ListAllElements]/[ListAnyElements] - Test all/any list elements
 * - [SetAllElements]/[SetAnyElements] - Test all/any set elements
 * - [ListSizesEquals]/[SetSizesEquals] - Size checks (deprecated in favor of empty checks)
 *
 * ## Nested Conditions
 *
 * - [OnField] - Navigate to nested object fields (created by DataClassPath)
 * - [OnKey] - Access map values by key
 * - [IfNotNull] - Null-safe condition wrapper
 *
 * ## Usage with Type-Safe DSL
 *
 * ```kotlin
 * // Using generated DataClassPath extensions
 * val adults = userTable.find(
 *     condition = User.path.age gte 18
 * )
 *
 * // Complex conditions
 * val activeVips = User.path.status eq UserStatus.Active and
 *                  (User.path.tier eq Tier.VIP)
 *
 * // Nested conditions
 * val hasVerifiedEmail = User.path.email.notNull eq "verified@example.com"
 * ```
 *
 * ## Important Gotchas
 *
 * - **Backend support varies**: Not all database backends support all conditions (e.g., RegexMatches)
 * - **FullTextSearch approximation**: In-memory evaluation is a rough approximation of database behavior
 * - **Case sensitivity**: String operations default to case-insensitive (StringContains, RawStringContains)
 * - **hashCode/equals**: Throw NotImplementedError by default - only specific implementations override
 * - **Type variance**: Condition is contravariant (Condition<Any?> is a valid Condition<String>)
 *
 * @param T The type of values this condition can test
 */
@Serializable(ConditionSerializer::class)
public sealed class Condition<in T> {
    open override fun hashCode(): Int = throw NotImplementedError()
    open override fun equals(other: Any?): Boolean = throw NotImplementedError()
    public open operator fun invoke(on: T): Boolean = throw NotImplementedError()

    @SerialName("Never")
    @Serializable
    @DisplayName("None")
    public data object Never : Condition<Any?>() {

        override fun invoke(on: Any?): Boolean = false
        override fun toString(): String = "false"
    }

    @SerialName("Always")
    @Serializable
    @DisplayName("All")
    public data object Always : Condition<Any?>() {

        override fun invoke(on: Any?): Boolean = true
        override fun toString(): String = "true"
    }

    @Serializable(ConditionAndSerializer::class)
    @SerialName("And")
    public data class And<T>(val conditions: List<Condition<T>>) : Condition<T>() {
        public constructor(vararg conditions: Condition<T>) : this(conditions.toList())

        override fun invoke(on: T): Boolean = conditions.all { it(on) }
        override fun toString(): String = conditions.joinToString(" && ", "(", ")")
    }

    @Serializable(ConditionOrSerializer::class)
    @SerialName("Or")
    public data class Or<T>(val conditions: List<Condition<T>>) : Condition<T>() {
        public constructor(vararg conditions: Condition<T>) : this(conditions.toList())

        override fun invoke(on: T): Boolean = conditions.any { it(on) }
        override fun toString(): String = conditions.joinToString(" || ", "(", ")")
    }

    @Serializable(ConditionNotSerializer::class)
    @SerialName("Not")
    public data class Not<T>(val condition: Condition<T>) : Condition<T>() {
        override fun invoke(on: T): Boolean = !condition(on)
        override fun toString(): String = "!($condition)"
    }

    @Serializable(ConditionEqualSerializer::class)
    @SerialName("Equal")
    public data class Equal<T>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on == value
        override fun toString(): String = " == $value"
    }

    @Serializable(ConditionNotEqualSerializer::class)
    @SerialName("NotEqual")
    public data class NotEqual<T>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on != value
        override fun toString(): String = " != $value"
    }

    @Serializable(ConditionInsideSerializer::class)
    @SerialName("Inside")
    public data class Inside<T>(val values: Set<T>) : Condition<T>() {
        public constructor(values: Collection<T>) : this(values.toSet())
        override fun invoke(on: T): Boolean = values.contains(on)
        override fun toString(): String = " in $values"
    }

    @Serializable(ConditionNotInsideSerializer::class)
    @SerialName("NotInside")
    public data class NotInside<T>(val values: Set<T>) : Condition<T>() {
        public constructor(values: Collection<T>) : this(values.toSet())
        override fun invoke(on: T): Boolean = !values.contains(on)
        override fun toString(): String = " !in $values"
    }

    @Serializable(ConditionGreaterThanSerializer::class)
    @SerialName("GreaterThan")
    public data class GreaterThan<T : Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on > value
        override fun toString(): String = " > $value"
    }

    @Serializable(ConditionLessThanSerializer::class)
    @SerialName("LessThan")
    public data class LessThan<T : Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on < value
        override fun toString(): String = " < $value"
    }

    @Serializable(ConditionGreaterThanOrEqualSerializer::class)
    @SerialName("GreaterThanOrEqual")
    public data class GreaterThanOrEqual<T : Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on >= value
        override fun toString(): String = " >= $value"
    }

    @Serializable(ConditionLessThanOrEqualSerializer::class)
    @SerialName("LessThanOrEqual")
    public data class LessThanOrEqual<T : Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on <= value
        override fun toString(): String = " <= $value"
    }

    @Serializable
    @SerialName("StringContains")
    public data class StringContains(@DoesNotNeedLabel val value: String, val ignoreCase: Boolean = false) :
        Condition<String>() {
        override fun invoke(on: String): Boolean = on.contains(value, ignoreCase)
        override fun toString(): String = ".contains($value, $ignoreCase)"
    }

    @Serializable
    @SerialName("StringContains")
    public data class RawStringContains<T : IsRawString>(val value: String, val ignoreCase: Boolean = false) : Condition<T>() {
        override fun invoke(on: T): Boolean = on.raw.contains(value, ignoreCase)
        override fun toString(): String = ".contains($value, $ignoreCase)"
    }

    @Serializable
    @SerialName("GeoDistance")
    public data class GeoDistance(
        val value: GeoCoordinate,
        val greaterThanKilometers: Double = 0.0,
        val lessThanKilometers: Double = 100_000.0
    ) : Condition<GeoCoordinate>() {
        override fun invoke(on: GeoCoordinate): Boolean =
            on.distanceToKilometers(value) in greaterThanKilometers..lessThanKilometers

        override fun toString(): String = ".distanceToKilometers(value) in $greaterThanKilometers..$lessThanKilometers"
    }

    @Serializable
    @SerialName("FullTextSearch")
    public data class FullTextSearch<T>(
        @DoesNotNeedLabel val value: String,
        val requireAllTermsPresent: Boolean = true,
        val levenshteinDistance: Int = 2,
    ) :
        Condition<T>() {
        @OptIn(ExperimentalSerializationApi::class)
        override fun invoke(on: T): Boolean {
            val ser = try {
                if (on != null) {
                    kotlinx.serialization.serializer(on::class, listOf(), false)
                } else null
            } catch(e: Exception) { null }
            if(ser != null && ser.descriptor.kind == StructureKind.CLASS) {
                val fieldNames = ser.descriptor.annotations.filterIsInstance<TextIndex>().firstOrNull()?.fields
                val element = Json.encodeToJsonElement(ser, on) as? JsonObject
                val fromString = element?.let { e -> fieldNames?.joinToString(" ") { fieldPath ->
                    // by Claude: Handle nested field paths like "metadata.category" by traversing the JSON structure
                    val pathParts = fieldPath.split(".")
                    var current: kotlinx.serialization.json.JsonElement? = e
                    for (part in pathParts) {
                        current = (current as? JsonObject)?.get(part)
                        if (current == null) break
                    }
                    when(val p = current) {
                        is JsonPrimitive -> p.content
                        null -> ""
                        else -> p.toString()
                    }
                } } ?: on.toString()
                return TextQuery.fromString(value).fuzzyPresent(fromString, levenshteinDistance)
            } else return TextQuery.fromString(value).fuzzyPresent(on.toString(), levenshteinDistance)
        }

        override fun toString(): String = ".fullTextSearch($value, $requireAllTermsPresent, $levenshteinDistance)"
    }

    @Serializable
    @SerialName("RegexMatches")
    public data class RegexMatches(@DoesNotNeedLabel val pattern: String, val ignoreCase: Boolean = false) :
        Condition<String>() {
        @Transient
        private val regex = Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else setOf())
        override fun invoke(on: String): Boolean = regex.matches(on)
        override fun toString(): String = ".contains(Regex($pattern), $ignoreCase)"
    }

    @Serializable(ConditionIntBitsClearSerializer::class)
    @SerialName("IntBitsClear")
    public data class IntBitsClear(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask == 0
        override fun toString(): String = ".bitsClear(${mask.toString(16)})"
    }

    @Serializable(ConditionIntBitsSetSerializer::class)
    @SerialName("IntBitsSet")
    public data class IntBitsSet(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask == mask
        override fun toString(): String = ".bitsSet(${mask.toString(16)})"
    }

    @Serializable(ConditionIntBitsAnyClearSerializer::class)
    @SerialName("IntBitsAnyClear")
    public data class IntBitsAnyClear(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask < mask
        override fun toString(): String = ".bitsAnyClear(${mask.toString(16)})"
    }

    @Serializable(ConditionIntBitsAnySetSerializer::class)
    @SerialName("IntBitsAnySet")
    public data class IntBitsAnySet(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask > 0
        override fun toString(): String = ".bitsAnySet(${mask.toString(16)})"
    }

    // TODO: Merge collection operations once Khrysalis is fully deprecated
    @Serializable(ConditionListAllElementsSerializer::class)
    @SerialName("ListAllElements")
    public data class ListAllElements<E>(val condition: Condition<E>) : Condition<List<E>>() {
        override fun invoke(on: List<E>): Boolean = on.all { condition(it) }
        override fun toString(): String = ".all { it$condition }"
    }

    @Serializable(ConditionListAnyElementsSerializer::class)
    @SerialName("ListAnyElements")
    public data class ListAnyElements<E>(val condition: Condition<E>) : Condition<List<E>>() {
        override fun invoke(on: List<E>): Boolean = on.any { condition(it) }
        override fun toString(): String = ".any { it$condition }"
    }

    // TODO: Change to empty check
    @Serializable(ConditionListSizesEqualsSerializer::class)
    @SerialName("ListSizesEquals")
    public data class ListSizesEquals<E>(val count: Int) : Condition<List<E>>() {
        override fun invoke(on: List<E>): Boolean = on.size == count
        override fun toString(): String = ".size == $count"
    }

    @Serializable(ConditionSetAllElementsSerializer::class)
    @SerialName("SetAllElements")
    public data class SetAllElements<E>(val condition: Condition<E>) : Condition<Set<E>>() {
        override fun invoke(on: Set<E>): Boolean = on.all { condition(it) }
        override fun toString(): String = ".all { it$condition }"
    }

    @Serializable(ConditionSetAnyElementsSerializer::class)
    @SerialName("SetAnyElements")
    public data class SetAnyElements<E>(val condition: Condition<E>) : Condition<Set<E>>() {
        override fun invoke(on: Set<E>): Boolean = on.any { condition(it) }
        override fun toString(): String = ".any { it$condition }"
    }

    // TODO: Change to empty check
    @Serializable(ConditionSetSizesEqualsSerializer::class)
    @SerialName("SetSizesEquals")
    public data class SetSizesEquals<E>(val count: Int) : Condition<Set<E>>() {
        override fun invoke(on: Set<E>): Boolean = on.size == count
        override fun toString(): String = ".size == $count"
    }

    // TODO: Allow alternate keys once Khrysalis is fully deprecated
    @Serializable(ConditionExistsSerializer::class)
    @SerialName("Exists")
    public data class Exists<V>(val key: String) : Condition<Map<String, V>>() {
        override fun invoke(on: Map<String, V>): Boolean = on.containsKey(key)
        override fun toString(): String = ".containsKey($key)"
    }

    @Serializable
    @SerialName("OnKey")
    public data class OnKey<V>(val key: String, val condition: Condition<V>) :
        Condition<Map<String, V>>() {
        @Suppress("UNCHECKED_CAST")
        override fun invoke(on: Map<String, V>): Boolean = on.containsKey(key) && condition(on[key] as V)
        override fun toString(): String = "[$key]$condition"
    }

    public data class OnField<K, V>(
        val key: SerializableProperty<in K, V>,
        val condition: Condition<V>,
    ) : Condition<K>() {
        override fun invoke(on: K): Boolean = condition(key.get(on))
        override fun toString(): String {
            return if (condition is OnField<*, *>)
                "${key.name}.$condition"
            else
                "${key.name}$condition"
        }
    }

    @Serializable(ConditionIfNotNullSerializer::class)
    @SerialName("IfNotNull")
    public data class IfNotNull<T>(val condition: Condition<T>) : Condition<T?>() {
        override fun invoke(on: T?): Boolean = on != null && condition(on)
        override fun toString(): String = "? $condition"
    }
}

public infix fun <T> Condition<T>.and(other: Condition<T>): Condition.And<T> = Condition.And(listOf(this, other))
public infix fun <T> Condition<T>.or(other: Condition<T>): Condition.Or<T> = Condition.Or(listOf(this, other))
public operator fun <T> Condition<T>.not(): Condition.Not<T> = Condition.Not(this)

public fun <T> Condition.Companion.andNotNull(vararg conditions: Condition<T>?): Condition<T> {
    val list = conditions.toList().filterNotNull()
    return when (list.size) {
        0 -> Condition.Always // vacuous truth
        1 -> list.first()
        else -> Condition.And(list)
    }
}

public fun <T> Condition.Companion.orNotNull(vararg conditions: Condition<T>?): Condition<T> {
    val list = conditions.toList().filterNotNull()
    return when (list.size) {
        0 -> Condition.Never
        1 -> list.first()
        else -> Condition.Or(list)
    }
}

/**
 * Creates a conditional condition that ensures logical consistency between two conditions.
 *
 * If the `if_` condition is true, the `then` condition must also be true for the overall condition to be true.
 * If the `if_` condition is false, the overall condition will always return true, regardless of the `then` condition.
 *
 * This can be thought of as a logical implication: `if_` implies `then`.
 *
 * Useful when building [Mask] or [UpdateRestrictions] when changes only _sometimes_ must meet conditions
 */
public fun <T> Condition.Companion.ifThen(if_: Condition<T>, then: Condition<T>): Condition.Or<T> = (if_ and then) or !if_

/**
 * Creates a conditional condition that evaluates to one of two conditions based on another condition.
 *
 * If the `if_` condition is true, the result will be the `then` condition.
 * If the `if_` condition is false, the result will be the `else_` condition.
 *
 * This can be thought of as a logical ternary operation: `if_ ? then : else_`.
 *
 * Useful when you need to express alternative sets of requirements
 */
public fun <T> Condition.Companion.ifThenElse(if_: Condition<T>, then: Condition<T>, else_: Condition<T>): Condition.Or<T> =
    (if_ and then) or (!if_ and else_)

// TODO: API Recommendation - Add empty/notEmpty collection conditions
//  ListSizesEquals/SetSizesEquals are deprecated. Add explicit isEmpty/isNotEmpty conditions
//  that backends can optimize (e.g., MongoDB $size operator, SQL WHERE array_length(field) > 0)
//
// TODO: API Recommendation - Consider adding case-sensitive string operations
//  StringContains defaults to case-insensitive. Add startsWith/endsWith/exactMatch variants
//  for performance-critical queries where case-insensitive search isn't needed
//
// TODO: API Recommendation - Document RegexMatches backend support matrix
//  Not all backends support regex (e.g., DynamoDB). Consider adding a feature detection API
//  or documenting which backends support which Condition types


