package com.lightningkite.services.files

import com.lightningkite.services.data.TypedData
import kotlin.time.Duration

/**
 * A reference to a location (which may or may not exist) within a [PublicFileSystem].
 *
 * `ExternalFile` is a thin `(fileSystem, path)` pair - all operations delegate to the
 * owning [fileSystem]. Two `ExternalFile`s are equal if they point at the same path in
 * the same file system.
 *
 * ```kotlin
 * val fs: PublicFileSystem = ...
 * val file = fs.root.then("uploads/document.pdf")
 *
 * file.put(TypedData(pdfBytes, MediaType.Application.Pdf))
 * val content = file.get()  // null if it doesn't exist
 * file.delete()
 * ```
 *
 * @see PublicFileSystem
 * @see ExternalPath
 */
public data class ExternalFile(public val fileSystem: PublicFileSystem, public val path: ExternalPath) {
    override fun toString(): String = "${fileSystem.name}:${path}"

    public val name: String get() = path.name
    public val extension: String get() = path.extension
    public val nameWithoutExtension: String get() = path.nameWithoutExtension

    /**
     * The parent file, or `null` if this is the file system's root.
     */
    public val parent: ExternalFile? get() = path.parent?.let { ExternalFile(fileSystem, it) }

    public fun then(vararg parts: String): ExternalFile = ExternalFile(fileSystem, path.then(*parts))
    public inline fun withAlteredName(alter: (String) -> String): ExternalFile =
        ExternalFile(fileSystem, path.withAlteredName(alter))

    public fun withAlteredExtension(alter: (String) -> String): ExternalFile =
        withAlteredName { it.substringBeforeLast('.') + "." + alter(it.substringAfterLast('.')) }

    public suspend fun list(): List<ExternalFile>? = fileSystem.list(path)?.map { ExternalFile(fileSystem, it) }
    public suspend fun head(): FileInfo? = fileSystem.head(path)
    public suspend fun put(content: TypedData): Unit = fileSystem.put(path, content)
    public suspend fun get(): TypedData? = fileSystem.get(path)
    public suspend fun copyTo(other: ExternalFile): Unit = fileSystem.copyTo(path, other)
    public suspend fun moveTo(other: ExternalFile): Unit = fileSystem.moveTo(path, other)
    public suspend fun delete(): Unit = fileSystem.delete(path)

    /**
     * A signed URL for this file that can be used to access it securely.
     *
     * @param timeout How long the URL should remain valid. Defaults to the file system's
     * configured signed-URL duration.
     */
    public fun signUrl(timeout: Duration? = null): String = fileSystem.signUrl(path, timeout)

    /**
     * A signed URL for this file using the file system's default signed-URL duration.
     *
     * For file systems without signing, this may be identical to [url].
     */
    public val signedUrl: String get() = signUrl(null)

    /**
     * Generates a signed URL that can be used to upload content to this file.
     */
    public fun uploadUrl(timeout: Duration): String = fileSystem.uploadUrl(path, timeout)

    /**
     * The internal URL for this file (may be unsigned).
     *
     * This URL is typically used server-side and may not be suitable for sharing with clients.
     */
    @Deprecated("Use signedUrl for external usage, and serializable otherwise")
    public val url: String get() = fileSystem.url(path)
}
