@file:Suppress("PackageDirectoryMismatch")
// by Claude - Comprehensive serialization loop test for all Condition and Modification types
package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.data.GeoCoordinate
import com.lightningkite.services.data.Length.Companion.miles
import com.lightningkite.services.data.toEmailAddress
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SerializationLoopTest {

    inline fun <reified T> loop(name: String, original: T) {
        val json = Json.encodeToString(original)
        println("$name: $json")
        assertEquals(original, Json.decodeFromString(json), "Failed for $name")
    }

    // ============================================================
    // ALL CONDITION TYPES WITH SERIAL NAMES
    // ============================================================
    @Suppress("DEPRECATION")
    val allConditions: List<Pair<String, Condition<LargeTestModel>>> = listOf(
        // --- Constant Conditions ---
        "Never" to condition { Condition.Never },
        "Always" to condition { Condition.Always },

        // --- Boolean Logic Conditions ---
        "And" to condition { it.int eq 1 and (it.string eq "test") },
        "Or" to condition { it.int eq 1 or (it.string eq "test") },
        "Not" to condition { !(it.int eq 1) },

        // --- Equality Conditions ---
        "Equal" to condition { it.int eq 42 },
        "NotEqual" to condition { it.int neq 42 },

        // --- Set Membership Conditions ---
        "Inside" to condition { it.int inside setOf(1, 2, 3) },
        "NotInside" to condition { it.int notInside setOf(1, 2, 3) },

        // --- Comparison Conditions (Comparable types) ---
        "GreaterThan" to condition { it.int gt 10 },
        "LessThan" to condition { it.int lt 10 },
        "GreaterThanOrEqual" to condition { it.int gte 10 },
        "LessThanOrEqual" to condition { it.int lte 10 },

        // --- String Conditions ---
        "StringContains (also: Search)" to condition { it.string contains "test" },
        "StringContains through IsRawString.raw" to condition { it.email.raw contains "test" },
        "Equal on an IsRawString" to condition { it.email eq "a@b.com".toEmailAddress() },
        "GreaterThan on an IsRawString" to condition { it.email gt "a@b.com".toEmailAddress() },
        "RegexMatches" to condition {
            it.string.Condition {
                Condition.RegexMatches(
                    "test.*pattern",
                    ignoreCase = true
                )
            }
        },

        // --- Int Bitwise Conditions ---
        "IntBitsClear" to condition { it.int allClear 0x0F },
        "IntBitsSet" to condition { it.int allSet 0x0F },
        "IntBitsAnyClear" to condition { it.int anyClear 0x0F },
        "IntBitsAnySet" to condition { it.int anySet 0x0F },

        // --- List Collection Conditions ---
        "List CollectionAllElements (also: SetAllElements, AllElements)" to condition { it.list all { e -> e gt 0 } },
        "List CollectionAnyElements (also: SetAnyElements, AnyElements)" to condition { it.list any { e -> e eq 42 } },

        // --- Set Collection Conditions ---
        "Set CollectionAllElements (also: ListAllElements, AllElements)" to condition { it.set all { e -> e gt 0 } },
        "Set CollectionAnyElements (also: ListAnyElements, AnyElements)" to condition { it.set any { e -> e eq 42 } },

        // --- Nested/Nullable Conditions ---
        "OnField (uses field name as serial key)" to condition { it.embedded.Condition { e -> e.value1 eq "test" } },
        "IfNotNull" to condition { it.intNullable.notNull eq 42 },
    )

    // GeoDistance tested separately since it uses GeoCoordinate type directly
    val geoDistanceCondition: Condition<GeoCoordinate> = Condition.GeoDistance(
        value = GeoCoordinate(37.7749, -122.4194),
        greaterThan = 0.0.miles,
        lessThan = 100.0.miles
    )

    // ============================================================
    // ALL MODIFICATION TYPES WITH SERIAL NAMES
    // ============================================================
    val allModifications: List<Pair<String, Modification<LargeTestModel>>> = listOf(
        // --- No-op Modification ---
        "Nothing" to modification { /* no modifications */ },

        // --- Chained Modifications ---
        "Chain" to modification { it ->
            it.int assign 1
            it.string assign "test"
        },

        // --- Nullable Modification ---
        "IfNotNull" to modification { it -> it.intNullable.notNull assign 42 },

        // --- Assignment Modification ---
        "Assign" to modification { it -> it.int assign 42 },

        // --- Comparable Coercion Modifications ---
        "CoerceAtMost" to modification { it -> it.int coerceAtMost 100 },
        "CoerceAtLeast" to modification { it -> it.int coerceAtLeast 0 },

        // --- Numeric Modifications ---
        "Increment" to modification { it -> it.int += 5 },
        "Multiply" to modification { it -> it.int *= 2 },

        // --- List Modifications ---
        "ListAppend (also: AppendList)" to modification { it -> it.list += listOf(1, 2, 3) },
        "ListRemove (also: Remove)" to modification { it -> it.list removeAll { e -> e gt 10 } },
        "ListRemoveInstances (also: RemoveInstances)" to modification { it -> it.list -= listOf(1, 2, 3) },
        "ListPerElement (also: PerElement)" to modification { it -> it.list forEach { e -> e += 1 } },
        "ListPerElement with condition" to modification { it ->
            it.list.forEachIf(
                condition = { e -> e gt 5 },
                modification = { e -> e += 10 }
            )
        },

        // --- Set Modifications ---
        "SetAppend (also: AppendSet)" to modification { it -> it.set += setOf(1, 2, 3) },
        "SetRemove" to modification { it -> it.set removeAll { e -> e gt 10 } },
        "SetRemoveInstances" to modification { it -> it.set -= setOf(1, 2, 3) },
        "SetPerElement" to modification { it -> it.set forEach { e -> e += 1 } },
        "SetPerElement with condition" to modification { it ->
            it.set.forEachIf(
                condition = { e -> e gt 5 },
                modification = { e -> e += 10 }
            )
        },

        // --- Nested Field Modification ---
        "OnField (uses field name as serial key)" to modification { it -> it.embedded.value1 assign "new value" },
    )

    // ============================================================
    // TESTS
    // ============================================================

    @Test
    fun allConditionTypes() {
        println("=== CONDITION TYPES ===")
        for ((name, cond) in allConditions) {
            loop(name, cond)
        }

        // GeoDistance uses a different type
        println("--- GeoCoordinate Condition ---")
        loop("GeoDistance", geoDistanceCondition)
    }

    @Test
    fun allModificationTypes() {
        println("=== MODIFICATION TYPES ===")
        for ((name, mod) in allModifications) {
            loop(name, mod)
        }
    }

    @Test
    fun complexConditionExample() {
        // Complex example combining multiple condition types
        loop("Complex Condition", Condition<LargeTestModel> {
            (it.int gt 0 and (it.int lt 100)) or
                    (it.string contains "special") and
                    !(it.boolean eq true)
        })
    }

    @Test
    fun complexModificationExample() {
        // Complex example combining multiple modification types
        loop("Complex Modification", modification<LargeTestModel> { it ->
            it.int += 1
            it.int coerceAtMost 100
            it.list += listOf(99)
            it.embedded.value1 assign "updated"
        })
    }

    // Helper to create conditions with the LargeTestModel type
    private inline fun condition(crossinline setup: (Lens<LargeTestModel, LargeTestModel>) -> Condition<LargeTestModel>): Condition<LargeTestModel> =
        Condition<LargeTestModel>(setup)

    // Helper to create modifications with the LargeTestModel type
    private inline fun modification(crossinline setup: ModificationBuilder<LargeTestModel>.(Lens<LargeTestModel, LargeTestModel>) -> Unit): Modification<LargeTestModel> =
        modification<LargeTestModel>(setup)
}
