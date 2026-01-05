package com.lightningkite.services.database.cassandra

import com.lightningkite.services.database.Condition

/**
 * Normalizes conditions by eliminating Not wrappers where possible.
 *
 * This preprocessing step converts negated conditions into their positive
 * equivalents, making more conditions pushable to CQL. For example:
 * - Not(Equal(x)) -> NotEqual(x)
 * - Not(LessThan(x)) -> GreaterThanOrEqual(x)
 * - Not(And(a, b)) -> Or(Not(a), Not(b))  (De Morgan's law)
 *
 * Run this before ConditionAnalyzer to maximize CQL pushability.
 */
public object ConditionNormalizer {

    /**
     * Recursively normalizes a condition tree, pushing Not inward and
     * converting to positive equivalents where possible.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T> normalize(condition: Condition<T>): Condition<T> {
        return when (condition) {
            is Condition.Not -> normalizeNot(condition.condition)
            is Condition.And -> {
                val normalized = condition.conditions.map { normalize(it) }
                if (normalized == condition.conditions) condition
                else Condition.And(normalized)
            }
            is Condition.Or -> {
                val normalized = condition.conditions.map { normalize(it) }
                if (normalized == condition.conditions) condition
                else Condition.Or(normalized)
            }
            is Condition.OnField<*, *> -> normalizeOnField(condition as Condition.OnField<T, Any?>)
            is Condition.IfNotNull<*> -> {
                val normalized = normalize(condition.condition as Condition<Any?>)
                if (normalized === condition.condition) condition
                else Condition.IfNotNull(normalized) as Condition<T>
            }
            else -> condition
        }
    }

    /**
     * Normalizes a Not condition by converting to positive equivalent or
     * applying De Morgan's laws.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> normalizeNot(inner: Condition<T>): Condition<T> {
        return when (inner) {
            // Double negation elimination
            is Condition.Not -> normalize(inner.condition)

            // Direct conversions to positive equivalents
            is Condition.Equal<*> -> Condition.NotEqual(inner.value) as Condition<T>
            is Condition.NotEqual<*> -> Condition.Equal(inner.value) as Condition<T>

            is Condition.Inside<*> -> Condition.NotInside(inner.values) as Condition<T>
            is Condition.NotInside<*> -> Condition.Inside(inner.values) as Condition<T>

            is Condition.GreaterThan<*> -> Condition.LessThanOrEqual(inner.value as Comparable<Any>) as Condition<T>
            is Condition.LessThan<*> -> Condition.GreaterThanOrEqual(inner.value as Comparable<Any>) as Condition<T>
            is Condition.GreaterThanOrEqual<*> -> Condition.LessThan(inner.value as Comparable<Any>) as Condition<T>
            is Condition.LessThanOrEqual<*> -> Condition.GreaterThan(inner.value as Comparable<Any>) as Condition<T>

            // De Morgan's laws: push Not inward
            is Condition.And -> {
                // Not(And(a, b)) -> Or(Not(a), Not(b))
                Condition.Or(inner.conditions.map { normalizeNot(it) })
            }
            is Condition.Or -> {
                // Not(Or(a, b)) -> And(Not(a), Not(b))
                Condition.And(inner.conditions.map { normalizeNot(it) })
            }

            // Always/Never inversion
            is Condition.Always -> Condition.Never as Condition<T>
            is Condition.Never -> Condition.Always as Condition<T>

            // Propagate into OnField
            is Condition.OnField<*, *> -> {
                val normalizedInner = normalizeNot(inner.condition as Condition<Any?>)
                Condition.OnField(inner.key as com.lightningkite.services.database.SerializableProperty<T, Any?>, normalizedInner)
            }

            // Propagate into IfNotNull
            is Condition.IfNotNull<*> -> {
                Condition.IfNotNull(normalizeNot(inner.condition as Condition<Any>)) as Condition<T>
            }

            // Bitwise inversions
            is Condition.IntBitsClear -> Condition.IntBitsAnySet(inner.mask) as Condition<T>
            is Condition.IntBitsSet -> Condition.IntBitsAnyClear(inner.mask) as Condition<T>
            is Condition.IntBitsAnyClear -> Condition.IntBitsSet(inner.mask) as Condition<T>
            is Condition.IntBitsAnySet -> Condition.IntBitsClear(inner.mask) as Condition<T>

            // Collection conditions - push Not inward
            is Condition.ListAllElements<*> -> {
                // Not(All(x)) -> Any(Not(x))
                Condition.ListAnyElements(normalizeNot(inner.condition as Condition<Any?>)) as Condition<T>
            }
            is Condition.ListAnyElements<*> -> {
                // Not(Any(x)) -> All(Not(x))
                Condition.ListAllElements(normalizeNot(inner.condition as Condition<Any?>)) as Condition<T>
            }
            is Condition.SetAllElements<*> -> {
                Condition.SetAnyElements(normalizeNot(inner.condition as Condition<Any?>)) as Condition<T>
            }
            is Condition.SetAnyElements<*> -> {
                Condition.SetAllElements(normalizeNot(inner.condition as Condition<Any?>)) as Condition<T>
            }

            // Map conditions
            is Condition.Exists<*> -> {
                // Not(Exists(key)) - no direct equivalent, keep as Not
                Condition.Not(inner)
            }
            is Condition.OnKey<*> -> {
                // Not(OnKey(k, cond)) -> OnKey(k, Not(cond)) normalized
                Condition.OnKey(inner.key, normalizeNot(inner.condition as Condition<Any?>)) as Condition<T>
            }

            // Conditions without direct negation - keep wrapped
            is Condition.StringContains,
            is Condition.RawStringContains<*>,
            is Condition.RegexMatches,
            is Condition.FullTextSearch<*>,
            is Condition.GeoDistance -> {
                // No positive equivalent exists, keep as Not
                Condition.Not(normalize(inner))
            }

            else -> Condition.Not(normalize(inner))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T, V> normalizeOnField(onField: Condition.OnField<T, V>): Condition<T> {
        val normalizedInner = normalize(onField.condition)
        return if (normalizedInner === onField.condition) {
            onField
        } else {
            Condition.OnField(onField.key, normalizedInner)
        }
    }
}
