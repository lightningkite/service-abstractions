package com.lightningkite.serviceabstractions.database

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.gt
import kotlinx.serialization.Serializable

@GenerateDataClassPaths
@Serializable
data class Sample(
    val x: Int,
    val y: String? = null,
    val z: List<String> = listOf()
)
@GenerateDataClassPaths
@Serializable
data class SampleGeneric<A, B: Comparable<B>>(
    val x: A,
    val y: B,
    val z: List<String> = listOf()
)

fun ifSyntaxWorksWereOk() {
    condition<Sample> { it.x gt 4 }
    condition<SampleGeneric<Int, String>> { it.x gt 4 }
}