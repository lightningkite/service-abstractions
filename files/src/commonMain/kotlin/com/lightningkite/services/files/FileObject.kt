package com.lightningkite.services.files

import com.lightningkite.services.data.TypedData
import kotlin.time.Duration

/**
 * An abstraction that allows FileSystem implementations to access and manipulate the underlying files.
 *
 * FileObject represents a file or directory in a [PublicFileSystem]. It provides operations for
 * reading, writing, copying, moving, and deleting files, as well as listing directory contents.
 */
public interface FileObject {
    /**
     * Resolves a path relative to this file object.
     *
     * @param path The relative path to resolve. Can contain forward slashes for nested paths.
     * @return A new FileObject representing the resolved path
     *
     * Example:
     * ```
     * val file = root.then("uploads/images/photo.jpg")
     * ```
     */
    public fun then(path: String): FileObject

    /**
     * The parent file object, or null if this is the root.
     */
    public val parent: FileObject?

    /**
     * The name of this file object (the last component of the path).
     */
    public val name: String

    /**
     * Lists the contents of this directory.
     *
     * @return A list of FileObjects in this directory, null if this is not a directory or doesn't exist
     */
    public suspend fun list(): List<FileObject>?

    /**
     * Gets metadata about this file without downloading its content.
     *
     * @return FileInfo containing type, size, and last modified time, or null if the file doesn't exist
     */
    public suspend fun head(): FileInfo?

    /**
     * Writes content to this file, creating parent directories if needed.
     *
     * If the file already exists, it will be overwritten.
     *
     * @param content The typed data to write
     */
    public suspend fun put(content: TypedData)

    /**
     * Reads the full content from this file.
     *
     * @return The file's content as TypedData, or null if the file doesn't exist
     */
    public suspend fun get(): TypedData?

    /**
     * Copies this file to another location.
     *
     * @param other The destination FileObject
     * @throws IllegalArgumentException if this file doesn't exist
     * @throws Exception if the copy operation fails
     */
    public suspend fun copyTo(other: FileObject) {
        val content = get() ?: throw IllegalArgumentException("Source file does not exist: $url")
        try {
            other.put(content)
        } catch (e: Exception) {
            throw Exception("Failed to copy file from $url to ${other.url}", e)
        }
    }

    /**
     * Moves this file to another location by copying and then deleting the source.
     *
     * This operation ensures the copy succeeds before deleting the source to prevent data loss.
     * However, it is still not fully atomic - if the delete fails after a successful copy,
     * the file will exist in both locations.
     *
     * @param other The destination FileObject
     * @throws IllegalArgumentException if this file doesn't exist
     * @throws Exception if the copy operation fails (source will not be deleted)
     */
    public suspend fun moveTo(other: FileObject) {
        val content = get() ?: throw IllegalArgumentException("Source file does not exist: $url")

        // First ensure the copy succeeds
        try {
            other.put(content)
        } catch (e: Exception) {
            // Copy failed, don't delete source
            throw Exception("Failed to move file from $url to ${other.url}: copy failed", e)
        }

        // Copy succeeded, now safe to delete source
        try {
            delete()
        } catch (e: Exception) {
            // Delete failed, but file was successfully copied
            // This is less critical than losing data, so we just wrap the exception
            throw Exception("File copied to ${other.url} but failed to delete source at $url", e)
        }
    }

    /**
     * Deletes this file or directory.
     *
     * Behavior with directories is implementation-specific and may fail if not empty.
     */
    public suspend fun delete()

    /**
     * The internal URL for this file (may be unsigned).
     *
     * This URL is typically used server-side and may not be suitable for sharing with clients.
     */
    public val url: String

    /**
     * A signed URL for this file that can be used to access it securely.
     *
     * For file systems with signed URL support, this provides a time-limited, signed URL
     * that can be safely shared with clients. For file systems without signing, this may
     * be identical to [url].
     */
    public val signedUrl: String

    /**
     * Generates a signed URL that can be used to upload content to this file.
     *
     * @param timeout How long the upload URL should remain valid
     * @return A signed URL that clients can use to upload directly to this file
     */
    public fun uploadUrl(timeout: Duration): String
}

/*
 * TODO: API Recommendations
 *
 * 1. Add suspend fun exists(): Boolean for efficient existence checks without metadata overhead.
 *
 * 2. The moveTo implementation is unsafe - it may delete the source even if the copy fails.
 *    Consider:
 *    - Adding verification after copy
 *    - Throwing exception on copy failure before delete
 *    - Providing moveToAtomic for implementations that support it
 *
 * 3. The copyTo operation silently does nothing if source doesn't exist. Consider throwing
 *    an exception or returning Boolean for success/failure.
 *
 * 4. Consider adding:
 *    - suspend fun size(): Long for getting just the size efficiently
 *    - suspend fun lastModified(): Instant? for getting just the timestamp
 *
 * 5. The list() function doesn't specify ordering. Consider:
 *    - Documenting whether results are sorted
 *    - Adding listSorted(comparator) variant
 *    - Adding pagination support for large directories
 *
 * 6. Consider adding recursive directory operations:
 *    - suspend fun listRecursive(): Sequence<FileObject>
 *    - suspend fun deleteRecursive()
 *    - suspend fun copyRecursiveTo(other: FileObject)
 */