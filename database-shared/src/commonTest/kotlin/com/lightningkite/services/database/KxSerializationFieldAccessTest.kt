package com.lightningkite.services.database

import com.lightningkite.services.test.performance
import kotlinx.datetime.LocalDate
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class KxSerializationFieldAccessTest {

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
            val alt = serializer.get(altValue, index, serializer.childSerializersOrNull()!![index])
            println("Setting field to $alt")
            println(serializer.set(default, index, serializer.childSerializersOrNull()!![index] as KSerializer<Any?>, alt))
        }
    }
}

