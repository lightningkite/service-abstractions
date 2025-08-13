package com.lightningkite.serviceabstractions.database

import com.lightningkite.services.database.get
import com.lightningkite.services.database.set
import com.lightningkite.services.test.performance
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Contextual
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlin.test.Test
import kotlin.test.assertEquals

class KxSerializationFieldAccessTest {

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
}

