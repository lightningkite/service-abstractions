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
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
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
    public val serveUrl: String = "http://localhost:8080/files",
    public val signatureReadDuration: Duration = 1.hours,
): MetricTrackingPublicFileSystem() {
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

    internal data class DataToSign(val url: String, val expires: Instant, val upload: Boolean) {
        constructor(urlWithQuery: String): this(
            url = urlWithQuery.substringBefore("?"),
            expires = urlWithQuery.substringAfter("?expires=", "0").takeWhile { it.isDigit() }.toLong().let { Instant.fromEpochMilliseconds(it) },
            upload = urlWithQuery.contains("&upload=true")
        )
        override fun toString(): String = "url=$url&expires=${expires.toEpochMilliseconds()}" + if(upload) "&upload=true" else ""
    }
    internal fun sign(data: DataToSign): String {
        return key.signatureGenerator().generateSignatureBlocking(data.toString().encodeToByteArray()).toHexString()
    }
    internal fun verify(data: DataToSign, signature: String): Boolean {
        return key.signatureVerifier().tryVerifySignatureBlocking(data.toString().encodeToByteArray(), signature.hexToByteArray())
    }
    internal fun DataToSign.signed() = toString() + "&signature=" + sign(this)
    override fun parseSignedUrlForRead(url: String): KotlinxIoFile {
        val data = DataToSign(url.substringBeforeLast("&"))
        val signature = url.substringAfterLast("&", "").substringAfter('=')
        if(!verify(data, signature)) throw IllegalArgumentException("Signature verification failed for $url")
        if(context.clock.now() > data.expires) throw IllegalArgumentException("URL has expired for $url")
        if(!data.url.startsWith(serveUrl)) throw IllegalArgumentException("URL does not match this file system")
        if(data.upload) throw IllegalArgumentException("URL is for upload, not read")
        return KotlinxIoFile(rootKFile.then(*data.url.substringAfter(serveUrl).split('/').toTypedArray()))
    }
    override fun parseSignedUrlForWrite(url: String): KotlinxIoFile {
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
    ) : MetricTrackingFileObject() {
        internal val relativePath get() = kfile.path

        override fun toString(): String = relativePath.toString()
        override fun equals(other: Any?): Boolean = other is KotlinxIoFile && this.relativePath == other.relativePath

        init { if(relativePath.toString().contains("..")) throw IllegalArgumentException("Invalid relative path: $relativePath")}
        init { if(relativePath.isAbsolute) throw IllegalArgumentException("Invalid relative path: $relativePath")}

        override val name: String = relativePath.name

        override fun resolve(path: String): FileObject = KotlinxIoFile(kfile.then(path))
        
        override val parent: FileObject? = kfile.parent?.let { KotlinxIoFile(it) }
        
        override val url: String = "$serveUrl/${relativePath}"
        
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

        override suspend fun listImpl(): List<FileObject>? {
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
        
        override suspend fun headImpl(): FileInfo? {
            val metadata = kfile.metadataOrNull() ?: return null
            val mediaType = if (contentTypePath.exists()) {
                contentTypePath.source().use { source ->
                    MediaType(source.buffered().readString())
                }
            } else {
                MediaType.fromExtension(relativePath.name)
            }

            return FileInfo(
                type = mediaType,
                size = metadata.size,
                lastModified = null,
            )
        }
        
        override suspend fun putImpl(content: TypedData) {
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
        
        override suspend fun getImpl(): TypedData? {
            if (!kfile.exists()) {
                return null
            }

            val mediaType = if (contentTypePath.exists()) {
                contentTypePath.source().buffered().use { source ->
                    MediaType(source.readString())
                }
            } else {
                MediaType.fromExtension(relativePath.name)
            }

            val source = kfile.source()
            return TypedData(Data.Source(source.buffered()), mediaType)
        }
        
        override suspend fun deleteImpl() {
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