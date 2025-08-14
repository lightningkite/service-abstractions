package com.lightningkite.serviceabstractions.database

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.TextIndex
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.get
import com.lightningkite.services.database.set
import com.lightningkite.services.database.tryChildSerializers
import com.lightningkite.services.test.performance
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Contextual
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class KxSerializationFieldAccessTest {

    @GenerateDataClassPaths
    @Serializable
    @TextIndex(["string", "embedded.value1"])
    data class LargeTestModel(
        override val _id: Uuid = Uuid.random(),
        var boolean: Boolean = false,
        var byte: Byte = 0,
        var short: Short = 0,
        @Index var int: Int = 0,
        var long: Long = 0,
        var float: Float = 0f,
        var double: Double = 0.0,
        var char: Char = ' ',
        var string: String = "",
        var uuid: Uuid = Uuid.NIL,
        @Contextual var instant: Instant = Instant.fromEpochMilliseconds(0L),
        var list: List<Int> = listOf(),
        var listEmbedded: List<ClassUsedForEmbedding> = listOf(),
        var set: Set<Int> = setOf(),
        var setEmbedded: Set<ClassUsedForEmbedding> = setOf(),
        var map: Map<String, Int> = mapOf(),
        var embedded: ClassUsedForEmbedding = ClassUsedForEmbedding(),
        var booleanNullable: Boolean? = null,
        var byteNullable: Byte? = null,
        var shortNullable: Short? = null,
        var intNullable: Int? = null,
        var longNullable: Long? = null,
        var floatNullable: Float? = null,
        var doubleNullable: Double? = null,
        var charNullable: Char? = null,
        var stringNullable: String? = null,
        var uuidNullable: Uuid? = null,
        @Contextual var instantNullable: Instant? = null,
        var listNullable: List<Int>? = null,
        var mapNullable: Map<String, Int>? = null,
        var embeddedNullable: ClassUsedForEmbedding? = null,
    ) : HasId<Uuid> {
        companion object
    }

    @GenerateDataClassPaths
    @Serializable
    data class ClassUsedForEmbedding(
        var value1:String = "default",
        var value2:Int = 1
    )

    @Serializable
    data class UnreasonablyComplex(
        val string: String = "",
        val int: Int = 0,
        val list: List<String> = listOf(),
        val map: Map<String, Int> = mapOf(),
        val nested: UnreasonablyComplex? = null,
        val listNested: List<UnreasonablyComplex> = listOf(),
        val mapNested: Map<String, UnreasonablyComplex> = mapOf(),
    )

    @Serializable
    data class StupidGenericBox<T>(val value: T)

    @Serializable
    data class ContextualCopyCheck(@Contextual val date: LocalDate)

    @Test fun getPerf() {
        val getter = { it: UnreasonablyComplex -> it.nested }
        val ser = UnreasonablyComplex.serializer()
        val child = ser.nullable
        val altGetter = { it: UnreasonablyComplex -> ser.get(it, 4, child) }

        var local: UnreasonablyComplex? = null
        val sample = UnreasonablyComplex(nested = UnreasonablyComplex(string = "asdf"))
        performance {
            local = getter(sample)
        }.also { println("native: $it") }
        performance {
            local = altGetter(sample)
        }.also { println("encall: $it") }
    }
    @Test fun setPerf() {
        val setter = { it: UnreasonablyComplex, value: UnreasonablyComplex? -> it.copy(nested = value) }
        val ser = UnreasonablyComplex.serializer()
        val child = ser.nullable
        val altSetter = { it: UnreasonablyComplex, value: UnreasonablyComplex?  -> ser.set(it, 4, child, value) }

        var local: UnreasonablyComplex? = null
        val sample = UnreasonablyComplex(nested = UnreasonablyComplex(string = "asdf"))
        val newvalue = UnreasonablyComplex(string = "fdas")
        performance {
            local = setter(sample, newvalue)
        }.also { println("copy: $it") }
        performance {
            local = altSetter(sample, newvalue)
        }.also { println("encdec: $it") }
    }
    @Test fun contextual() {
        val ser = ContextualCopyCheck.serializer()
        val input = ContextualCopyCheck(LocalDate(2023, 1, 1))
        val output = ContextualCopyCheck(LocalDate(2024, 2, 2))
        assertEquals(output, ser.set(input, 0, ContextualSerializer(LocalDate::class), output.date))
    }

    @Test fun allOperations() {
        val default = LargeTestModel()
        val altValue = LargeTestModel(
            boolean = false,
            byte = 1,
            short = 1,
            int = 1,
            long = 1,
            float = 1f,
            double = 1.0,
            char = 'A',
            string = "ASDF",
            uuid = Uuid.fromLongs(1L, 1L),
            instant = Instant.fromEpochMilliseconds(100000L),
            list = listOf(1),
            listEmbedded = listOf(ClassUsedForEmbedding()),
            set = setOf(1),
            setEmbedded = setOf(ClassUsedForEmbedding()),
            map = mapOf("asdf" to 1),
            embedded = ClassUsedForEmbedding(),
            booleanNullable = false,
            byteNullable = 1,
            shortNullable = 1,
            intNullable = 1,
            longNullable = 1,
            floatNullable = 1f,
            doubleNullable = 1.0,
            charNullable = 'A',
            stringNullable = "ASDF",
            uuidNullable = Uuid.fromLongs(1L, 1L),
            instantNullable = Instant.fromEpochMilliseconds(100000L),
            listNullable = listOf(1),
            mapNullable = mapOf("asdf" to 1),
            embeddedNullable = ClassUsedForEmbedding()
        )
        val serializer = LargeTestModel.serializer()
        for (index in 0..<serializer.descriptor.elementsCount) {
            println("---${serializer.descriptor.getElementName(index)}---")
            val alt = serializer.get(altValue, index, serializer.tryChildSerializers()!![index])
            println("Setting field to $alt")
            println(serializer.set(default, index, serializer.tryChildSerializers()!![index] as KSerializer<Any?>, alt))
        }
    }
}

