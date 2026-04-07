// by Claude - Comprehensive serialization loop test for all Condition and Modification types
package com.lightningkite.services.database

import com.lightningkite.GeoCoordinate
import com.lightningkite.services.database.*
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
        "RawStringContains (also: Search, StringContains)" to condition { it.email contains "test" },
        "RegexMatches" to condition { it.string.condition { Condition.RegexMatches("test.*pattern", ignoreCase = true) } },

        // --- Full-Text Search ---
        "FullTextSearch" to condition { it.fullTextSearch("search terms", levenshteinDistance = 2) },

        // --- Int Bitwise Conditions ---
        "IntBitsClear" to condition { it.int allClear 0x0F },
        "IntBitsSet" to condition { it.int allSet 0x0F },
        "IntBitsAnyClear" to condition { it.int anyClear 0x0F },
        "IntBitsAnySet" to condition { it.int anySet 0x0F },

        // --- List Collection Conditions ---
        "ListAllElements (also: SetAllElements, AllElements)" to condition { it.list all { e -> e gt 0 } },
        "ListAnyElements (also: SetAnyElements, AnyElements)" to condition { it.list any { e -> e eq 42 } },
        "ListSizesEquals (also: SetSizesEquals, SizesEquals) - DEPRECATED" to condition { it.list sizesEquals 5 },

        // --- Set Collection Conditions ---
        "SetAllElements (also: ListAllElements, AllElements)" to condition { it.set all { e -> e gt 0 } },
        "SetAnyElements (also: ListAnyElements, AnyElements)" to condition { it.set any { e -> e eq 42 } },
        "SetSizesEquals (also: ListSizesEquals, SizesEquals) - DEPRECATED" to condition { it.set sizesEquals 3 },

        // --- Map Conditions ---
        "Exists" to condition { it.map containsKey "myKey" },
        "OnKey" to condition { it.map.condition { Condition.OnKey("myKey", Condition.Equal(42)) } },

        // --- Nested/Nullable Conditions ---
        "OnField (uses field name as serial key)" to condition { it.embedded.condition { e -> e.value1 eq "test" } },
        "IfNotNull" to condition { it.intNullable.notNull eq 42 },
    )

    // GeoDistance tested separately since it uses GeoCoordinate type directly
    val geoDistanceCondition: Condition<GeoCoordinate> = Condition.GeoDistance(
        value = GeoCoordinate(37.7749, -122.4194),
        greaterThanKilometers = 0.0,
        lessThanKilometers = 100.0
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

        // --- String Modifications ---
        "AppendString" to modification { it -> it.string += " appended" },
        "AppendRawString (also: AppendString)" to modification { it -> it.email += ".appended" },

        // --- List Modifications ---
        "ListAppend (also: AppendList)" to modification { it -> it.list += listOf(1, 2, 3) },
        "ListRemove (also: Remove)" to modification { it -> it.list removeAll { e -> e gt 10 } },
        "ListRemoveInstances (also: RemoveInstances)" to modification { it -> it.list -= listOf(1, 2, 3) },
        "ListDropFirst (also: DropFirst)" to modification { it -> it.list.dropFirst() },
        "ListDropLast (also: DropLast)" to modification { it -> it.list.dropLast() },
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
        "SetDropFirst" to modification { it -> it.set.dropFirst() },
        "SetDropLast" to modification { it -> it.set.dropLast() },
        "SetPerElement" to modification { it -> it.set forEach { e -> e += 1 } },
        "SetPerElement with condition" to modification { it ->
            it.set.forEachIf(
                condition = { e -> e gt 5 },
                modification = { e -> e += 10 }
            )
        },

        // --- Map Modifications ---
        "Combine" to modification { it -> it.map += mapOf("key1" to 1, "key2" to 2) },
        "ModifyByKey" to modification { it ->
            it.map modifyByKey mapOf(
                "key1" to { e -> e += 10 },
                "key2" to { e -> e assign 99 }
            )
        },
        "RemoveKeys" to modification { it -> it.map removeKeys setOf("key1", "key2") },

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
        loop("Complex Condition", condition<LargeTestModel> {
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
            it.string += " modified"
            it.list += listOf(99)
            it.embedded.value1 assign "updated"
        })
    }

    @Test
    fun queryDeserialization() {
        // Verify Query deserialization works with condition serial names
        val query = Json.decodeFromString<Query<LargeTestModel>>("""
            {
              "condition": {
                "And": [
                  {
                    "email": {
                      "RawStringContains": {
                        "value": "lightning"
                      }
                    }
                  }
                ]
              },
              "orderBy": [
                "_id"
              ]
            }
        """.trimIndent())
        println("Query deserialization: $query")
    }

    // Helper to create conditions with the LargeTestModel type
    private inline fun condition(crossinline setup: (DataClassPath<LargeTestModel, LargeTestModel>) -> Condition<LargeTestModel>): Condition<LargeTestModel> =
        condition<LargeTestModel>(setup)

    // Helper to create modifications with the LargeTestModel type
    private inline fun modification(crossinline setup: ModificationBuilder<LargeTestModel>.(DataClassPath<LargeTestModel, LargeTestModel>) -> Unit): Modification<LargeTestModel> =
        modification<LargeTestModel>(setup)
}
