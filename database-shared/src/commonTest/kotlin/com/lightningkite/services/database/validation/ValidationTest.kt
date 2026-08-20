@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database.validation

import com.lightningkite.services.data.*
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.modification
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KClass
import kotlin.reflect.typeOf
import kotlin.test.*

@Serializable
@GenerateDataClassPaths
data class Sample(
    @MaxLength(5) val x: String = "asdf",
    @IntegerRange(0, 100) val y: Int = 4,
    @IntegerRange(0, 100) val yNullable: Int? = 5,
    @MaxLength(5) @MaxSize(5) val z: List<String> = listOf(),
)

@Serializable
enum class TestEnum {
    First, Second, Third
}

@Serializable
@GenerateDataClassPaths
data class BadSample(
    @MaxSize(1) val a: String = "fdsa",
    @MaxLength(5) val x: String = "asdf",
)

@Serializable
@GenerateDataClassPaths
data class ArgSample<T>(
    val value: T,
    @MaxLength(5) val str: String = "asdf",
)

@Serializable
@GenerateDataClassPaths
data class Box<T>(val value: T)

@Serializable
@GenerateDataClassPaths
data class CustomSample(
    @StringListContainsAll("hello", "world") val list: List<String> = listOf("hello", "world"),
    @StringListContainsAll("unapplied") val intList: List<Int> = listOf(1, 2, 3),
    @EnumNotEqualTo(TestEnum.First) val enum: TestEnum = TestEnum.Second,
    @EnumNotEqualTo(TestEnum.First) val enumList: List<TestEnum> = emptyList(),
    @IntegerRange(0, 100) val nullableInt: Int? = 3,
    @SampleXNotContains("xyz") val sample: Sample? = null,
)

@Serializable
@GenerateDataClassPaths
data class TestWarnings(
    @AlwaysPrintsMismatchedTypesWarning val warn: Int = 5,
    @IntegerRange(0, 100) val shouldNotWarn: Int? = null,
    @IntegerRange(0, 100) val shouldNotWarnCascading: Box<Box<Box<Box<Int>>>> = Box(Box(Box(Box(50)))),
    @IntegerRange(0, 100) val shouldWarnCascading: Box<Box<Box<Box<String>>>> = Box(Box(Box(Box("50")))),
)

@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class StringListContainsAll(vararg val values: String)

@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class EnumNotEqualTo(val value: TestEnum)

@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class SampleXNotContains(val value: String)

@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class AlwaysPrintsMismatchedTypesWarning

@Serializable
class NeverUsed

class ValidationTest {
    var validators = AnnotationValidators(Json.serializersModule) + AnnotationValidators {
        validate<AlwaysPrintsMismatchedTypesWarning, NeverUsed> {
            null
        }
    }

    inline fun <reified T> assertPasses(item: T) {
        val issues = validators.validateSkipSuspending(validators.serializersModule.serializer<T>(), item)
        if (issues.isNotEmpty()) fail("Validation did not pass: $issues")
    }

    inline fun <reified T> assertFails(item: T, failures: Int = 1) {
        val issues = validators.validateSkipSuspending(validators.serializersModule.serializer<T>(), item)
        if (issues.size != failures) fail("Validation did not fail as expected. Expected $failures, got ${issues.size}. Found issues: $issues")
        else println("Found issues: $issues")
    }

    /** The paths that [item] produces invalid-annotation-type warnings for. */
    internal inline fun <reified T> warningPaths(item: T): Set<String> =
        validators.validateSkipSuspendingCollectingWarnings(validators.serializersModule.serializer<T>(), item)
            .warnings
            .filterIsInstance<EncodingWarning.InvalidAnnotationType>()
            .mapTo(mutableSetOf()) { it.path }

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

        // annotations cascade through list elements
        assertFails(Sample(z = listOf("123456")))

        assertFails(Sample(x = "123456", y = 101, z = List(10) { "a" }), failures = 3)

        // annotations cascade through nullability
        assertPasses(Sample(yNullable = null))
        assertFails(Sample(yNullable = 101))
    }

    @Test
    fun testWarnings() {
        assertPasses(TestWarnings())

        assertEquals(
            setOf("warn", "shouldWarnCascading"),
            warningPaths(TestWarnings()),
            "Expected warnings only for the annotation with no matching validator and the cascade that " +
                    "bottoms out in the wrong type."
        )
    }

    @Test
    fun testNullValueDoesNotWarn() {
        // A validator registered for `Int` legitimately applies to an `Int?` field - it simply has
        // nothing to check when the value is null, which is not a mistaken application.
        assertFalse("shouldNotWarn" in warningPaths(TestWarnings(shouldNotWarn = null)))
        assertFalse("shouldNotWarn" in warningPaths(TestWarnings(shouldNotWarn = 50)))
    }

    @Test
    fun testCollectWarningsFlag() {
        assertTrue(validators.collectWarnings, "Warnings should be collected by default")

        val quiet = validators.withWarnings(false)
        assertFalse(quiet.collectWarnings)
        assertSame(quiet, quiet.withWarnings(false), "Setting the current value should not copy")

        // Turning warnings off must not change what validation itself reports.
        val serializer = validators.serializersModule.serializer<TestWarnings>()
        assertEquals(
            validators.validateSkipSuspending(serializer, TestWarnings()),
            quiet.validateSkipSuspending(serializer, TestWarnings())
        )
    }

    @Test
    fun testCollectWarningsCombination() {
        // Empty sets, so combining can't trip the duplicate-validator check and we observe the flag alone.
        val loud = EmptyAnnotationValidators()
        val quiet = loud.withWarnings(false)

        // `+` is conservative - warnings survive if either side wants them.
        assertTrue((quiet + loud).collectWarnings)
        assertTrue((loud + quiet).collectWarnings)
        assertTrue((loud + loud).collectWarnings)
        assertFalse((quiet + quiet).collectWarnings)

        // `overwriteWith` takes the right-hand setting, like every other part of the merge.
        assertFalse((loud overwriteWith quiet).collectWarnings)
        assertTrue((quiet overwriteWith loud).collectWarnings)
    }

    @Test
    fun testStrings() {
        println(AnnotationValidators.Standard)
        println(AnnotationValidators())
        println(AnnotationValidators(SerializersModule { }))
        println(AnnotationValidators(SerializersModule { }).prettyPrint(qualified = true))
        println(AnnotationValidators(SerializersModule { }).prettyPrint(qualified = false))
    }

    @Test
    fun testModificationValidation() {
        assertPasses(Modification.Assign(Sample("ASDFA")))
        assertFails(Modification.Assign(Sample("ASDFAB")))
        assertPasses(modification<Sample> { it.x assign "ASDFA" })
        assertFails(modification<Sample> { it.x assign "ASDFAB" })
    }

    @Test
    fun testNullableValidation() {
        validators += AnnotationValidators {
            validate<SampleXNotContains, Sample> {
                if (it.x.contains(value, ignoreCase = true)) "x Cannot contain '$value'"
                else null
            }
        }

        assertPasses<Sample?>(Sample("ASDFA"))
        assertFails<Sample?>(Sample("ASDFAB"))

        assertPasses(CustomSample(sample = Sample()))
        assertFails(CustomSample(sample = Sample("xyz")))

        assertPasses(modification<CustomSample> { it.sample assign Sample() })
        assertFails(modification<CustomSample> { it.sample assign Sample("xyz") })
    }

    @Test
    fun testCustomValidators() {
        validators += AnnotationValidators {
            validate<StringListContainsAll, List<String>> {
                if (!it.containsAll(values.toList())) "Does not contain all values: $it !in ${values.contentToString()}"
                else null
            }
            validate<EnumNotEqualTo, TestEnum> {
                if (it == value) "Cannot be $value"
                else null
            }
            validate<IntegerRange, Int?> {  // specific null-override
                when (it) {
                    null -> "Cannot be null"        // this is incredibly stupid, never do this in reality
                    !in min..max -> "Out of range"
                    else -> null
                }
            }
        }

        println(validators)

        assertPasses(CustomSample())
        assertFails(CustomSample(list = listOf("hello")))

        assertFails(CustomSample(enum = TestEnum.First))
        assertPasses(CustomSample(enumList = listOf(TestEnum.Second)))
        assertFails(CustomSample(enumList = listOf(TestEnum.First)))

        assertPasses(CustomSample(nullableInt = 50))
        assertFails(CustomSample(nullableInt = null))
        assertFails(CustomSample(nullableInt = 101))
    }

    inline fun <reified T> match(descriptions: List<SerialKType>) {
        val type = typeOf<T>()
        val description = SerialKType(type, validators.serializersModule)
        val serializer = validators.serializersModule.serializer(type)

        val typeMatches = descriptions.filter { it.matches(description) }
        val serMatches = descriptions.filter { it.matches(serializer) }

        assertEquals(
            typeMatches, serMatches,
            """
                Matches are not equal for $description
                Type Matches:       ${typeMatches.joinToString()}
                Serializer Matches: ${serMatches.joinToString()}
            """.trimIndent()
        )

        println("\nMatches for $description")
        println(
            serMatches
                .map { it to it.generality() }
                .sortedBy { it.second }
                .joinToString("\n") { "- ${it.first} (${it.second})" }
        )
    }

    @Test
    fun testGeneralityAlgorithm() {
        val descriptions = listOf(
            serialKTypeOf<Int>(),
            serialKTypeOf<List<Map<*, *>>>(),
            serialKTypeOf<List<Map<List<Int>, *>>>(),
            serialKTypeOf<List<Map<*, List<Int>>>>(),
            serialKTypeOf<List<*>>(),
            serialKTypeOf<Map<*, *>>(),
            serialKTypeOf<Map<*, Map<*, *>>>(),
            serialKTypeOf<Map<*, Map<*, List<*>>>>(),
            serialKTypeOf<Map<*, Map<Int, List<*>>>>(),
            serialKTypeOf<Map<*, Map<Pair<Int, *>, List<*>>>>(),
            serialKTypeOf<Map<*, Map<Pair<*, *>, List<*>>>>(),
            serialKTypeOf<Map<*, Map<Pair<*, ArgSample<*>>, List<*>>>>(),
            serialKTypeOf<Map<*, Map<Pair<*, ArgSample<Int>>, *>>>(),
            serialKTypeOf<List<Triple<*, Pair<Int, Int>, *>>>(),
            serialKTypeOf<List<ArgSample<*>?>>(),
            serialKTypeOf<List<ArgSample<*>?>>(),
        )

        println("\nSorted:")
        println(
            descriptions
                .map { it to it.generality() }
                .sortedBy { it.second }
                .joinToString("\n") { "- ${it.first} (${it.second})" }
        )

        match<Map<Int, Map<Int, List<String>>>>(descriptions)
        match<Map<String, Map<Pair<Int, Int>, String>>>(descriptions)
        match<Map<Int, Map<Pair<ArgSample<Int>, String>, List<String>>>>(descriptions)
        match<Map<Int, Map<Pair<ArgSample<ArgSample<Int>>, String>, List<String>>>>(descriptions)
        match<Map<Int, Map<Pair<String, ArgSample<Int>>, Map<Int, Int>>>>(descriptions)
        match<List<Triple<Int, Pair<Int, Int>, Int>>>(descriptions)
        match<List<ArgSample<Int>?>>(descriptions)
    }

    @Test
    fun testMatching() {
        assertTrue { serialKTypeOf<String>().matches(serialKTypeOf<String>()) }
    }
}