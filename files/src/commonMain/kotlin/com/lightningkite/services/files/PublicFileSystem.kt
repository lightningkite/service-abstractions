package com.lightningkite.services.files

import com.lightningkite.MediaType
import com.lightningkite.services.*
import com.lightningkite.services.data.*
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Service abstraction for cloud file storage and content delivery.
 *
 * PublicFileSystem provides a unified interface for storing and serving files across
 * different storage backends (local filesystem, AWS S3, etc.). Files are accessible
 * via public URLs with optional signed URL support for access control.
 *
 * ## Available Implementations
 *
 * - **KotlinxIoPublicFileSystem** (`file://`) - Local filesystem storage
 * - **S3PublicFileSystem** (`s3://`) - AWS S3 storage (JVM-only: files-s3, KMP: files-s3-kmp)
 *
 * ## Configuration
 *
 * Configure via [Settings] using URL strings:
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     // Local filesystem
 *     val files: PublicFileSystem.Settings = PublicFileSystem.Settings(
 *         "file:///var/app/uploads?serveUrl=https://example.com/files"
 *     ),
 *     // S3 with signed URLs
 *     val s3Files: PublicFileSystem.Settings = PublicFileSystem.Settings(
 *         "s3://my-bucket.s3-us-east-1.amazonaws.com?signedUrlDuration=1h"
 *     )
 * )
 * ```
 *
 * ## Basic Usage
 *
 * ```kotlin
 * val fs: PublicFileSystem = settings.files("storage", context)
 *
 * // Upload file
 * val file = fs.root.then("uploads/avatar.jpg")
 * file.put(TypedData(imageBytes, MediaType.Image.Jpeg))
 *
 * // Get public URL
 * val url = file.url  // https://example.com/files/uploads/avatar.jpg
 *
 * // Read file
 * val content = file.get()  // TypedData with content and media type
 *
 * // Delete file
 * file.delete()
 *
 * // List files
 * val uploadDir = fs.root.then("uploads/")
 * uploadDir.list().collect { fileInfo ->
 *     println("${fileInfo.name}: ${fileInfo.size} bytes")
 * }
 * ```
 *
 * ## Signed URLs
 *
 * For access control, signed URLs include signatures that expire:
 *
 * ```kotlin
 * // Configure with signed URLs (1 hour expiration)
 * val fs = PublicFileSystem.Settings(
 *     "s3://bucket.s3-us-east-1.amazonaws.com?signedUrlDuration=1h"
 * )("storage", context)
 *
 * val file = fs.root.then("private/document.pdf")
 * val signedUrl = file.url  // Includes signature, expires in 1 hour
 * ```
 *
 * ## URL Parsing
 *
 * Parse URLs back into FileObject references:
 *
 * ```kotlin
 * // Internal URL (server-side only)
 * val file = fs.parseInternalUrl("https://cdn.example.com/files/image.jpg")
 *
 * // External URL (validates signature)
 * val file = fs.parseExternalUrl(signedUrlFromClient)
 * ```
 *
 * ## Important Gotchas
 *
 * - **Public access**: Files are publicly accessible unless using signed URLs
 * - **URL persistence**: File URLs should not be stored long-term if using signed URLs
 * - **Path traversal**: FileObject normalizes paths to prevent .. attacks
 * - **Concurrency**: Concurrent writes to same file may result in race conditions
 * - **Storage costs**: Cloud storage (S3) charges for storage and bandwidth
 * - **serveUrl required**: Local filesystem requires serveUrl parameter (base URL for file access)
 * - **Health check writes**: Creates test file at `health-check/test-file.txt`
 * - **Media type detection**: Set MediaType explicitly, don't rely on auto-detection
 * - **Large files**: For uploads >5MB on S3, consider multipart upload (handled automatically by some implementations)
 *
 * @see FileObject
 * @see TypedData
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
            val testFile = root.then("health-check/test-file-${Uuid.random()}.txt")
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
     * Configuration for instantiating a PublicFileSystem.
     *
     * The URL scheme determines the storage backend:
     * - `file://path?serveUrl=baseUrl` - Local filesystem (requires serveUrl parameter)
     * - `s3://bucket.s3-region.amazonaws.com` - AWS S3 storage
     *
     * ## Query Parameters
     *
     * - `serveUrl` (required for file://): Base URL for serving files
     *   - Relative: `?serveUrl=files` → `${context.publicUrl}/files/`
     *   - Absolute: `?serveUrl=https://cdn.example.com/files` → `https://cdn.example.com/files/`
     *
     * - `signedUrlDuration` (optional): How long signed URLs remain valid
     *   - ISO 8601 duration: `?signedUrlDuration=PT1H` (1 hour)
     *   - Seconds: `?signedUrlDuration=3600` (1 hour)
     *   - No expiration: `?signedUrlDuration=forever` or `?signedUrlDuration=null`
     *   - Default: 1 hour if not specified
     *
     * ## Examples
     *
     * ```kotlin
     * // Local filesystem with relative URL
     * PublicFileSystem.Settings("file:///var/uploads?serveUrl=files")
     *
     * // Local filesystem with absolute URL and signed URLs
     * PublicFileSystem.Settings("file:///var/uploads?serveUrl=https://cdn.example.com/files&signedUrlDuration=PT30M")
     *
     * // S3 with default credentials
     * PublicFileSystem.Settings("s3://my-bucket.s3-us-east-1.amazonaws.com")
     *
     * // S3 with access key and no signed URLs
     * PublicFileSystem.Settings("s3://AKIAIOSFODNN7EXAMPLE:secretKey@my-bucket.s3-us-east-1.amazonaws.com?signedUrlDuration=forever")
     * ```
     *
     * @property url Connection string defining the storage backend and parameters
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
                    val serveUrl = if (relativeServeUrl.contains("://")) relativeServeUrl.trim('/')
                        .plus('/') else "${context.publicUrl}/${relativeServeUrl.trim('/')}/"

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