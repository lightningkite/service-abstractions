package com.lightningkite.services.database

public fun String.termShouldUseFuzzySearch(): Boolean {
    return all { it.isLetter() || it == '-' } && length > 3
}