package com.lightningkite.services.files

import com.lightningkite.services.*
import com.lightningkite.services.data.*
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.kfile.workingDirectory
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
 * different storage backends (local filesystem, AWS S3, etc.), keyed by [ExternalPath].
 * Files are accessible via public URLs with optional signed URL support for access control.
 *
 * ## Available Implementations
 *
 * - **KotlinxIoPublicFileSystem** (`file://`) - Local filesystem storage
 * - **S3PublicFileSystem** (`s3://`) - AWS S3 storage (JVM-only: files-s3)
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
 * // Read file
 * val content = file.get()  // TypedData with content and media type, null if it doesn't exist
 *
 * // Delete file
 * file.delete()
 * ```
 *
 * ## Signed URLs
 *
 * For access control, signed URLs include signatures that expire:
 *
 * ```kotlin
 * val fs = PublicFileSystem.Settings(
 *     "s3://bucket.s3-us-east-1.amazonaws.com?signedUrlDuration=1h"
 * )("storage", context)
 *
 * val file = fs.root.then("private/document.pdf")
 * val signedUrl = file.signedUrl  // Includes signature, expires in 1 hour
 * ```
 *
 * ## URL Parsing
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
 * - **Path traversal**: [ExternalPath] rejects `.`/`..` segments at construction time
 * - **Concurrency**: Concurrent writes to same file may result in race conditions
 * - **serveUrl required**: Local filesystem requires serveUrl parameter (base URL for file access)
 * - **Health check writes**: Creates test file at `health-check/test-file.txt`
 *
 * @see ExternalFile
 * @see ExternalPath
 * @see TypedData
 */
public interface PublicFileSystem : Service {
    public suspend fun list(path: ExternalPath): List<ExternalPath>?
    public suspend fun head(path: ExternalPath): FileInfo?
    public suspend fun put(path: ExternalPath, content: TypedData)
    public suspend fun get(path: ExternalPath): TypedData?
    public suspend fun delete(path: ExternalPath)

    /**
     * Copies the file at [path] to [other].
     *
     * The default implementation downloads then re-uploads, which works across file systems;
     * implementations may override this to optimize same-system copies (e.g. server-side copy).
     *
     * @throws IllegalArgumentException if the source file doesn't exist
     */
    public suspend fun copyTo(path: ExternalPath, other: ExternalFile) {
        val content = get(path) ?: throw IllegalArgumentException("Source file does not exist: ${url(path)}")
        try {
            other.put(content)
        } catch (e: Exception) {
            throw Exception("Failed to copy file from ${url(path)} to ${other.fileSystem.url(other.path)}", e)
        }
    }

    /**
     * Moves the file at [path] to [other] by copying and then deleting the source.
     *
     * This ensures the copy succeeds before deleting the source to prevent data loss. It is
     * still not fully atomic - if the delete fails after a successful copy, the file will
     * exist in both locations.
     *
     * @throws IllegalArgumentException if the source file doesn't exist
     */
    public suspend fun moveTo(path: ExternalPath, other: ExternalFile) {
        val content = get(path) ?: throw IllegalArgumentException("Source file does not exist: ${url(path)}")
        try {
            other.put(content)
        } catch (e: Exception) {
            throw Exception(
                "Failed to move file from ${url(path)} to ${other.fileSystem.url(other.path)}: copy failed",
                e
            )
        }
        try {
            delete(path)
        } catch (e: Exception) {
            throw Exception(
                "File copied to ${other.fileSystem.url(other.path)} but failed to delete source at ${url(path)}",
                e
            )
        }
    }

    /**
     * The internal URL for a file at [path] (may be unsigned).
     */
    public fun url(path: ExternalPath): String

    /**
     * The root URLs for this file system.
     * Default implementation returns a single-element list containing the root's URL.
     * Override this if your file system has multiple root URLs (e.g., CDN mirrors).
     */
    public val rootUrls: List<String> get() = listOf(url(ExternalPath(emptyList())))

    /**
     * A signed URL for the file at [path] that can be used to access it securely.
     *
     * @param timeout How long the URL should remain valid. Defaults to the file system's
     * configured signed-URL duration; if that is also unset, the URL is unsigned.
     */
    public fun signUrl(path: ExternalPath, timeout: Duration? = null): String

    /**
     * Generates a signed URL that can be used to upload content to the file at [path].
     */
    public fun uploadUrl(path: ExternalPath, timeout: Duration): String

    /**
     * Parses an internal URL (unsigned, used within the server) into an [ExternalFile].
     *
     * @return An [ExternalFile] if the URL belongs to this file system, null otherwise
     */
    public fun parseInternalUrl(url: String): ExternalFile?

    /**
     * Parses an external URL (signed, provided to clients) into an [ExternalFile].
     *
     * For file systems with signed URLs, this will validate the signature and expiration.
     *
     * @return An [ExternalFile] if the URL is valid and belongs to this file system, null otherwise
     * @throws IllegalArgumentException if signature validation fails or URL has expired
     */
    public fun parseExternalUrl(url: String): ExternalFile?

    /**
     * The root file for this file system. All file paths are resolved relative to this root.
     */
    public val root: ExternalFile get() = ExternalFile(this, ExternalPath(emptyList()))

    /**
     * Performs a health check by writing, reading, and deleting a test file.
     *
     * Note: The test file is created at `health-check/test-file-<uuid>.txt` relative to root.
     * If deletion fails, this file may persist.
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

/**
 * @suppress Unused alternate name kept only in case anything already adopted it; prefer [PublicFileSystem].
 */
@Deprecated("Use PublicFileSystem instead.", ReplaceWith("PublicFileSystem"))
public typealias ExternalFileSystem = PublicFileSystem
