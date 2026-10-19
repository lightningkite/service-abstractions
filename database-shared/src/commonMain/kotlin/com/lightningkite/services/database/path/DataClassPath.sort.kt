@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

internal val <T> DataClassPathPartial<T>.comparator: Comparator<T> get() = compareBy { this.getAny(it) as? Comparable<*> }
