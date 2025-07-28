package com.lightningkite.serviceabstractions.files

import com.lightningkite.MediaType
import kotlin.time.Instant

/**
 * Holds common information about files.
 */
public data class FileInfo(
    /**
     * The content type of the file.
     */
    val type: MediaType,

    /**
     * The size of the file in bytes.
     */
    val size: Long,

    /**
     * The last modified time of the file.
     */
    val lastModified: Instant? = null
)