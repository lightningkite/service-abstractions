package com.lightningkite.serviceabstractions.database

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.eq
import com.lightningkite.services.database.modification
import com.lightningkite.services.database.serializableProperties
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.fail
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

@Serializable
@JvmInline
@GenerateDataClassPaths
value class LongWrapper(val long: Long)

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

    @Test
    fun inlineTest() {
        val ser = LongWrapper.serializer()
        println("Element count: ${ser.descriptor.elementsCount}")

        val properties = ser.serializableProperties

        if (properties == null) fail("No generated properties")
        else {
            assertEquals(1, properties.size)
            println("Properties (${properties.size}): ${properties.joinToString { if (it.inline) "${it.name} (inline)" else it.name }}")
        }

        val prop = LongWrapper.path.long

        assertEquals(1L, prop.get(LongWrapper(1L)))
        assertEquals(LongWrapper(5L), prop.set(LongWrapper(0L), 5L))

        val cond = condition<LongWrapper> { it.long eq 5L }
        val mod = modification<LongWrapper> { it.long assign 5L }

        assertEquals(true, cond(LongWrapper(5L)))
        assertEquals(false, cond(LongWrapper(0L)))
        assertEquals(LongWrapper(5L), mod(LongWrapper(0L)))
    }
}