package com.lightningkite.services.files

import com.lightningkite.services.*
import com.lightningkite.services.data.*
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.kfile.workingDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
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
 * // Stored references, server-side only: the canonical sf:// form and legacy absolute URLs
 * val file = ExternalFile.Parser(listOf(fs)).parse("sf://storage/image.jpg")
 *
 * // External URL (validates signature); does NOT accept canonical sf:// references
 * val file = fs.parseExternalUrl(signedUrlFromClient)
 * ```
 *
 * ## Persisting References
 *
 * Store the backend-agnostic canonical form (see [ExternalFile.toString]) - `sf://<name>/<path>` -
 * not a signed or backend-specific absolute URL. `ExternalFile.serverFile` produces it, and the file
 * serializer both writes it on the way in and turns it back into a signed URL on the way out.
 * Because it is keyed by the service [name], a stored value keeps resolving after the backend is
 * swapped, provided the service keeps the same name and the files are moved across. Legacy absolute
 * URLs already in a database continue to resolve through [parseLegacyUrl] as long as the same
 * backend and name are configured.
 *
 * ## Important Gotchas
 *
 * - **Public access**: Files are publicly accessible unless using signed URLs
 * - **Never persist signed URLs**: they expire; persist the canonical `sf://` form instead
 * - **Path traversal**: [ExternalPath] rejects `.`/`..`/separator-bearing segments at construction time
 * - **Concurrency**: Concurrent writes to same file may result in race conditions
 * - **serveUrl required**: Local filesystem requires serveUrl parameter (base URL for file access)
 * - **Health check writes**: Creates test file at `health-check/test-file.txt`
 *
 * @see ExternalFile
 * @see ExternalPath
 * @see TypedData
 */
public interface ExternalFileSystem : Service {
    public suspend fun flow(path: ExternalPath): Flow<ExternalPath>
    public suspend fun list(path: ExternalPath): List<ExternalPath> = flow(path).toList()
    public suspend fun head(path: ExternalPath): FileInfo?
    public suspend fun put(path: ExternalPath, content: TypedData)
    public suspend fun get(path: ExternalPath): TypedData?
    public suspend fun delete(path: ExternalPath)

    /**
     * Reads the bytes of the file at [path] between [range]'s bounds, **both ends inclusive**, so
     * `0L..15L` asks for 16 bytes. This matches HTTP's `bytes=a-b`, which is where these ranges
     * almost always come from.
     *
     * The window is clamped to what the file actually holds: asking for `0L..15L` of a 5-byte file
     * yields those 5 bytes, and a range starting at or past the end yields empty data. Neither is an
     * error - a caller reading a file in fixed-size chunks cannot know where the end falls without
     * asking. The returned [TypedData.data]'s size is therefore the number of bytes actually read,
     * not the size of the file.
     *
     * The default implementation reads the whole file and slices it; backends that can ask for the
     * window directly override this.
     *
     * The result is the caller's to close, as with [get] - a backend that fetches the window over the
     * network hands back a live response body, so dropping it unclosed leaks the connection.
     *
     * @return the requested window, or null if the file does not exist (as [get])
     * @throws IllegalArgumentException if [range] starts before 0 or ends before it starts
     */
    public suspend fun getRange(path: ExternalPath, range: LongRange): TypedData? {
        requireValidRange(range)
        val whole = get(path) ?: return null
        val bytes = whole.data.bytes()
        // A start at or past the end is an empty window; below here the start is a valid index.
        if (range.first >= bytes.size) return TypedData(Data.Bytes(ByteArray(0)), whole.mediaType)
        // Clamp before the +1 so that an open-ended `a..Long.MAX_VALUE` doesn't overflow.
        val lastIndex = minOf(range.last, bytes.size - 1L).toInt()
        return TypedData(Data.Bytes(bytes.copyOfRange(range.first.toInt(), lastIndex + 1)), whole.mediaType)
    }

    /**
     * Copies the file at [path] to [other].
     *
     * The default implementation downloads then re-uploads, which works across file systems;
     * implementations may override this to optimize same-system copies (e.g. server-side copy).
     *
     * @throws IllegalArgumentException if the source file doesn't exist
     */
    public suspend fun copyTo(path: ExternalPath, other: ExternalFile) {
        val content = get(path)
            ?: throw IllegalArgumentException("Source file does not exist: ${ExternalFile(this, path)}")
        try {
            other.put(content)
        } catch (e: Exception) {
            throw Exception("Failed to copy file from ${ExternalFile(this, path)} to $other", e)
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
        val content = get(path)
            ?: throw IllegalArgumentException("Source file does not exist: ${ExternalFile(this, path)}")
        try {
            other.put(content)
        } catch (e: Exception) {
            throw Exception(
                "Failed to move file from ${ExternalFile(this, path)} to $other: copy failed",
                e
            )
        }
        try {
            delete(path)
        } catch (e: Exception) {
            throw Exception(
                "File copied to $other but failed to delete source at ${ExternalFile(this, path)}",
                e
            )
        }
    }

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
     * Parses an absolute URL of the kind this backend stored before the canonical `sf://` form
     * existed, into an [ExternalFile].
     *
     * These URLs are unsigned, so this is server-internal: only values that came out of our own
     * storage may be passed here, never client input. [ExternalFile.Parser] uses it as the fallback
     * for stored references that are not canonical.
     *
     * @return An [ExternalFile] if the URL belongs to this file system, null otherwise
     */
    public fun parseLegacyUrl(url: String): ExternalFile?

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
     * Whether [parseExternalUrl] can tell a reference this file system issued from one a client
     * invented - by signature, or by any other means.
     *
     * When false, [parseExternalUrl] accepts any path under this file system's own root, so a
     * client-supplied reference is worth no more than the path string inside it. Callers whose
     * security depends on a client only being able to name files it was given must check this and
     * refuse to run without it.
     *
     * The default is false so that a backend which has not considered the question is treated as the
     * unsafe case rather than silently vouching for itself.
     */
    public val referencesAreUnforgeable: Boolean get() = false

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
    ) : Setting<ExternalFileSystem> {

        public companion object : UrlSettingParser<ExternalFileSystem>() {
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

                    KotlinxIoExternalFileSystem(
                        name = name,
                        context = context,
                        rootKFile = KFile(path),
                        serveUrl = serveUrl,
                        signedUrlDuration = signedUrlDuration
                    )
                }
            }
        }

        override fun invoke(name: String, context: SettingContext): ExternalFileSystem {
            return parse(name, url, context)
        }
    }

    public companion object {
        /**
         * The precondition shared by every [getRange] implementation. It lives here because an
         * override never runs the default implementation, and a backend that silently accepted a
         * backwards range would hand back a plausible-looking window of the wrong bytes.
         */
        public fun requireValidRange(range: LongRange) {
            require(range.first >= 0) { "Range must not start before the file: ${range.first}" }
            require(range.last >= range.first) { "Range must not end before it starts: $range" }
        }
    }
}