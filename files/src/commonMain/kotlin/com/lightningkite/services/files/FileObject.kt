package com.lightningkite.services.files

import com.lightningkite.services.data.TypedData
import kotlin.time.Duration

/**
 * An abstraction that allows FileSystem implementations to access and manipulate the underlying files.
 */
public interface FileObject {
    /**
     * Resolves a path relative to this file object.
     */
    public fun then(path: String): FileObject
    
    /**
     * The parent file object, or null if this is the root.
     */
    public val parent: FileObject?
    
    /**
     * The name of this file object.
     */
    public val name: String
    
    /**
     * Lists the contents of this directory, or returns null if this is not a directory.
     */
    public suspend fun list(): List<FileObject>?
    
    /**
     * Gets information about this file, or null if it doesn't exist.
     */
    public suspend fun head(): FileInfo?
    
    /**
     * Writes content to this file.
     */
    public suspend fun put(content: TypedData)
    
    /**
     * Reads content from this file, or returns null if it doesn't exist.
     */
    public suspend fun get(): TypedData?
    
    /**
     * Copies this file to another location.
     */
    public suspend fun copyTo(other: FileObject) {
        other.put(get() ?: return)
    }
    
    /**
     * Moves this file to another location.
     */
    public suspend fun moveTo(other: FileObject) {
        copyTo(other)
        delete()
    }
    
    /**
     * Deletes this file.
     */
    public suspend fun delete()
    
    /**
     * The URL for this file.
     */
    public val url: String
    
    /**
     * A signed URL for this file that can be used to access it securely.
     */
    public val signedUrl: String
    
    /**
     * Gets a URL that can be used to upload to this file.
     */
    public fun uploadUrl(timeout: Duration): String
}