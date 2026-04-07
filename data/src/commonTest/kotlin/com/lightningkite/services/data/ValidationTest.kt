package com.lightningkite.services.data

import kotlinx.serialization.SerialInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
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

@Serializable
@GenerateDataClassPaths
data class CustomSample(
    @ListContains("hello", "world") val list: List<String> = listOf("hello", "world"),
    @ListContains("unapplied") val intList: List<Int> = listOf(1, 2, 3)
)

@SerialInfo
@ValidationAnnotation
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class ListContains(vararg val values: String)

class ValidationTest {
    var validators = AnnotationValidators()

    inline fun <reified T> assertPasses(item: T) {
        val issues = validators.validateSkipSuspending(Json.serializersModule.serializer<T>(), item)
        if (issues.isNotEmpty()) fail("Validation did not pass: $issues")
    }

    inline fun <reified T> assertFails(item: T, failures: Int = 1) {
        println(validators)
        val issues = validators.validateSkipSuspending(Json.serializersModule.serializer<T>(), item)
        if (issues.size != failures) fail("Validation did not fail. Expected $failures, got ${issues.size}. Found issues: $issues")
        else println("Found issues: $issues")
    }

    @Test
    fun testTypeNameNormalization() {
        fun test(k1: KClass<*>, k2: KClass<*>) {
            println("$k1 -> ${k1.normalizedTypeName()}")
            println("$k2 -> ${k2.normalizedTypeName()}")
            assertEquals(k1.normalizedTypeName(), k2.normalizedTypeName())
        }
        test(MaxLength::class, MaxLength(1)::class)
        test(MaxLength::class, MaxLength(1)::class)
        test(FloatRange::class, FloatRange(0.0, 1.0)::class)
        test(FloatRange::class, FloatRange(0.0, 1.0)::class)
    }

    @Test
    fun test() {
        assertPasses(Sample("ASDFA"))
        assertFails(Sample("ASDFAB"))
        assertPasses(Sample(y = 0))
        assertFails(Sample(y = -1))
        assertFails(Sample(y = 101))
        assertFails(Sample(x = "123456", y = 101, z = List(10) { "a" }), failures = 3)
    }

    @Test
    fun testStrings() {
        println(AnnotationValidators.Standard)
        println(AnnotationValidators())
        println(AnnotationValidators(SerializersModule {  }))
    }

    @Test
    fun testCustomValidators() {
        validators += AnnotationValidators {
            ListContains::class.validatesCollections {
                println("running custom")
                val values = values.toList()
                if (it.firstOrNull() is String) {
                    (it as? List<String>)?.let {
                        if (!it.containsAll(values.toList())) "Does not contain all values: $it !in $values"
                        else null
                    }
                }
                else null
            }
        }

        println(validators)

        assertPasses(CustomSample())
        assertFails(CustomSample(list = listOf("hello")))
    }
}