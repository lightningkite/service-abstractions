package com.lightningkite.services.files

import com.lightningkite.MediaType
import com.lightningkite.services.*
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.KFile
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.workingDirectory
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * An abstracted model for reading and writing files in a storage solution.
 * Every implementation will handle how to resolve FileObjects in their own system.
 *
 * This interface provides a way to work with file systems across different storage
 * backends (local filesystem, S3, etc.) using a consistent API.
 */
public interface PublicFileSystem : Service {
    /**
     * The root file object for this file system.
     * All file paths are resolved relative to this root.
     */
    public val root: FileObject

    /**
     * The root URLs for this file system.
     * Default implementation returns a single-element list containing the root's URL.
     * Override this if your file system has multiple root URLs (e.g., CDN mirrors).
     */
    public val rootUrls: List<String> get() = listOf(root.url)

    /**
     * Parses an internal URL (unsigned, used within the server) into a FileObject.
     *
     * @param url The internal URL to parse
     * @return A FileObject if the URL belongs to this file system, null otherwise
     */
    public fun parseInternalUrl(url: String): FileObject?

    /**
     * Parses an external URL (signed, provided to clients) into a FileObject.
     *
     * For file systems with signed URLs, this will validate the signature and expiration.
     *
     * @param url The external/signed URL to parse
     * @return A FileObject if the URL is valid and belongs to this file system, null otherwise
     * @throws IllegalArgumentException if signature validation fails or URL has expired
     */
    public fun parseExternalUrl(url: String): FileObject?


    /**
     * Performs a health check by writing, reading, and deleting a test file.
     *
     * The health check verifies:
     * - Ability to write files
     * - Ability to read files with correct content type
     * - Ability to read files with matching content
     * - Ability to delete files
     *
     * Note: The test file is created at `health-check/test-file.txt` relative to root.
     * If deletion fails, this file may persist.
     *
     * @return HealthStatus.Level.OK if all operations succeed, HealthStatus.Level.ERROR otherwise
     */
    override suspend fun healthCheck(): HealthStatus {
        return try {
            val testFile = root.then("health-check/test-file.txt")
            val contentData = Data.Text("Test Content")
            val content = TypedData(contentData, MediaType.Text.Plain)
            testFile.put(content)
            val retrieved = testFile.get()
            if (retrieved?.mediaType != MediaType.Text.Plain) {
                HealthStatus(
                    level = HealthStatus.Level.ERROR,
                    additionalMessage = "Test write resulted in file of incorrect content type"
                )
            } else if (retrieved.data.text() != contentData.text()) {
                HealthStatus(
                    level = HealthStatus.Level.ERROR,
                    additionalMessage = "Test content did not match"
                )
            } else {
                testFile.delete()
                HealthStatus(level = HealthStatus.Level.OK)
            }
        } catch (e: Exception) {
            HealthStatus(
                level = HealthStatus.Level.ERROR,
                additionalMessage = e.message
            )
        }
    }

    /**
     * Configuration settings for a PublicFileSystem.
     *
     * Example URLs:
     * - `file:///path/to/directory?serveUrl=files` - Local filesystem with relative serve URL
     * - `file:///path/to/directory?serveUrl=https://example.com/files` - Local filesystem with absolute serve URL
     * - `file:///path/to/directory?serveUrl=files&signedUrlDuration=PT1H` - With 1 hour signed URL duration
     * - `file:///path/to/directory?serveUrl=files&signedUrlDuration=3600` - With 3600 seconds signed URL duration
     * - `file:///path/to/directory?serveUrl=files&signedUrlDuration=forever` - Without signed URL expiration
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "file://${
            workingDirectory.then("local/files").also { it.createDirectories() }
        }?serveUrl=files",
    ) : Setting<PublicFileSystem> {

        public companion object : UrlSettingParser<PublicFileSystem>() {
            init {
                register("file") { name, url, context ->

                    val path = url.substringAfter("://").substringBefore("?").substringBefore("#")

                    // Required Parameters:
                    //      serveUrl - The base url files will be served from
                    // Optional Parameters:
                    //      signedUrlDuration - How long a url is valid for. If not provided the default time is 1 hour
                    //      valid values are: "forever", "null", a valid iso8601 duration string, a number representing seconds
                    val params = url.substringAfter("?", "").substringBefore("#")
                        .takeIf { it.isNotEmpty() }
                        ?.split("&")
                        ?.associate { it.substringBefore("=") to it.substringAfter("=", "") }
                        ?: emptyMap()

                    val relativeServeUrl = params["serveUrl"] ?: throw IllegalArgumentException("No serveUrl provided")
                    val serveUrl = if(relativeServeUrl.contains("://")) relativeServeUrl.trim('/').plus('/') else "${context.publicUrl}/${relativeServeUrl.trim('/')}/"

                    val signedUrlDuration = params["signedUrlDuration"].let {
                        when {
                            it == null -> 1.hours
                            it == "forever" || it == "null" -> null
                            it.all { it.isDigit() } -> it.toLong().seconds
                            else -> Duration.parse(it)
                        }
                    }

                    KotlinxIoPublicFileSystem(
                        name = name,
                        context = context,
                        rootKFile = KFile(path),
                        serveUrl = serveUrl,
                        signedUrlDuration = signedUrlDuration
                    )
                }
            }
        }

        override fun invoke(name: String, context: SettingContext): PublicFileSystem {
            return parse(name, url, context)
        }
    }
}

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding a `exists()` method to the interface for checking file existence
 *    without reading the entire file (more efficient than calling head() != null).
 *
 * 2. Consider adding bulk operations for better performance:
 *    - suspend fun copyBatch(items: List<Pair<FileObject, FileObject>>)
 *    - suspend fun deleteBatch(items: List<FileObject>)
 *
 * 3. The healthCheck leaves a test file if deletion fails. Consider adding a cleanup
 *    method or documenting this behavior more prominently for operations teams.
 *
 * 4. Consider adding metadata operations:
 *    - suspend fun setMetadata(key: String, value: String)
 *    - suspend fun getMetadata(key: String): String?
 *    This would be useful for tags, custom headers, etc.
 *
 * 5. The distinction between parseInternalUrl and parseExternalUrl could be clarified
 *    with better naming (e.g., parseUnsignedUrl vs parseSignedUrl) or merged into a
 *    single method with a validation parameter.
 */