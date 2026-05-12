package com.lightningkite.services.database

import com.lightningkite.services.data.GenerateDataClassPaths
import kotlinx.serialization.Serializable
import kotlin.test.Test

@GenerateDataClassPaths
@Serializable
data class Sample(
    val x: Int,
    val y: String? = null,
    val z: List<String> = listOf(),
) {
    @GenerateDataClassPaths
    @Serializable
    data class Nested(val value: Int) {
        @Serializable
        @GenerateDataClassPaths
        data class DoubleNested(val value: Int)
    }
}

@GenerateDataClassPaths
@Serializable
data class SampleGeneric<A, B : Comparable<B>>(
    val x: A,
    val y: B,
    val z: List<String> = listOf(),
) {
    @GenerateDataClassPaths
    @Serializable
    data class Nested(val value: Int) {
        @Serializable
        @GenerateDataClassPaths
        data class DoubleNested(val value: Int)
    }
}

class FieldGenerationTest {
    @Test
    fun ifSyntaxWorksWereOk() {
        condition<Sample> { it.x gt 4 }
        condition<SampleGeneric<Int, String>> { it.x gt 4 }
        condition<Sample.Nested> { it.value eq 0 }
        condition<SampleGeneric.Nested> { it.value eq 0 }
        condition<Sample.Nested.DoubleNested> { it.value eq 0 }
        condition<SampleGeneric.Nested.DoubleNested> { it.value eq 0 }
    }
}
