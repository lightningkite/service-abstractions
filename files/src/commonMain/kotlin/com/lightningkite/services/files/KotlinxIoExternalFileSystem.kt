package com.lightningkite.services.files

import com.lightningkite.services.Namespaced
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.*
import com.lightningkite.services.data.DataSize.Companion.bytes
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.emptyTelemetryAttributes
import com.lightningkite.services.telemetry.telemetryAttributesOf
import com.lightningkite.services.telemetry.telemetryTrace
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.io.*
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A [ExternalFileSystem] implementation that uses kotlinx.io for local file system access.
 *
 * This implementation stores files on the local file system and provides:
 * - HMAC-based signed URLs for secure file access
 * - Content type tracking via sidecar `.contenttype` files
 * - Support for upload URLs with time-based expiration
 *
 * @param name The service name
 * @param context The setting context
 * @param rootKFile The root directory for file storage
 * @param serveUrl The base URL where files will be served from (e.g., "https://example.com/files/")
 * @param signedUrlDuration How long signed URLs remain valid. If null, URLs are unsigned.
 */
public class KotlinxIoExternalFileSystem(
    override val name: String,
    override val context: SettingContext,
    public val rootKFile: KFile,
    public val serveUrl: String = "http://localhost:8080/files/",
    public val signedUrlDuration: Duration? = null,
) : ExternalFileSystem {
    init {
        rootKFile.createDirectories()
    }

    private val signingKeyFile = ".signingKey"

    /**
     * Top-level directory holding the content-type sidecar of every stored file, mirroring the
     * file's own path with `.contenttype` appended.
     *
     * Sidecars live in their own subtree rather than beside the file they describe so that no name a
     * caller can write is ever also a sidecar name: storing them side by side would let anyone with
     * an upload URL write `photo.jpg.contenttype` and choose the content type this server later
     * serves `photo.jpg` with, which is enough to turn an image upload into stored XSS.
     */
    private val metadataDirectory = ".metadata"

    /**
     * HMAC signing key used for URL signatures.
     *
     * The key is persisted in `.signingKey` file in the root directory. If the file doesn't exist,
     * a new key is generated and saved. This ensures URLs remain valid across server restarts.
     */
    private val key = run {
        val hmac = CryptographyProvider.Default.get(HMAC)
        val digest = SHA256
        val format = HMAC.Key.Format.RAW

        val keyFile = rootKFile.then(signingKeyFile)

        if (keyFile.exists()) hmac.keyDecoder(digest).decodeFromByteArrayBlocking(format, keyFile.readByteArray())
        else {
            val key = hmac.keyGenerator(digest).generateKeyBlocking()
            keyFile.writeByteArray(key.encodeToByteArrayBlocking(format))
            key
        }
    }

    /**
     * Data structure for signing URLs with expiration and upload permissions.
     *
     * @param url The base URL (without query parameters)
     * @param expires When the signed URL expires
     * @param upload Whether this URL permits uploads (true) or only reads (false)
     */
    internal data class DataToSign(val url: String, val expires: Instant, val upload: Boolean) {
        /**
         * Parses a signed URL string back into DataToSign components.
         */
        constructor(urlWithQuery: String) : this(
            url = urlWithQuery.substringBefore("?"),
            expires = urlWithQuery.substringAfter("?expires=", "0").takeWhile { it.isDigit() }.toLong()
                .let { Instant.fromEpochMilliseconds(it) },
            upload = urlWithQuery.contains("&upload=true")
        )

        override fun toString(): String =
            "$url?expires=${expires.toEpochMilliseconds()}" + if (upload) "&upload=true" else ""
    }

    /**
     * Generates an HMAC signature for the given data.
     */
    internal fun sign(data: DataToSign): String {
        return key.signatureGenerator().generateSignatureBlocking(data.toString().encodeToByteArray()).toHexString()
    }

    /**
     * Verifies an HMAC signature for the given data.
     */
    internal fun verify(data: DataToSign, signature: String): Boolean {
        return key.signatureVerifier()
            .tryVerifySignatureBlocking(data.toString().encodeToByteArray(), signature.hexToByteArray())
    }

    /**
     * Returns a signed URL string by appending the signature to the data.
     */
    internal fun DataToSign.signed() = toString() + "&signature=" + sign(this)

    /**
     * The served URL for [path], with the path in [ExternalPath]'s conservative form so that it is
     * URL-safe without any further encoding. [parsePath] reverses it.
     */
    private fun url(path: ExternalPath): String =
        serveUrl.removeSuffix("/") + '/' + path.toString()

    override fun signUrl(path: ExternalPath, timeout: Duration?): String {
        val u = url(path)
        return (timeout ?: signedUrlDuration)?.let { expiration ->
            DataToSign(u, context.clock.now().plus(expiration), false).signed()
        } ?: u
    }

    override fun uploadUrl(path: ExternalPath, timeout: Duration): String =
        DataToSign(url(path), context.clock.now().plus(timeout), true).signed()

    /**
     * Parses a serveUrl-based absolute URL stored by the pre-canonical version, which wrote path
     * segments literally rather than in the conservative form [url] now emits.
     *
     * @return An ExternalFile if the URL starts with this file system's serveUrl, null otherwise
     */
    override fun parseLegacyUrl(url: String): ExternalFile? {
        if (!url.startsWith(serveUrl)) return null
        return ExternalFile(this, pathFromLiteral(url.substringAfter(serveUrl)))
    }

    /**
     * Parses an external (signed) URL into an [ExternalFile].
     *
     * For file systems with signing enabled, this validates the signature and expiration.
     * For unsigned file systems, this simply parses the URL path.
     *
     * @throws IllegalArgumentException if signature verification fails, URL has expired,
     *         URL doesn't match this file system, or URL is for upload (not read)
     */
    override fun parseExternalUrl(url: String): ExternalFile? {
        if (!url.startsWith(serveUrl)) return null
        return if (signedUrlDuration != null) {
            val data = DataToSign(url.substringBeforeLast("&"))
            val signature = url.substringAfterLast("&", "").substringAfter('=')
            if (!verify(data, signature)) throw IllegalArgumentException("Signature verification failed for $url")
            if (context.clock.now() > data.expires) throw IllegalArgumentException("URL has expired for $url")
            if (!data.url.startsWith(serveUrl)) throw IllegalArgumentException("URL does not match this file system")
            if (data.upload) throw IllegalArgumentException("URL is for upload, not read")
            ExternalFile(this, parsePath(data.url.substringAfter(serveUrl)))
        } else
            ExternalFile(this, parsePath(url.substringBefore('?').substringAfter(serveUrl)))
    }

    /**
     * Parses a signed upload URL into an [ExternalFile].
     *
     * This validates that the URL is specifically marked for uploads and hasn't expired.
     *
     * @return An ExternalFile if the URL is valid, null if it doesn't start with serveUrl
     * @throws IllegalArgumentException if signature verification fails, URL has expired,
     *         URL doesn't match this file system, or URL is for read (not upload)
     */
    public fun parseUploadUrl(url: String): ExternalFile? {
        if (!url.startsWith(serveUrl)) return null
        val data = DataToSign(url.substringBeforeLast("&"))
        val signature = url.substringAfterLast("&", "").substringAfter('=')
        if (!verify(data, signature)) throw IllegalArgumentException("Signature verification failed for $url")
        if (context.clock.now() > data.expires) throw IllegalArgumentException("URL has expired for $url")
        if (!data.url.startsWith(serveUrl)) throw IllegalArgumentException("URL does not match this file system")
        if (!data.upload) throw IllegalArgumentException("URL is for read, not upload")
        return ExternalFile(this, parsePath(data.url.substringAfter(serveUrl)))
    }

    /** Reads a served path, which [url] wrote in [ExternalPath]'s conservative form. */
    private fun parsePath(relative: String): ExternalPath = ExternalPath.fromConservativeString(relative)

    /** Reads a path whose segments were written literally: legacy URLs and on-disk locations. */
    private fun pathFromLiteral(relative: String): ExternalPath =
        ExternalPath(relative.replace('\\', '/').split('/').filter { it.isNotEmpty() })

    /**
     * The parts of the root that back the file system itself and so are not addressable through the
     * public API: the signing key and the sidecar subtree.
     */
    private fun ExternalPath.isReserved(): Boolean =
        parts.firstOrNull().let { it == signingKeyFile || it == metadataDirectory }

    /**
     * Rejects paths that must not reach the disk:
     *
     * - segments that a hierarchical file system reads as navigation rather than as a name.
     *   [ExternalPath] segments are literal, so a path parsed from anywhere - including an escape
     *   in a signed URL - can legitimately contain `..` or a separator, and joining those onto the
     *   root would step outside it.
     * - the file system's own storage, per [isReserved].
     */
    private fun guard(path: ExternalPath) {
        if (path.parts.any { it.isEmpty() || it == "." || it == ".." || '/' in it || '\\' in it })
            throw IllegalArgumentException("Invalid file path.")
        if (path.isReserved())
            throw IllegalArgumentException("Invalid file path.")
    }

    private fun kfileFor(path: ExternalPath): KFile {
        guard(path)
        return KFile(rootKFile.fileSystem, Path(rootKFile.path, path.parts.joinToString("/")))
    }

    private val KFile.localPath: Path get() = Path(this.path.toString().removePrefix(rootKFile.path.toString()))

    /**
     * The sidecar file that stores the content type of the file at [path], inside
     * [metadataDirectory]: `photo.jpg` is described by `.metadata/photo.jpg.contenttype`.
     *
     * Only call this with a path that [kfileFor] has already accepted - it deliberately reaches into
     * the reserved subtree.
     */
    private fun contentTypeFileFor(path: ExternalPath): KFile = KFile(
        rootKFile.fileSystem,
        Path(rootKFile.path, (listOf(metadataDirectory) + path.parts).joinToString("/") + ".contenttype")
    )

    /**
     * Internal tracing helper for file operations.
     *
     * This provides telemetry tracing on JVM (via [com.lightningkite.services.telemetry.telemetryTrace] on [owner])
     * and no-op behavior on other platforms. [owner] is the file system the operation belongs to, used
     * as the span's owner.
     */
    internal suspend inline fun <T> traceFileOperation(
        owner: Namespaced,
        operation: String,
        path: String,
        storageSystem: String,
        attributes: TelemetryAttributes = emptyTelemetryAttributes(),
        crossinline block: suspend () -> T,
    ): T = withContext(Dispatchers.Io) {
        val spanAttributes = TelemetryAttributes {
            put(TelemetryKeys.File.path, owner.context.telemetrySanitization.sanitizeFilePathWithDepth(path))
            put(TelemetryKey.OfString("storage.system"), storageSystem)
            put(TelemetryKeys.Rpc.system, "filesystem")
            putAll(attributes)
        }
        owner.telemetryTrace(operation, attributes = spanAttributes) { block() }
    }


    override suspend fun flow(path: ExternalPath): Flow<ExternalPath> = traceFileOperation(
        owner = this,
        operation = "flow",
        path = path.parts.joinToString("/"),
        storageSystem = "file"
    ) {
        val kfile = kfileFor(path)
        try {
            kfile.list()
                // Names come off the disk literally, not in conservative form.
                // TODO: Just realized that sharding is probably needed for the filesystem here; some file systems have limits on the number of files in a folder
                //  In addition, this absolutely forces IO to block for retrieving the entire list
                .map { pathFromLiteral(it.localPath.toString()) }
                .filter { !it.isReserved() }
                .asFlow()
        } catch (e: FileNotFoundException) {
            emptyFlow()
        } catch (e: IOException) {
            if (contentTypeFileFor(path).exists()) emptyFlow()
            else throw e
        }
    }

    /**
     * Gets metadata about the file at [path].
     *
     * The media type is determined from:
     * 1. The `.contenttype` sidecar file if it exists
     * 2. The file extension otherwise
     *
     * Note: lastModified is always null in this implementation.
     */
    override suspend fun head(path: ExternalPath): FileInfo? = traceFileOperation(
        owner = this,
        operation = "head",
        path = path.parts.joinToString("/"),
        storageSystem = "file",
    ) {
        val kfile = kfileFor(path)
        val metadata = kfile.metadataOrNull() ?: return@traceFileOperation null
        val contentTypeFile = contentTypeFileFor(path)
        val mediaType = if (contentTypeFile.exists()) {
            contentTypeFile.source().use { source ->
                MediaType(source.buffered().readString())
            }
        } else {
            MediaType.fromExtension(path.extension)
        }

        FileInfo(
            type = mediaType,
            size = metadata.size.bytes,
            lastModified = null,
        )
    }

    /**
     * Writes content to the file at [path].
     *
     * Creates parent directories if needed and stores the media type in a sidecar file under
     * [metadataDirectory].
     */
    override suspend fun put(path: ExternalPath, content: TypedData): Unit = traceFileOperation(
        owner = this,
        operation = "put",
        path = path.parts.joinToString("/"),
        storageSystem = "file",
        attributes = telemetryAttributesOf(
            TelemetryKeys.File.size to (content.data.size ?: -1L),
            TelemetryKeys.File.contentType to content.mediaType.toString()
        )
    ) {
        val kfile = kfileFor(path)

        // Create parent directories if they don't exist
        val parent = kfile.parent
        if (parent != null && !parent.exists()) {
            parent.createDirectories()
        }

        // Write content type to content type file
        val contentTypeFile = contentTypeFileFor(path)
        contentTypeFile.parent?.takeUnless { it.exists() }?.createDirectories()
        contentTypeFile.sink().buffered().use {
            it.writeString(content.mediaType.toString())
        }

        // Write content to file
        kfile.sink().buffered().use {
            content.data.write(it)
        }
    }

    /**
     * Reads the content from the file at [path].
     *
     * The media type is determined from the `.contenttype` sidecar file or file extension.
     */
    override suspend fun get(path: ExternalPath): TypedData? = traceFileOperation(
        owner = this,
        operation = "get",
        path = path.parts.joinToString("/"),
        storageSystem = "file",
    ) {
        val kfile = kfileFor(path)

        // Try-open avoids a redundant exists() syscall before open.
        val source = try {
            kfile.source().buffered()
        } catch (e: FileNotFoundException) {
            return@traceFileOperation null
        }

        val contentTypeFile = contentTypeFileFor(path)
        val mediaType = if (contentTypeFile.exists()) {
            contentTypeFile.source().buffered().use { s ->
                MediaType(s.readString())
            }
        } else {
            MediaType.fromExtension(path.extension)
        }

        TypedData(
            Data.Source(source, kfile.fileSystem.metadataOrNull(kfile.path)?.size ?: -1),
            mediaType
        )
    }

    /**
     * Deletes the file at [path] and its content-type sidecar.
     *
     * @throws RuntimeException if deletion fails
     */
    override suspend fun delete(path: ExternalPath): Unit = traceFileOperation(
        owner = this,
        operation = "delete",
        path = path.parts.joinToString("/"),
        storageSystem = "file"
    ) {
        val kfile = kfileFor(path)
        try {
            val contentTypeFile = contentTypeFileFor(path)
            if (contentTypeFile.exists()) {
                contentTypeFile.delete()
            }

            if (kfile.exists()) {
                kfile.delete()
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to delete file: $kfile", e)
        }
    }
}
