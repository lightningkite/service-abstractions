package com.lightningkite.services.files

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.*
import com.lightningkite.services.kfile.KFile
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.io.*
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A [PublicFileSystem] implementation that uses kotlinx.io for local file system access.
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
public class KotlinxIoPublicFileSystem(
    override val name: String,
    override val context: SettingContext,
    public val rootKFile: KFile,
    public val serveUrl: String = "http://localhost:8080/files/",
    public val signedUrlDuration: Duration? = null,
) : PublicFileSystem {
    init {
        rootKFile.createDirectories()
    }

    private val signingKeyFile = ".signingKey"

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

    override val rootUrls: List<String> = listOf(serveUrl)

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

    override fun url(path: ExternalPath): String =
        serveUrl.removeSuffix("/") + '/' + path.parts.joinToString("/")

    override fun signUrl(path: ExternalPath, timeout: Duration?): String {
        val u = url(path)
        return (timeout ?: signedUrlDuration)?.let { expiration ->
            DataToSign(u, context.clock.now().plus(expiration), false).signed()
        } ?: u
    }

    override fun uploadUrl(path: ExternalPath, timeout: Duration): String =
        DataToSign(url(path), context.clock.now().plus(timeout), true).signed()

    /**
     * Parses an internal (unsigned) URL into an [ExternalFile].
     *
     * @return An ExternalFile if the URL starts with this file system's serveUrl, null otherwise
     */
    override fun parseInternalUrl(url: String): ExternalFile? {
        if (!url.startsWith(serveUrl)) return null
        return ExternalFile(this, parsePath(url.substringAfter(serveUrl)))
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

    private fun parsePath(relative: String): ExternalPath =
        ExternalPath(relative.replace('\\', '/').split("/").filter { it.isNotEmpty() })

    /**
     * Rejects operations directly against the `.signingKey` file, which backs URL signing and
     * must never be readable/writable through the public file API. Traversal (`.`/`..`) is
     * already rejected by [ExternalPath]'s constructor.
     */
    private fun guard(path: ExternalPath) {
        if (path.parts.size == 1 && path.parts[0] == signingKeyFile)
            throw IllegalArgumentException("Invalid file path.")
    }

    private fun kfileFor(path: ExternalPath): KFile =
        KFile(rootKFile.fileSystem, Path(rootKFile.path, path.parts.joinToString("/")))

    private val KFile.localPath: Path get() = Path(this.path.toString().removePrefix(rootKFile.path.toString()))

    /**
     * Path to the sidecar file that stores a file's content type.
     *
     * For a file named `photo.jpg`, the content type is stored in `photo.jpg.contenttype`.
     */
    private fun contentTypePath(kfile: KFile): KFile = kfile.parent!!.then("${kfile.name}.contenttype")

    /**
     * Lists the contents of the directory at [path].
     *
     * Filters out `.contenttype` sidecar files and the `.signingKey` file.
     *
     * @return A list of child paths, null if this is a file (not a directory) or doesn't exist
     */
    override suspend fun list(path: ExternalPath): List<ExternalPath>? = traceFileOperation(
        owner = this,
        operation = "list",
        path = path.parts.joinToString("/"),
        storageSystem = "file"
    ) {
        guard(path)
        val kfile = kfileFor(path)
        try {
            kfile.list()
                .filter { !it.name.endsWith(".contenttype") && it.name != signingKeyFile }
                .map { parsePath(it.localPath.toString()) }
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IOException) {
            if (contentTypePath(kfile).exists()) null
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
        attributes = mapOf("rpc.system" to "filesystem")
    ) {
        guard(path)
        val kfile = kfileFor(path)
        val metadata = kfile.metadataOrNull() ?: return@traceFileOperation null
        val contentTypePath = contentTypePath(kfile)
        val mediaType = if (contentTypePath.exists()) {
            contentTypePath.source().use { source ->
                MediaType(source.buffered().readString())
            }
        } else {
            MediaType.fromExtension(path.extension)
        }

        FileInfo(
            type = mediaType,
            size = metadata.size,
            lastModified = null,
        )
    }

    /**
     * Writes content to the file at [path].
     *
     * Creates parent directories if needed and stores the media type in a `.contenttype` sidecar file.
     */
    override suspend fun put(path: ExternalPath, content: TypedData): Unit = traceFileOperation(
        owner = this,
        operation = "put",
        path = path.parts.joinToString("/"),
        storageSystem = "file",
        attributes = mapOf(
            "file.size" to (content.data.size ?: -1L),
            "file.content_type" to content.mediaType.toString()
        )
    ) {
        guard(path)
        val kfile = kfileFor(path)

        // Create parent directories if they don't exist
        val parent = kfile.parent
        if (parent != null && !parent.exists()) {
            parent.createDirectories()
        }

        // Write content type to content type file
        contentTypePath(kfile).sink().buffered().use {
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
        attributes = mapOf("rpc.system" to "filesystem")
    ) {
        guard(path)
        val kfile = kfileFor(path)

        // Try-open avoids a redundant exists() syscall before open.
        val source = try {
            kfile.source().buffered()
        } catch (e: FileNotFoundException) {
            return@traceFileOperation null
        }

        val contentTypePath = contentTypePath(kfile)
        val mediaType = if (contentTypePath.exists()) {
            contentTypePath.source().buffered().use { s ->
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
     * Deletes the file at [path] and its `.contenttype` sidecar file.
     *
     * @throws RuntimeException if deletion fails
     */
    override suspend fun delete(path: ExternalPath): Unit = traceFileOperation(
        owner = this,
        operation = "delete",
        path = path.parts.joinToString("/"),
        storageSystem = "file"
    ) {
        guard(path)
        val kfile = kfileFor(path)
        try {
            val contentTypePath = contentTypePath(kfile)
            if (contentTypePath.exists()) {
                contentTypePath.delete()
            }

            if (kfile.exists()) {
                kfile.delete()
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to delete file: $kfile", e)
        }
    }
}
