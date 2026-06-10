package com.lightningkite.services

import java.io.File


internal data class Entry(
    val raw: String,
    val rawType: String,
    val group: String = raw.substringBefore('.'),
) {
    val innerName get() = raw.substringAfter(group).trim('.').split('.', '_').joinToString("") { it.capitalize() }.decapitalize()
    val metricKeyType = lookup[rawType] ?: (if(rawType.startsWith('{')) "OfString" else null)
    companion object {
        val lookup = mapOf(
            "int" to "OfLong",
            "boolean" to "OfBoolean",
            "double" to "OfDouble",
            "number" to "OfDouble",
            "string" to "OfString",
            "string[]" to "OfStringList",
        )
    }
}

internal fun main(vararg args: String) {
    val f = File("/Users/jivie/Library/Application Support/JetBrains/IntelliJIdea2026.1/scratches/scratch_573.txt")
    println("public object MetricKeys {")
    f.readLines()
        .filter { it.isNotBlank() }
        .map { Entry(raw = it.substringBefore('|'), rawType = it.substringAfter('|')) }
        .filter { it.metricKeyType != null }
        .groupBy { it.group }
        .entries
        .sortedBy { it.key }
        .map { it.key to it.value.sortedBy { it.raw } }
        .forEach {
            println("    public object ${it.first.capitalize()} {")
            it.second.forEach {
                println("        public val ${it.innerName}: MetricKey.${it.metricKeyType} = MetricKey.${it.metricKeyType}(\"${it.raw}\")")
            }
            println("    }")
        }
    println("}")
}