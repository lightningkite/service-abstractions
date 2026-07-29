package com.lightningkite.services.files

import com.lightningkite.services.data.*
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.kfile.temporary
import kotlinx.io.files.SystemFileSystem
import kotlin.uuid.Uuid

/**
 * Downloads a FileObject to a local temporary file.
 *
 * @param file Optional KFile to download to. If null, a temporary file will be created.
 * @return The KFile containing the downloaded content, or null if the FileObject doesn't exist
 */
public suspend fun ExternalFile.download(
    file: KFile?,
): KFile? = get()?.let { download(file ?: SystemFileSystem.temporary(extension = it.mediaType.extension)) }

/**
 * Downloads TypedData to a local file.
 *
 * @param file The KFile to write to. Defaults to a temporary file with the appropriate extension.
 * @return The KFile containing the downloaded content
 */
public suspend fun TypedData.download(
    file: KFile = SystemFileSystem.temporary(extension = mediaType.extension),
): KFile {
    write(file.sink())
    return file
}

@Deprecated("Use then", ReplaceWith("then(path)"))
public fun ExternalFile.resolve(path: String): FileObject = then(path)

@Deprecated("Use thenRandom", ReplaceWith("thenRandom(prefix, extension)"))
public fun ExternalFile.resolveRandom(prefix: String, extension: String): FileObject = thenRandom(prefix, extension)

/**
 * Creates a FileObject with a random UUID-based filename.
 *
 * The separator is `-` rather than `_` so the name survives [ExternalPath.toString] unescaped,
 * keeping generated URLs and stored references readable.
 *
 * @param prefix The prefix for the filename (before the UUID)
 * @param extension The file extension (without the dot)
 * @return A FileObject with path like `{prefix}-{uuid}.{extension}`
 *
 * Example:
 * ```
 * val file = root.thenRandom("upload", "jpg")
 * // Results in something like: upload-550e8400-e29b-41d4-a716-446655440000.jpg
 * ```
 */
public fun ExternalFile.thenRandom(prefix: String, extension: String): FileObject {
    return then("${prefix}-${Uuid.random()}.${extension}")
}

/**
 * Converts a FileObject to a persistable [ServerFile] reference.
 *
 * This wraps the backend-agnostic canonical `sf://<name>/<path>` form (see [ExternalFile.toString]),
 * which is what should be stored in a database - not a backend-specific absolute URL. Clients
 * receive a real signed URL only when the [ServerFile] is serialized out through the file
 * serializer, and [ExternalFile.Parser] reads the stored form back.
 */
public val ExternalFile.serverFile: ServerFile get() = ServerFile(toString())

/*
 * TODO: API Recommendations
 *
 * 1. The download() functions don't clean up temporary files. Consider adding:
 *    - A Closeable/AutoCloseable wrapper that deletes on close
 *    - Documentation about manual cleanup responsibility
 *    - A downloadAndUse { } extension that auto-cleans up
 *
 * 2. thenRandom always uses underscores. Consider allowing custom separators:
 *    fun thenRandom(prefix: String, extension: String, separator: String = "_")
 *
 * 3. Consider adding a thenRandomDirectory() variant for creating random directory paths.
 */