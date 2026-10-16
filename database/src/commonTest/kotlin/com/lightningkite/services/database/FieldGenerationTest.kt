package com.lightningkite.services.database

import com.lightningkite.services.data.Queryable
import kotlinx.serialization.Serializable

@Queryable
data class Sample(
    val x: Int,
    val y: String? = null,
    val z: List<String> = listOf(),
)

@Queryable
data class SampleGeneric<A, B : Comparable<B>>(
    val x: A,
    val y: B,
    val z: List<String> = listOf(),
)

fun ifSyntaxWorksWereOk() {
    condition<Sample> { it.x gt 4 }
    condition<SampleGeneric<Int, String>> { it.x gt 4 }
}