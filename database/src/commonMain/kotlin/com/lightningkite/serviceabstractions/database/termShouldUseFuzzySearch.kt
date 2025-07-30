package com.lightningkite.serviceabstractions.database

fun String.termShouldUseFuzzySearch(): Boolean {
    return all { it.isLetter() || it == '-' } && length > 3
}