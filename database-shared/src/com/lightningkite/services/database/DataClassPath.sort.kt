package com.lightningkite.services.database

internal val <T> DataClassPathPartial<T>.compare: Comparator<T> get() = compareBy { this.getAny(it) as? Comparable<*> }
