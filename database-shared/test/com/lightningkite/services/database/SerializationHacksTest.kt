@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import kotlinx.serialization.*
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmInline
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.fail
import kotlin.uuid.Uuid

class SerializationHacksTest {
    @OptIn(InternalSerializationApi::class)
    @Test
    fun test() {
        try {
            @Suppress("UNCHECKED_CAST")
            val x = (SampleBox.serializer(NothingSerializer()) as GeneratedSerializer<*>)
                .factory()
                .invoke(arrayOf(Int.serializer(), Int.serializer(), Int.serializer()))
                .also {
                    println("Serializer is $it")
                    println("Descriptor info is ${it.descriptor.serialName}")
                } as KSerializer<SampleBox<Int>>
            Json.encodeToString(x, SampleBox(1)).also { println(it) }
        } catch (e: PlatformNotSupportedError) {
            println("Skipping test due to lack of serialization features.")
        }
    }


    private fun KSerializer<*>.display(): String {
        val args = typeParametersSerializersOrNull()
        return when {
            args == null -> "${descriptor.serialName}<???>"
            args.isEmpty() -> descriptor.serialName
            else -> "${descriptor.serialName}<${args.joinToString { it.display() }}>"
        }
    }

    private val fetchTypeExceptions = setOf(    // I'm fine with these returning `null` in reality
        Uuid.serializer().descriptor.serialName
    )

    private fun KSerializer<*>.typeParameterSerializersOrFail(): Array<KSerializer<*>> {
        val params = typeParametersSerializersOrNull()
        if (params == null && descriptor.serialName.removeSuffix("?") !in fetchTypeExceptions) fail("Could not find type parameter serializers for type with serialName `${descriptor.serialName}`")
        return params ?: emptyArray()
    }

    fun KSerializer<*>.deepEquals(other: KSerializer<*>): Boolean {
        if (descriptor.serialName != other.descriptor.serialName) return false

        val myArgs = typeParameterSerializersOrFail()
        val otherArgs = other.typeParameterSerializersOrFail()

        if (myArgs.size != otherArgs.size) return false

        return myArgs.zip(otherArgs).all { (a, b) -> a.deepEquals(b) }
    }

    fun assertEquals(
        myArgs: Array<KSerializer<*>>,
        otherArgs: Array<KSerializer<*>>,
    ) {
        if (myArgs.size != otherArgs.size) fail(
            "Serializers do not have the same number of arguments. Expected [${myArgs.joinToString { it.display() }}] but got [${otherArgs.joinToString { it.display() }}]."
        )

        for ((idx, pair) in myArgs.zip(otherArgs).withIndex()) {
            if (!pair.first.deepEquals(pair.second)) fail(
                """
                    Type parameter serializers are not fully equivalent. 
                    
                    At index $idx: Expected `${pair.first.display()}` but got `${pair.second.display()}`
                    
                    Expected Args: [${myArgs.joinToString { it.display() }}]
                    Actual Args: [${otherArgs.joinToString { it.display() }}]
                """.trimIndent()
            )
        }
    }

    fun assertEquals(
        expected: KSerializer<*>,
        actual: KSerializer<*>,
    ) {
        if (expected.descriptor.serialName != actual.descriptor.serialName) fail(
            "Serializers are not for the same type. Expected `${expected.display()}` but got `${actual.display()}`."
        )

        val myArgs = expected.typeParameterSerializersOrFail()
        val otherArgs = actual.typeParameterSerializersOrFail()

        assertEquals(myArgs, otherArgs)
    }

    private fun testTypeParamSerializers(type: KType) {
        val params = type.arguments
            .map { arg ->
                arg.type?.let { kotlinx.serialization.serializer(it) as KSerializer<*> }
                    ?: throw IllegalArgumentException("No star projections")
            }

        val outer = kotlinx.serialization.serializer(type)

        val foundArgs = outer.typeParameterSerializersOrFail()

        assertEquals(params.toTypedArray(), foundArgs)
    }

    private inline fun <reified T> testTypeParamSerializers() = testTypeParamSerializers(typeOf<T>())

    @Test
    fun testTypeParameterSerializers() {
        // Basic single-parameter collections
        testTypeParamSerializers<List<String>>()
        testTypeParamSerializers<List<Int>>()
        testTypeParamSerializers<List<Long>>()
        testTypeParamSerializers<List<Double>>()
        testTypeParamSerializers<List<Boolean>>()
        testTypeParamSerializers<Set<String>>()
        testTypeParamSerializers<Set<Int>>()
        testTypeParamSerializers<LinkedHashSet<String>>()
        testTypeParamSerializers<HashSet<Int>>()

        // Basic maps
        testTypeParamSerializers<Map<Int, String>>()
        testTypeParamSerializers<Map<String, Int>>()
        testTypeParamSerializers<Map<String, String>>()
        testTypeParamSerializers<LinkedHashMap<String, Int>>()
        testTypeParamSerializers<HashMap<Int, String>>()

        // Nested collections
        testTypeParamSerializers<Map<Int, List<String>>>()
        testTypeParamSerializers<List<List<String>>>()
        testTypeParamSerializers<List<Set<Int>>>()
        testTypeParamSerializers<Set<List<String>>>()
        testTypeParamSerializers<Map<String, Set<Int>>>()
        testTypeParamSerializers<Map<String, Map<Int, String>>>()

        // Custom serializable types
        testTypeParamSerializers<SampleBox<String>>()
        testTypeParamSerializers<SampleBox<Int>>()
        testTypeParamSerializers<SampleBox<Double>>()
        testTypeParamSerializers<List<SampleBox<String>>>()
        testTypeParamSerializers<SampleBox<List<String>>>()
        testTypeParamSerializers<SampleBox<SampleBox<String>>>()
        testTypeParamSerializers<Map<String, SampleBox<Int>>>()

        // Pairs
        testTypeParamSerializers<Pair<String, String>>()
        testTypeParamSerializers<Pair<Int, String>>()
        testTypeParamSerializers<Pair<String, Int>>()
        testTypeParamSerializers<Pair<Double, Long>>()
        testTypeParamSerializers<Pair<String, StringWrapper>>()
        testTypeParamSerializers<Pair<List<String>, Set<Int>>>()
        testTypeParamSerializers<Pair<Map<Int, String>, List<Double>>>()
        testTypeParamSerializers<Pair<SampleBox<String>, SampleBox<Int>>>()

        // Triples
        testTypeParamSerializers<Triple<Int, String, Double>>()
        testTypeParamSerializers<Triple<String, String, String>>()
        testTypeParamSerializers<Triple<List<Int>, Set<String>, Map<Int, String>>>()
        testTypeParamSerializers<Triple<SampleBox<Int>, SampleBox<String>, SampleBox<Double>>>()

        // UUID types
        testTypeParamSerializers<Uuid>()
        testTypeParamSerializers<List<Uuid>>()
        testTypeParamSerializers<Set<Uuid>>()
        testTypeParamSerializers<Map<Uuid, String>>()
        testTypeParamSerializers<Map<String, Uuid>>()
        testTypeParamSerializers<Pair<Uuid, String>>()
        testTypeParamSerializers<Pair<String, Uuid>>()
        testTypeParamSerializers<Pair<Uuid, Uuid>>()
        testTypeParamSerializers<Triple<Uuid, Uuid, Uuid>>()
        testTypeParamSerializers<SampleBox<Uuid>>()
        testTypeParamSerializers<Map<Uuid, List<String>>>()

        // Value classes
        testTypeParamSerializers<StringWrapper>()
        testTypeParamSerializers<List<StringWrapper>>()
        testTypeParamSerializers<Set<StringWrapper>>()
        testTypeParamSerializers<Map<String, StringWrapper>>()
        testTypeParamSerializers<SampleBox<StringWrapper>>()
        testTypeParamSerializers<Pair<StringWrapper, StringWrapper>>()

        // Nullable basic types
        testTypeParamSerializers<List<String?>>()
        testTypeParamSerializers<List<Int?>>()
        testTypeParamSerializers<Set<String?>>()
        testTypeParamSerializers<Map<String, Int?>>()
        testTypeParamSerializers<Map<String?, Int>>()
        testTypeParamSerializers<Pair<String?, Int>>()
        testTypeParamSerializers<Pair<String, Int?>>()
        testTypeParamSerializers<Pair<String?, Int?>>()
        testTypeParamSerializers<Triple<String?, Int?, Double?>>()

        // Nullable with UUID
        testTypeParamSerializers<Pair<Uuid, Uuid?>>()
        testTypeParamSerializers<Pair<Uuid?, Uuid>>()
        testTypeParamSerializers<List<Uuid?>>()
        testTypeParamSerializers<Set<Uuid?>>()
        testTypeParamSerializers<Map<Uuid?, String>>()
        testTypeParamSerializers<Map<String, Uuid?>>()

        // Nullable with custom types
        testTypeParamSerializers<SampleBox<String?>>()
        testTypeParamSerializers<List<SampleBox<String>?>>()
        testTypeParamSerializers<SampleBox<StringWrapper?>>()

        // Complex nested nullable types
        testTypeParamSerializers<Map<Int?, Pair<Uuid, String?>>>()
        testTypeParamSerializers<List<Map<String, Int?>>>()
        testTypeParamSerializers<Map<String?, List<Int?>>>()
        testTypeParamSerializers<Pair<List<String?>, Set<Int?>>>()
        testTypeParamSerializers<Triple<List<String?>, Map<Int?, String>, Set<Uuid?>>>()

        // Deeply nested types
        testTypeParamSerializers<List<List<List<String>>>>()
        testTypeParamSerializers<Map<String, Map<String, Map<String, Int>>>>()
        testTypeParamSerializers<SampleBox<SampleBox<SampleBox<String>>>>()
        testTypeParamSerializers<List<Map<String, List<Int>>>>()
        testTypeParamSerializers<Map<String, List<Map<Int, String>>>>()
        testTypeParamSerializers<Pair<List<Map<Int, String>>, Set<List<Int>>>>()

        // Mixed primitive types
        testTypeParamSerializers<Map<Byte, Short>>()
        testTypeParamSerializers<Map<Char, Boolean>>()
        testTypeParamSerializers<Pair<Byte, Float>>()
        testTypeParamSerializers<Triple<Byte, Short, Long>>()
        testTypeParamSerializers<List<Char>>()
        testTypeParamSerializers<Set<Float>>()

        // Collections of pairs/triples
        testTypeParamSerializers<List<Pair<String, Int>>>()
        testTypeParamSerializers<Set<Pair<Int, String>>>()
        testTypeParamSerializers<Map<String, Pair<Int, String>>>()
        testTypeParamSerializers<List<Triple<Int, String, Double>>>()
        testTypeParamSerializers<Pair<Pair<String, Int>, Pair<Double, Long>>>()

        // Edge cases with everything combined
        testTypeParamSerializers<Map<Uuid?, List<SampleBox<String?>>>>()
        testTypeParamSerializers<Pair<Map<String, List<Int?>>, Set<Uuid?>>>()
        testTypeParamSerializers<Triple<List<Uuid?>, Map<String?, SampleBox<Int>>, Set<StringWrapper?>>>()
        testTypeParamSerializers<SampleBox<Pair<List<String?>, Map<Uuid?, Int>>>>()
        testTypeParamSerializers<List<Map<Uuid, Pair<SampleBox<String>, StringWrapper>>>>()

        // Custom types with 2 type parameters
        testTypeParamSerializers<DualContainer<String, Int>>()
        testTypeParamSerializers<DualContainer<Int, String>>()
        testTypeParamSerializers<DualContainer<String, String>>()
        testTypeParamSerializers<DualContainer<Uuid, StringWrapper>>()
        testTypeParamSerializers<DualContainer<List<String>, Set<Int>>>()
        testTypeParamSerializers<DualContainer<Map<String, Int>, List<Double>>>()
        testTypeParamSerializers<DualContainer<SampleBox<String>, SampleBox<Int>>>()
        testTypeParamSerializers<List<DualContainer<String, Int>>>()
        testTypeParamSerializers<DualContainer<String?, Int?>>()
        testTypeParamSerializers<DualContainer<DualContainer<String, Int>, DualContainer<Double, Long>>>()

        // Custom types with 3 type parameters
        testTypeParamSerializers<TripleContainer<String, Int, Double>>()
        testTypeParamSerializers<TripleContainer<Int, Int, Int>>()
        testTypeParamSerializers<TripleContainer<String, String, String>>()
        testTypeParamSerializers<TripleContainer<List<String>, Set<Int>, Map<String, Double>>>()
        testTypeParamSerializers<TripleContainer<SampleBox<String>, SampleBox<Int>, SampleBox<Double>>>()
        testTypeParamSerializers<TripleContainer<Uuid, StringWrapper, String>>()
        testTypeParamSerializers<List<TripleContainer<String, Int, Double>>>()
        testTypeParamSerializers<TripleContainer<String?, Int?, Double?>>()
        testTypeParamSerializers<TripleContainer<DualContainer<String, Int>, SampleBox<String>, StringWrapper>>()
        testTypeParamSerializers<Map<String, TripleContainer<Int, String, Double>>>()

        // Custom types with 4 type parameters
        testTypeParamSerializers<QuadContainer<String, Int, Double, Boolean>>()
        testTypeParamSerializers<QuadContainer<Int, Long, Float, Double>>()
        testTypeParamSerializers<QuadContainer<String, String, String, String>>()
        testTypeParamSerializers<QuadContainer<List<String>, Set<Int>, Map<String, Int>, Pair<String, Int>>>()
        testTypeParamSerializers<QuadContainer<SampleBox<String>, DualContainer<Int, String>, StringWrapper, Uuid>>()
        testTypeParamSerializers<QuadContainer<String?, Int?, Double?, Boolean?>>()
        testTypeParamSerializers<List<QuadContainer<String, Int, Double, Boolean>>>()
        testTypeParamSerializers<QuadContainer<Uuid, Uuid, Uuid, Uuid>>()

        // ComplexWrapper - custom type with nested generics in definition
        testTypeParamSerializers<ComplexWrapper<String, Int>>()
        testTypeParamSerializers<ComplexWrapper<Int, String>>()
        testTypeParamSerializers<ComplexWrapper<Uuid, StringWrapper>>()
        testTypeParamSerializers<ComplexWrapper<String, SampleBox<Int>>>()
        testTypeParamSerializers<List<ComplexWrapper<String, Int>>>()
        testTypeParamSerializers<ComplexWrapper<String?, Int?>>()
        testTypeParamSerializers<ComplexWrapper<DualContainer<String, Int>, TripleContainer<Int, String, Double>>>()

        // NestedGeneric - custom type that uses T in multiple ways
        testTypeParamSerializers<NestedGeneric<String>>()
        testTypeParamSerializers<NestedGeneric<Int>>()
        testTypeParamSerializers<NestedGeneric<Uuid>>()
        testTypeParamSerializers<NestedGeneric<StringWrapper>>()
        testTypeParamSerializers<NestedGeneric<SampleBox<String>>>()
        testTypeParamSerializers<NestedGeneric<List<String>>>()
        testTypeParamSerializers<NestedGeneric<String?>>()
        testTypeParamSerializers<List<NestedGeneric<String>>>()
        testTypeParamSerializers<NestedGeneric<DualContainer<String, Int>>>()

        // MultiLayered - complex nested structure with 3 type parameters
        testTypeParamSerializers<MultiLayered<String, Int, Double>>()
        testTypeParamSerializers<MultiLayered<Int, String, Boolean>>()
        testTypeParamSerializers<MultiLayered<Uuid, StringWrapper, String>>()
        testTypeParamSerializers<MultiLayered<String, SampleBox<Int>, DualContainer<String, Int>>>()
        testTypeParamSerializers<MultiLayered<String?, Int?, Double?>>()
        testTypeParamSerializers<List<MultiLayered<String, Int, Double>>>()

        // Mixing different custom types together
        testTypeParamSerializers<SampleBox<DualContainer<String, Int>>>()
        testTypeParamSerializers<DualContainer<SampleBox<String>, SampleBox<Int>>>()
        testTypeParamSerializers<TripleContainer<SampleBox<String>, DualContainer<Int, String>, ComplexWrapper<String, Int>>>()
        testTypeParamSerializers<ComplexWrapper<DualContainer<String, Int>, TripleContainer<String, Int, Double>>>()
        testTypeParamSerializers<NestedGeneric<DualContainer<String, Int>>>()
        testTypeParamSerializers<MultiLayered<SampleBox<String>, DualContainer<Int, String>, TripleContainer<Int, String, Double>>>()

        // Custom types in standard collections
        testTypeParamSerializers<Map<DualContainer<String, Int>, TripleContainer<String, Int, Double>>>()
        testTypeParamSerializers<Pair<ComplexWrapper<String, Int>, NestedGeneric<String>>>()
        testTypeParamSerializers<Triple<SampleBox<String>, DualContainer<Int, String>, TripleContainer<String, Int, Double>>>()
        testTypeParamSerializers<Set<QuadContainer<String, Int, Double, Boolean>>>()

        // Extreme nesting with custom types
        testTypeParamSerializers<SampleBox<SampleBox<DualContainer<String, Int>>>>()
        testTypeParamSerializers<DualContainer<TripleContainer<String, Int, Double>, QuadContainer<String, Int, Double, Boolean>>>()
        testTypeParamSerializers<TripleContainer<List<Map<String, Int>>, Set<Pair<String, Int>>, ComplexWrapper<String, Int>>>()
        testTypeParamSerializers<List<Map<String, DualContainer<TripleContainer<String, Int, Double>, QuadContainer<Int, String, Double, Boolean>>>>>()
    }
}

@Serializable
data class SampleBox<T>(val value: T)

@Serializable
data class DualContainer<A, B>(val first: A, val second: B)

@Serializable
data class TripleContainer<A, B, C>(val first: A, val second: B, val third: C)

@Serializable
data class QuadContainer<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Serializable
data class ComplexWrapper<K, V>(val data: Map<K, List<V>>)

@Serializable
data class NestedGeneric<T>(val items: List<T>, val metadata: Map<String, T>)

@Serializable
data class MultiLayered<A, B, C>(val outer: List<Map<A, Pair<B, C>>>)

@Serializable
@JvmInline
value class StringWrapper(val value: String)