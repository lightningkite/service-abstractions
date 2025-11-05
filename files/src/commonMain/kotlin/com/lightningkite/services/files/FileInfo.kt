package com.lightningkite.services.files

import com.lightningkite.MediaType
import kotlin.time.Instant

/**
 * Holds metadata information about files without requiring the full content to be read.
 *
 * This data class is returned by [FileObject.head] to provide quick access to file
 * metadata for operations like listing files with their sizes or checking content types.
 */
public data class FileInfo(
    /**
     * The media type (MIME type) of the file.
     *
     * This is typically determined from the file extension or stored metadata.
     */
    val type: MediaType,

    /**
     * The size of the file in bytes.
     *
     * For empty files, this will be 0. For directories, the meaning is implementation-specific.
     */
    val size: Long,

    /**
     * The last modified timestamp of the file.
     *
     * May be null if the file system doesn't track modification times or if the
     * information is unavailable. Not all file system implementations populate this field.
     */
    val lastModified: Instant? = null
)

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding a 'created' timestamp field for file creation time.
 *
 * 2. Consider adding an 'isDirectory' boolean to distinguish files from directories
 *    without requiring a separate list() call.
 *
 * 3. Consider adding an 'etag' or 'version' field for cache validation and
 *    optimistic locking scenarios.
 *
 * 4. Consider adding custom metadata map:
 *    val metadata: Map<String, String> = emptyMap()
 *    This would be useful for storing tags, user-defined properties, etc.
 */