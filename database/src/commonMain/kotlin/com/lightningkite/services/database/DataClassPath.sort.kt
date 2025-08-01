package com.lightningkite.services.database

val <T> DataClassPathPartial<T>.compare: Comparator<T> get() = compareBy { this.getAny(it) as? Comparable<*> }
