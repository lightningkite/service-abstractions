package com.lightningkite.services.files

import com.lightningkite.MediaType
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.KFile
import com.lightningkite.services.data.TypedData
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * A FileSystem implementation that uses kotlinx.io.files.FileSystem.
 */
public class KotlinxIoPublicFileSystem(
    override val name: String,
    override val context: SettingContext,
    public val rootKFile: KFile,
    public val serveUrl: String = "http://localhost:8080/files/",
    public val signatureReadDuration: Duration = 1.hours,
): PublicFileSystem {
    init {
        rootKFile.createDirectories()
    }
    private val hmac = CryptographyProvider.Default.get(HMAC)
    private val shaVersion = SHA256
    private val sha = CryptographyProvider.Default.get(shaVersion)
    private val secretBytes = run {
        val signingKeyPath = rootKFile.then(".signingKey")
        if(signingKeyPath.exists()) {
            signingKeyPath.readByteArray()
        } else {
            val random = CryptographyRandom.nextBytes(32)
            signingKeyPath.writeByteArray(random)
            random
        }
    }
    private val key = hmac.keyDecoder(shaVersion).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, secretBytes)

    override val root: KotlinxIoFile = KotlinxIoFile(rootKFile)
    
    override val rootUrls: List<String> = listOf(serveUrl)

    internal data class DataToSign constructor(val url: String, val expires: Instant, val upload: Boolean) {
        constructor(urlWithQuery: String): this(
            url = urlWithQuery.substringBefore("?"),
            expires = urlWithQuery.substringAfter("?expires=", "0").takeWhile { it.isDigit() }.toLong().let { Instant.fromEpochMilliseconds(it) },
            upload = urlWithQuery.contains("&upload=true")
        )
        override fun toString(): String = "$url?expires=${expires.toEpochMilliseconds()}" + if(upload) "&upload=true" else ""
    }
    internal fun sign(data: DataToSign): String {
        return key.signatureGenerator().generateSignatureBlocking(data.toString().encodeToByteArray()).toHexString()
    }
    internal fun verify(data: DataToSign, signature: String): Boolean {
        return key.signatureVerifier().tryVerifySignatureBlocking(data.toString().encodeToByteArray(), signature.hexToByteArray())
    }
    internal fun DataToSign.signed() = toString() + "&signature=" + sign(this)
    override fun parseInternalUrl(url: String): KotlinxIoFile? {
        if(!url.startsWith(serveUrl)) return null
        return KotlinxIoFile(rootKFile.then(*url.substringAfter(serveUrl).split('/').toTypedArray()))
    }
    override fun parseExternalUrl(url: String): KotlinxIoFile? {
        if(!url.startsWith(serveUrl)) return null
        val data = DataToSign(url.substringBeforeLast("&"))
        val signature = url.substringAfterLast("&", "").substringAfter('=')
        if(!verify(data, signature)) throw IllegalArgumentException("Signature verification failed for $url")
        if(context.clock.now() > data.expires) throw IllegalArgumentException("URL has expired for $url")
        if(!data.url.startsWith(serveUrl)) throw IllegalArgumentException("URL does not match this file system")
        if(data.upload) throw IllegalArgumentException("URL is for upload, not read")
        return KotlinxIoFile(rootKFile.then(*data.url.substringAfter(serveUrl).split('/').toTypedArray()))
    }
    public fun parseUploadUrl(url: String): KotlinxIoFile? {
        if(!url.startsWith(serveUrl)) return null
        val data = DataToSign(url.substringBeforeLast("&"))
        val signature = url.substringAfterLast("&", "").substringAfter('=')
        if(!verify(data, signature)) throw IllegalArgumentException("Signature verification failed for $url")
        if(context.clock.now() > data.expires) throw IllegalArgumentException("URL has expired for $url")
        if(!data.url.startsWith(serveUrl)) throw IllegalArgumentException("URL does not match this file system")
        if(!data.upload) throw IllegalArgumentException("URL is for read, not upload")
        return KotlinxIoFile(rootKFile.then(*data.url.substringAfter(serveUrl).split('/').toTypedArray()))
    }

    /**
     * A file object implementation for the kotlinx.io file system.
     */
    public inner class KotlinxIoFile(
        public val kfile: KFile
    ) : FileObject {
        init {
            if(!kfile.path.toString().startsWith(rootKFile.path.toString())) throw IllegalArgumentException("Invalid path.  '${kfile.path}' does not start with '${rootKFile.path}'")
        }
        internal val relativePath = kfile.path.toString().removePrefix(rootKFile.path.toString())

        override fun toString(): String = relativePath
        override fun equals(other: Any?): Boolean = other is KotlinxIoFile && this.kfile == other.kfile

        override val name: String = kfile.path.name

        override fun then(path: String): FileObject = KotlinxIoFile(kfile.then(*path.split('/').toTypedArray()))
        
        override val parent: FileObject? = if(kfile == rootKFile) null else kfile.parent?.let { KotlinxIoFile(it) }
        
        override val url: String = serveUrl + relativePath.removePrefix("/")
        
        override val signedUrl: String get() {
            return DataToSign(url, context.clock.now().plus(signatureReadDuration), false)
                .signed()
        }
        
        override fun uploadUrl(timeout: Duration): String {
            return DataToSign(url, context.clock.now().plus(timeout), true)
                .signed()
        }

        private val absolutePath: KFile
            get() = kfile.resolved
            
        private val contentTypePath: KFile
            get() = kfile.parent!!.then("${kfile.name}.contenttype")

        override suspend fun list(): List<FileObject>? {
            return try {
                kfile.list().filter { !it.name.endsWith(".contenttype") && it.name != ".signingKey" }.map {
                    KotlinxIoFile(it)
                }
            } catch (e: FileNotFoundException) {
                null
            } catch (e: IOException) {
                if(contentTypePath.exists()) null
                else throw e
            }
        }
        
        override suspend fun head(): FileInfo? {
            val metadata = kfile.metadataOrNull() ?: return null
            val mediaType = if (contentTypePath.exists()) {
                contentTypePath.source().use { source ->
                    MediaType(source.buffered().readString())
                }
            } else {
                MediaType.fromExtension(kfile.path.name.substringAfterLast('.', ""))
            }

            return FileInfo(
                type = mediaType,
                size = metadata.size,
                lastModified = null,
            )
        }
        
        override suspend fun put(content: TypedData) {
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
        
        override suspend fun get(): TypedData? {
            if (!kfile.exists()) {
                return null
            }

            val mediaType = if (contentTypePath.exists()) {
                contentTypePath.source().buffered().use { source ->
                    MediaType(source.readString())
                }
            } else {
                MediaType.fromExtension(kfile.path.name.substringAfterLast('.', ""))
            }

            val source = kfile.source()
            return TypedData(Data.Source(source.buffered()), mediaType)
        }
        
        override suspend fun delete() {
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
    }
}