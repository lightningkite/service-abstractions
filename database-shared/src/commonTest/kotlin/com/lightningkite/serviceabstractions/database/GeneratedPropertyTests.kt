package com.lightningkite.serviceabstractions.database

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.eq
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
data class DualTypeParamTestModel<T, Y>(
    val t: T,
    val y: Y,
    val title: String,
    val body: String,
    val url: String?,
    val otherValue: String,
)
@Serializable
@GenerateDataClassPaths
data class DualTypeParamTestModelPart2<T, Y>(
    val t: T,
    val y: Y,
    val title: String,
    val body: String,
    val url: String?,
    val otherValue: String,
)

class GeneratedPropertyTests {

    @Test
    fun conditionEqualityTest() {

        // Simple Equal
        var condition1: Condition<*> = condition<DualTypeParamTestModel<Uuid, Int>> { it.otherValue.eq("") }
        var condition2: Condition<*> = condition<DualTypeParamTestModel<Uuid, Int>> { it.otherValue.eq("") }
        assertEquals(condition1, condition2)

        // Simple Not Equal
        condition1 = condition<DualTypeParamTestModel<Uuid, Long>> { it.otherValue.eq("") }
        condition2 = condition<DualTypeParamTestModel<Uuid, Uuid>> { it.otherValue.eq("") }
        assertNotEquals(condition1 as Any, condition2 as Any)

        // Complicated Equal
        condition1 =
            condition<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<DualTypeParamTestModel<String, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<Int, Long>>, Long>>>>, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, Long>>, Long>>>>>> {
                it.otherValue.eq("")
            }
        condition2 =
            condition<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<DualTypeParamTestModel<String, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<Int, Long>>, Long>>>>, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, Long>>, Long>>>>>> {
                it.otherValue.eq("")
            }
        assertEquals(condition1, condition2)

        // Complicated Not Equal
        condition1 =
            condition<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<DualTypeParamTestModel<String, DualTypeParamTestModel<Long, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<Int, Long>>, Long>>>>, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, Long>>, Long>>>>>> {
                it.otherValue.eq("")
            }
        condition2 =
            condition<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<DualTypeParamTestModel<String, DualTypeParamTestModel<Int, DualTypeParamTestModel<Short, DualTypeParamTestModel<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<Int, Long>>, Long>>>>, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, Long>>, Long>>>>>> {
                it.otherValue.eq("")
            }
        assertNotEquals(condition1 as Any, condition2 as Any)

        // Extreme Differences
        condition1 = condition<DualTypeParamTestModel<Uuid, Long>> { it.otherValue.eq("") }
        condition2 =
            condition<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<DualTypeParamTestModel<String, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<Int, Long>>, Long>>>>, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, Long>>, Long>>>>>> {
                it.otherValue.eq("")
            }
        assertNotEquals(condition1 as Any, condition2 as Any)

        // Different Models
        condition1 = condition<DualTypeParamTestModelPart2<Uuid, Long>> { it.otherValue.eq("") }
        condition2 = condition<DualTypeParamTestModel<Uuid, Long>> { it.otherValue.eq("") }
        assertNotEquals(condition1 as Any, condition2 as Any)

    }
}