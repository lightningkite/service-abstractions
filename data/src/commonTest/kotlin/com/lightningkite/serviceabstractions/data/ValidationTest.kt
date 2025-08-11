package com.lightningkite.serviceabstractions.data

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.IntegerRange
import com.lightningkite.services.data.MaxLength
import com.lightningkite.services.data.MaxSize
import com.lightningkite.services.data.Validators
import com.lightningkite.services.data.validateFast
import com.lightningkite.validation.validateFast
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.*

@Serializable
@GenerateDataClassPaths
data class Sample(
    @MaxLength(5) val x: String = "asdf",
    @IntegerRange(0, 100) val y: Int = 4,
    @MaxLength(5) @MaxSize(5) val z: List<String> = listOf(),
)

@Serializable
@GenerateDataClassPaths
data class BadSample(
    @MaxSize(1) val a: String = "fdsa",
    @MaxLength(5) val x: String = "asdf",
)

class ValidationTest {
    val validators = Validators()

    inline fun <reified T> assertPasses(item: T) {
        validators.validateFast(Json.serializersModule.serializer<T>(), item) { fail(it.toString()) }
    }

    inline fun <reified T> assertFails(item: T) {
        var failed = false
        validators.validateFast(Json.serializersModule.serializer<T>(), item) {
            println(it)
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun test() {
        assertPasses(Sample("ASDFA"))
        assertFails(Sample("ASDFAB"))
    }
}