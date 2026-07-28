package com.lightningkite.services.files

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * A path within a [PublicFileSystem], represented as a list of path segments.
 *
 * The empty path (`ExternalPath(emptyList())`) refers to the root of the file system.
 *
 * ## Path Traversal Protection
 *
 * Segments may not be empty, `"."`, or `".."` - this is enforced at construction time,
 * so an [ExternalPath] can never be used to escape the file system's root.
 *
 * @see PublicFileSystem
 * @see ExternalFile
 */
@Serializable
@JvmInline
public value class ExternalPath(public val parts: List<String>) {
    public constructor(vararg segments: String) : this(segments.toList())

    init {
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid file path." }
    }

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
     * produce the same result.
     */
    public fun then(vararg segments: String): ExternalPath =
        ExternalPath(parts + segments.flatMap { it.split('/') }.filter { it.isNotEmpty() })

    public inline fun withAlteredName(alter: (String) -> String): ExternalPath =
        ExternalPath(parts.dropLast(1) + alter(name))

    override fun toString(): String = parts.joinToString("/")
}
