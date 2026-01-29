package com.lightningkite.services.files

import com.lightningkite.MediaType
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.KFile
import com.lightningkite.services.data.TypedData
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A FileSystem implementation that uses kotlinx.io for local file system access.
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

        val keyFile = rootKFile.then(".signingKey")

        if (keyFile.exists()) hmac.keyDecoder(digest).decodeFromByteArrayBlocking(format, keyFile.readByteArray())
        else {
            val key = hmac.keyGenerator(digest).generateKeyBlocking()
            keyFile.writeByteArray(key.encodeToByteArrayBlocking(format))
            key
        }
    }

    override val root: KotlinxIoFile = KotlinxIoFile(rootKFile)

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

    /**
     * Parses an internal (unsigned) URL into a FileObject.
     *
     * @param url The URL to parse
     * @return A KotlinxIoFile if the URL starts with this file system's serveUrl, null otherwise
     */
    override fun parseInternalUrl(url: String): KotlinxIoFile? {
        if (!url.startsWith(serveUrl)) return null
        return KotlinxIoFile(rootKFile.then(*url.substringAfter(serveUrl).split('/').toTypedArray()))
    }

    /**
     * Parses an external (signed) URL into a FileObject.
     *
     * For file systems with signing enabled, this validates the signature and expiration.
     * For unsigned file systems, this simply parses the URL path.
     *
     * @param url The signed URL to parse
     * @return A KotlinxIoFile if the URL is valid
     * @throws IllegalArgumentException if signature verification fails, URL has expired,
     *         URL doesn't match this file system, or URL is for upload (not read)
     */
    override fun parseExternalUrl(url: String): KotlinxIoFile? {
        if (!url.startsWith(serveUrl)) return null
        return if (signedUrlDuration != null) {
            val data = DataToSign(url.substringBeforeLast("&"))
            val signature = url.substringAfterLast("&", "").substringAfter('=')
            if (!verify(data, signature)) throw IllegalArgumentException("Signature verification failed for $url")
            if (context.clock.now() > data.expires) throw IllegalArgumentException("URL has expired for $url")
            if (!data.url.startsWith(serveUrl)) throw IllegalArgumentException("URL does not match this file system")
            if (data.upload) throw IllegalArgumentException("URL is for upload, not read")
            KotlinxIoFile(rootKFile.then(*data.url.substringAfter(serveUrl).split('/').toTypedArray()))
        } else
            KotlinxIoFile(rootKFile.then(*url.substringBefore('?').substringAfter(serveUrl).split('/').toTypedArray()))
    }

    /**
     * Parses a signed upload URL into a FileObject.
     *
     * This validates that the URL is specifically marked for uploads and hasn't expired.
     *
     * @param url The signed upload URL to parse
     * @return A KotlinxIoFile if the URL is valid, null if it doesn't start with serveUrl
     * @throws IllegalArgumentException if signature verification fails, URL has expired,
     *         URL doesn't match this file system, or URL is for read (not upload)
     */
    public fun parseUploadUrl(url: String): KotlinxIoFile? {
        if (!url.startsWith(serveUrl)) return null
        val data = DataToSign(url.substringBeforeLast("&"))
        val signature = url.substringAfterLast("&", "").substringAfter('=')
        if (!verify(data, signature)) throw IllegalArgumentException("Signature verification failed for $url")
        if (context.clock.now() > data.expires) throw IllegalArgumentException("URL has expired for $url")
        if (!data.url.startsWith(serveUrl)) throw IllegalArgumentException("URL does not match this file system")
        if (!data.upload) throw IllegalArgumentException("URL is for read, not upload")
        return KotlinxIoFile(rootKFile.then(*data.url.substringAfter(serveUrl).split('/').toTypedArray()))
    }

    /**
     * A file object implementation for the kotlinx.io file system.
     *
     * This inner class represents a file or directory within the KotlinxIoPublicFileSystem.
     * It stores content type information in sidecar `.contenttype` files alongside the actual files.
     *
     * @param kfile The underlying KFile representing this file's location
     */
    public inner class KotlinxIoFile(
        public val kfile: KFile,
    ) : FileObject {
        init {
            if (!kfile.path.toString()
                    .startsWith(rootKFile.path.toString())
            ) throw IllegalArgumentException("Invalid path.  '${kfile.path}' does not start with '${rootKFile.path}'")
        }

        internal val relativePath = kfile.path.toString().removePrefix(rootKFile.path.toString()).replace('\\', '/')

        override fun toString(): String = relativePath
        override fun equals(other: Any?): Boolean = other is KotlinxIoFile && this.kfile == other.kfile

        override val name: String = kfile.path.name

        override fun then(path: String): FileObject = KotlinxIoFile(kfile.then(*path.split('/').toTypedArray()))

        override val parent: FileObject? = if (kfile == rootKFile) null else kfile.parent?.let { KotlinxIoFile(it) }

        override val url: String = serveUrl.removeSuffix("/") + '/' + relativePath.removePrefix("/")

        override val signedUrl: String
            get() = signedUrlDuration?.let { expiration ->
                DataToSign(url, context.clock.now().plus(expiration), false)
                    .signed()
            }
                ?: url

        override fun uploadUrl(timeout: Duration): String {
            return DataToSign(url, context.clock.now().plus(timeout), true)
                .signed()
        }

        private val absolutePath: KFile
            get() = kfile.resolved

        /**
         * Path to the sidecar file that stores this file's content type.
         *
         * For a file named `photo.jpg`, the content type is stored in `photo.jpg.contenttype`.
         */
        private val contentTypePath: KFile
            get() = kfile.parent!!.then("${kfile.name}.contenttype")

        /**
         * Lists the contents of this directory.
         *
         * Filters out:
         * - `.contenttype` sidecar files
         * - `.signingKey` file
         *
         * @return A list of FileObjects, null if this is a file (not a directory) or doesn't exist
         */
        override suspend fun list(): List<FileObject>? = traceFileOperation(
            context = context,
            operation = "list",
            path = relativePath,
            storageSystem = "file"
        ) {
            try {
                kfile.list().filter { !it.name.endsWith(".contenttype") && it.name != ".signingKey" }.map {
                    KotlinxIoFile(it)
                }
            } catch (e: FileNotFoundException) {
                null
            } catch (e: IOException) {
                if (contentTypePath.exists()) null
                else throw e
            }
        }

        /**
         * Gets metadata about this file.
         *
         * The media type is determined from:
         * 1. The `.contenttype` sidecar file if it exists
         * 2. The file extension otherwise
         *
         * Note: lastModified is always null in this implementation.
         *
         * @return FileInfo with type and size, or null if the file doesn't exist
         */
        override suspend fun head(): FileInfo? = traceFileOperation(
            context = context,
            operation = "head",
            path = relativePath,
            storageSystem = "file"
        ) {
            val metadata = kfile.metadataOrNull() ?: return@traceFileOperation null
            val mediaType = if (contentTypePath.exists()) {
                contentTypePath.source().use { source ->
                    MediaType(source.buffered().readString())
                }
            } else {
                MediaType.fromExtension(kfile.path.name.substringAfterLast('.', ""))
            }

            FileInfo(
                type = mediaType,
                size = metadata.size,
                lastModified = null,
            )
        }

        /**
         * Writes content to this file.
         *
         * Creates parent directories if needed and stores the media type in a `.contenttype` sidecar file.
         *
         * @param content The typed data to write
         */
        override suspend fun put(content: TypedData): Unit = traceFileOperation(
            context = context,
            operation = "put",
            path = relativePath,
            storageSystem = "file",
            attributes = mapOf(
                "file.size" to (content.data.size ?: -1L),
                "file.content_type" to content.mediaType.toString()
            )
        ) {
            // Create parent directories if they don't exist
            val parent = kfile.parent
            if (parent != null && !parent.exists()) {
                parent.createDirectories()
            }

            // Write content type to content type file
            contentTypePath.sink().buffered().use {
                it.writeString(content.mediaType.toString())
            }

            // Write content to file
            kfile.sink().buffered().use {
                content.data.write(it)
            }
        }

        /**
         * Reads the content from this file.
         *
         * The media type is determined from the `.contenttype` sidecar file or file extension.
         *
         * @return The file's content as TypedData, or null if the file doesn't exist
         */
        override suspend fun get(): TypedData? = traceFileOperation(
            context = context,
            operation = "get",
            path = relativePath,
            storageSystem = "file"
        ) {
            if (!kfile.exists()) {
                return@traceFileOperation null
            }

            val mediaType = if (contentTypePath.exists()) {
                contentTypePath.source().buffered().use { source ->
                    MediaType(source.readString())
                }
            } else {
                MediaType.fromExtension(kfile.path.name.substringAfterLast('.', ""))
            }

            TypedData(Data.Source(kfile.source().buffered(), kfile.fileSystem.metadataOrNull(kfile.path)?.size ?: -1), mediaType)
        }

        /**
         * Deletes this file and its `.contenttype` sidecar file.
         *
         * @throws RuntimeException if deletion fails
         */
        override suspend fun delete(): Unit = traceFileOperation(
            context = context,
            operation = "delete",
            path = relativePath,
            storageSystem = "file"
        ) {
            try {
                if (contentTypePath.exists()) {
                    contentTypePath.delete()
                }

                if (kfile.exists()) {
                    kfile.delete()
                }
            } catch (e: Exception) {
                throw RuntimeException("Failed to delete file: ${kfile}", e)
            }
        }

        override fun hashCode(): Int = kfile.hashCode() + 47
    }
}

/*
 * TODO: API Recommendations
 *
 * 1. The sidecar `.contenttype` file approach creates filesystem clutter. Consider:
 *    - Using extended attributes (xattr) where supported
 *    - Storing a single index file with all metadata
 *    - Making the storage strategy configurable
 *
 * 2. The signing key is stored unencrypted in `.signingKey`. For production use, consider:
 *    - Warning in docs about file permissions
 *    - Supporting external key management (env vars, key vaults)
 *    - Encrypting the key at rest
 *
 * 3. head() always returns null for lastModified. Consider populating it from file metadata.
 *
 * 4. list() filters out internal files but doesn't handle other hidden/system files.
 *    Consider adding a parameter to control visibility of hidden files.
 *
 * 5. The URL parsing splits on '/' without handling URL encoding. Filenames with special
 *    characters may not work correctly. Consider proper URL encoding/decoding.
 */