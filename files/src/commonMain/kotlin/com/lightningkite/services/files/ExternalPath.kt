package com.lightningkite.services.files

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * A path within a [ExternalFileSystem], represented as a list of path segments.
 *
 * The empty path (`ExternalPath(emptyList())`) refers to the root of the file system.
 *
 * Segments are **literal**: a segment holds exactly the text it names, with nothing escaped and
 * nothing forbidden - a segment may contain `/`, `..`, or anything else. Escaping is the job of
 * whoever renders a path outward: [toString] writes the conservative form, and
 * [fromConservativeString] reads it back, so string handling is settled in one place.
 *
 * A consequence is that a backend which resolves paths against a real hierarchy (a local file
 * system, as opposed to S3's flat key space) must reject or escape segments that mean something
 * to it - `..` in particular - when mapping a path onto its own storage.
 *
 * @see ExternalFileSystem
 * @see ExternalFile
 */
@Serializable
@JvmInline
public value class ExternalPath(public val parts: List<String>) {
    public constructor(vararg segments: String) : this(segments.toList())

    /**
     * The last segment of this path, or `""` if this is the root path.
     */
    public val name: String get() = parts.lastOrNull() ?: ""
    public val extension: String get() = name.substringAfterLast('.', "")
    public val nameWithoutExtension: String get() = name.substringBeforeLast('.')

    /**
     * The parent path, or `null` if this is the root path.
     */
    public val parent: ExternalPath? get() = if (parts.isEmpty()) null else ExternalPath(parts.dropLast(1))

    /**
     * Resolves additional segments relative to this path.
     *
     * Each argument is split on `/` and any empty segments are dropped, so
     * `path.then("uploads/images/x.jpg")` and `path.then("uploads", "images", "x.jpg")`
     * produce the same result. Arguments are raw text, not conservative-form strings.
     */
    public fun then(vararg segments: String): ExternalPath =
        ExternalPath(parts + segments.flatMap { it.split('/') }.filter { it.isNotEmpty() })

    public inline fun withAlteredName(alter: (String) -> String): ExternalPath =
        ExternalPath(parts.dropLast(1) + alter(name))

    /**
     * The conservative string form of this path: segments joined with `/`, with every character
     * outside `a-z`, `A-Z`, `0-9`, `-` and `.` written as `_` plus its four lowercase hex digits
     * (the escape character `_` included). A segment of nothing but dots has its first dot escaped,
     * so `.` and `..` never appear as whole segments and the form cannot be read as a traversal.
     *
     * The result contains nothing that needs further escaping in a URL, a query parameter, or a
     * canonical `sf://` reference, and [fromConservativeString] recovers the exact segments -
     * the two are inverses, so no two distinct paths share a rendering and no path has two.
     */
    override fun toString(): String = buildString {
        for (index in parts.indices) {
            if (index != 0) append('/')
            val part = parts[index]
            // '.' is left alone so extensions stay readable, which would let "." and ".." through
            // untouched; escaping the first dot of an all-dots segment closes that.
            val escapeFirst = part.isNotEmpty() && part.all { it == '.' }
            for (charIndex in part.indices) {
                val c = part[charIndex]
                if (c.isUnreserved() && !(escapeFirst && charIndex == 0)) append(c)
                else {
                    append(ESCAPE)
                    // Char.code is at most 0xFFFF, so four digits always suffice.
                    append(c.code.toString(16).padStart(4, '0'))
                }
            }
        }
    }

    public companion object {
        private const val ESCAPE: Char = '_'

        private fun Char.isUnreserved(): Boolean =
            this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '.'

        /**
         * Reads back a path written by [toString]. The empty string is the root path.
         *
         * Only exactly what [toString] emits is accepted: any other character, a truncated escape,
         * or an escape whose digits are not four lowercase hex digits is rejected. Being strict
         * keeps the encoding one-to-one - there is no second spelling of a path, so a reference can
         * neither be smuggled past a comparison nor decoded into something the emitter could never
         * have produced.
         */
        public fun fromConservativeString(string: String): ExternalPath =
            if (string.isEmpty()) ExternalPath(emptyList())
            else ExternalPath(string.split('/').map {
                // toString escapes the first dot of an all-dots segment, so a bare "." or ".." here
                // is something we never wrote - refuse it rather than hand a traversal onward.
                require(it.isEmpty() || !it.all { c -> c == '.' }) { "Invalid file path: unescaped traversal." }
                it.unescape()
            })

        private fun String.unescape(): String = buildString {
            var index = 0
            while (index < this@unescape.length) {
                val c = this@unescape[index]
                when {
                    c == ESCAPE -> {
                        require(index + 4 < this@unescape.length) { "Invalid file path: truncated escape." }
                        var code = 0
                        for (offset in 1..4) code = code * 16 + this@unescape[index + offset].hexDigit()
                        append(code.toChar())
                        index += 5
                    }

                    c.isUnreserved() -> {
                        append(c)
                        index++
                    }

                    else -> throw IllegalArgumentException("Invalid file path: unescaped character.")
                }
            }
        }

        /**
         * Rejects anything but a lowercase hex digit, so that a single character has a single
         * encoding (`toIntOrNull(16)` would additionally accept signs and uppercase).
         */
        private fun Char.hexDigit(): Int = when (this) {
            in '0'..'9' -> this - '0'
            in 'a'..'f' -> this - 'a' + 10
            else -> throw IllegalArgumentException("Invalid file path: malformed escape.")
        }
    }
}
