package com.lightningkite.serviceabstractions.database

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.eq
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
data class DualTypeParamTestModel<T, Y>(
    val t:T,
    val y: Y,
    val title: String,
    val body: String,
    val url: String?,
    val otherValue: String
)

class GeneratedPropertyTests {

    @Test
    fun conditionEqualityTest() {

        val condition1 = condition<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<DualTypeParamTestModel<String, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<Int, Long>>, Long>>>>, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, Long>>, Long>>>>>> { it.otherValue.eq("") }
        val condition2 = condition<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<DualTypeParamTestModel<String, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<Int, Long>>, Long>>>>, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, Long>>, Long>>>>>> { it.otherValue.eq("") }

        assertEquals(condition1, condition2)

    }
}