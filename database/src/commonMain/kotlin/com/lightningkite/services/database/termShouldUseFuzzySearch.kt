package com.lightningkite.services.database

fun String.termShouldUseFuzzySearch(): Boolean {
    return all { it.isLetter() || it == '-' } && length > 3
}