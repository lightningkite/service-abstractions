package com.lightningkite.services.files.s3

import com.lightningkite.MediaType
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.FileInfo
import com.lightningkite.services.files.FileObject
import com.lightningkite.services.http.client
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.time.toKotlinInstant
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.*
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * An implementation of [FileObject] that uses AWS S3 for storage.
 */
public class S3FileObject(
    public val system: S3PublicFileSystem,
    public val path: File
) : FileObject {
    
    /**
     * The Unix-style path for this file.
     */
    private val unixPath: String get() = path.toString().replace('\\', '/')
    
    override fun then(path: String): S3FileObject = S3FileObject(system, this.path.resolve(path))
    
    override val name: String get() = path.name
    
    override val parent: FileObject?
        get() = path.parentFile?.let { S3FileObject(system, it) } ?: if (unixPath.isNotEmpty()) system.root else null

    override suspend fun list(): List<FileObject>? = withContext(Dispatchers.IO) {
        try {
            val results = ArrayList<S3FileObject>()
            var token: String? = null
            while (true) {
                val r = system.s3Async.listObjectsV2 {
                    it.bucket(system.bucket)
                    it.prefix(unixPath)
                    it.delimiter("/")
                    token?.let { t -> it.continuationToken(t) }
                }.await()
                results += r.contents().filter { !it.key().substringAfter(unixPath).contains('/') }
                    .map { S3FileObject(system, File(it.key())) }
                if (r.isTruncated) token = r.nextContinuationToken()
                else break
            }
            results
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun head(): FileInfo? = withContext(Dispatchers.IO) {
        try {
            system.s3Async.headObject {
                it.bucket(system.bucket)
                it.key(unixPath)
            }.await().let {
                FileInfo(
                    type = MediaType(it.contentType()),
                    size = it.contentLength(),
                    lastModified = it.lastModified().toKotlinInstant()
                )
            }
        } catch (e: NoSuchKeyException) {
            null
        }
    }

    override suspend fun put(content: TypedData) {
        withContext(Dispatchers.IO) {
            system.s3.putObject(PutObjectRequest.builder().also {
                it.bucket(system.bucket)
                it.key(unixPath)
                it.contentType(content.mediaType.toString())
            }.build(), content.data.size.let { size ->
                if (size > 0) {
                    RequestBody.fromBytes(content.data.bytes())
                } else {
                    RequestBody.fromBytes(content.data.bytes())
                }
            })
        }
    }

    override suspend fun get(): TypedData? = withContext(Dispatchers.IO) {
        try {
            val response = system.s3.getObject(
                GetObjectRequest.builder().also {
                    it.bucket(system.bucket)
                    it.key(unixPath)
                }.build()
            )
            
            val contentType = response.response().contentType() ?: "application/octet-stream"
            val mediaType = MediaType(contentType)
            val bytes = response.readAllBytes()
            
            TypedData(
                data = Data.Bytes(bytes),
                mediaType = mediaType
            )
        } catch (e: NoSuchKeyException) {
            null
        }
    }

    override suspend fun copyTo(other: FileObject) {
        if (other is S3FileObject && other.system.bucket == system.bucket) {
            withContext(Dispatchers.IO) {
                system.s3Async.copyObject {
                    it.sourceBucket(system.bucket)
                    it.destinationBucket(system.bucket)
                    it.sourceKey(unixPath)
                    it.destinationKey(other.unixPath)
                }.await()
            }
        } else {
            super.copyTo(other)
        }
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) {
            system.s3Async.deleteObject {
                it.bucket(system.bucket)
                it.key(unixPath)
            }.await()
        }
    }

    /**
     * Encodes a string for use in a URL path, preserving slashes.
     */
    private fun String.encodeURLPathSafe(): String = URLEncoder.encode(this, Charsets.UTF_8)
        .replace("%2F", "/")
        .replace("+", "%20")

    override val url: String
        get() = "https://${system.bucket}.s3.${system.region.id()}.amazonaws.com/${unixPath.encodeURLPathSafe()}"

    override val signedUrl: String
        get() = system.signedUrlDuration?.let { e ->
            val creds = system.creds()
            val accessKey = creds.access
            val tokenPreEncoded = creds.tokenPreEncoded
            var dateOnly: String
            val date = java.time.ZonedDateTime.now(ZoneOffset.UTC).run {
                buildString {
                    this.append(year.toString().padStart(4, '0'))
                    this.append(monthValue.toString().padStart(2, '0'))
                    this.append(dayOfMonth.toString().padStart(2, '0'))
                    dateOnly = toString()
                    append("T")
                    this.append(hour.toString().padStart(2, '0'))
                    this.append(minute.toString().padStart(2, '0'))
                    this.append(second.toString().padStart(2, '0'))
                    append("Z")
                }
            }
            val region = system.region.id()
            val objectPath = unixPath
            val preHeaders = tokenPreEncoded?.let {
                "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=${accessKey}%2F$dateOnly%2F$region%2Fs3%2Faws4_request&X-Amz-Date=$date&X-Amz-Expires=${e.inWholeSeconds}&X-Amz-Security-Token=${it}&X-Amz-SignedHeaders=host"
            } ?: run {
                "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=${accessKey}%2F$dateOnly%2F$region%2Fs3%2Faws4_request&X-Amz-Date=$date&X-Amz-Expires=${e.inWholeSeconds}&X-Amz-SignedHeaders=host"
            }
            val hashHolder = ByteArray(32)
            val canonicalRequestHasher = java.security.MessageDigest.getInstance("SHA-256")
            canonicalRequestHasher.update(CONSTANT_BYTES_A)
            canonicalRequestHasher.update(objectPath.removePrefix("/").encodeURLPathSafe().toByteArray())
            canonicalRequestHasher.update(CONSTANT_BYTE_NEWLINE)
            canonicalRequestHasher.update(preHeaders.toByteArray())
            canonicalRequestHasher.update(CONSTANT_BYTES_C)
            canonicalRequestHasher.update(system.bucket.toByteArray())
            canonicalRequestHasher.update(CONSTANT_BYTES_D)
            canonicalRequestHasher.update(system.region.id().toByteArray())
            canonicalRequestHasher.update(CONSTANT_BYTES_E)
            canonicalRequestHasher.digest(hashHolder, 0, 32)
            val canonicalRequestHash = hashHolder.toHex()
            val finalHasher = javax.crypto.Mac.getInstance("HmacSHA256")
            finalHasher.init(system.signingKey(dateOnly))
            finalHasher.update(CONSTANT_BYTES_F)
            finalHasher.update(date.toByteArray())
            finalHasher.update(CONSTANT_BYTE_NEWLINE)
            finalHasher.update(dateOnly.toByteArray())
            finalHasher.update(CONSTANT_BYTE_SLASH)
            finalHasher.update(system.region.id().toByteArray())
            finalHasher.update(CONSTANT_BYTES_H)
            finalHasher.update(canonicalRequestHash.toByteArray())
            finalHasher.doFinal(hashHolder, 0)
            val regeneratedSig = hashHolder.toHex()
            val result = "${url}?$preHeaders&X-Amz-Signature=$regeneratedSig"
            result
        } ?: url

    override fun uploadUrl(timeout: Duration): String = 
        system.signedUrlDuration?.let { _ ->
            val creds = system.creds()
            val accessKey = creds.access
            val tokenPreEncoded = creds.tokenPreEncoded
            var dateOnly: String
            val date = java.time.ZonedDateTime.now(ZoneOffset.UTC).run {
                buildString {
                    this.append(year.toString().padStart(4, '0'))
                    this.append(monthValue.toString().padStart(2, '0'))
                    this.append(dayOfMonth.toString().padStart(2, '0'))
                    dateOnly = toString()
                    append("T")
                    this.append(hour.toString().padStart(2, '0'))
                    this.append(minute.toString().padStart(2, '0'))
                    this.append(second.toString().padStart(2, '0'))
                    append("Z")
                }
            }
            val region = system.region.id()
            val objectPath = unixPath
            val preHeaders = tokenPreEncoded?.let {
                "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=${accessKey}%2F$dateOnly%2F$region%2Fs3%2Faws4_request&X-Amz-Date=$date&X-Amz-Expires=${timeout.inWholeSeconds}&X-Amz-Security-Token=${it}&X-Amz-SignedHeaders=host"
            } ?: run {
                "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=${accessKey}%2F$dateOnly%2F$region%2Fs3%2Faws4_request&X-Amz-Date=$date&X-Amz-Expires=${timeout.inWholeSeconds}&X-Amz-SignedHeaders=host"
            }
            
            // For PUT requests, we need to modify the canonical request
            val putConstantBytesA = "PUT\n/".toByteArray()
            
            val hashHolder = ByteArray(32)
            val canonicalRequestHasher = java.security.MessageDigest.getInstance("SHA-256")
            canonicalRequestHasher.update(putConstantBytesA)
            canonicalRequestHasher.update(objectPath.removePrefix("/").encodeURLPathSafe().toByteArray())
            canonicalRequestHasher.update(CONSTANT_BYTE_NEWLINE)
            canonicalRequestHasher.update(preHeaders.toByteArray())
            canonicalRequestHasher.update(CONSTANT_BYTES_C)
            canonicalRequestHasher.update(system.bucket.toByteArray())
            canonicalRequestHasher.update(CONSTANT_BYTES_D)
            canonicalRequestHasher.update(system.region.id().toByteArray())
            canonicalRequestHasher.update(CONSTANT_BYTES_E)
            canonicalRequestHasher.digest(hashHolder, 0, 32)
            val canonicalRequestHash = hashHolder.toHex()
            val finalHasher = javax.crypto.Mac.getInstance("HmacSHA256")
            finalHasher.init(system.signingKey(dateOnly))
            finalHasher.update(CONSTANT_BYTES_F)
            finalHasher.update(date.toByteArray())
            finalHasher.update(CONSTANT_BYTE_NEWLINE)
            finalHasher.update(dateOnly.toByteArray())
            finalHasher.update(CONSTANT_BYTE_SLASH)
            finalHasher.update(system.region.id().toByteArray())
            finalHasher.update(CONSTANT_BYTES_H)
            finalHasher.update(canonicalRequestHash.toByteArray())
            finalHasher.doFinal(hashHolder, 0)
            val regeneratedSig = hashHolder.toHex()
            val result = "${url}?$preHeaders&X-Amz-Signature=$regeneratedSig"
            result
        } ?: system.signer.presignPutObject {
            it.signatureDuration(timeout.toJavaDuration())
            it.putObjectRequest {
                it.bucket(system.bucket)
                it.key(unixPath)
            }
        }.url().toString()


    internal val signedUrlOfficial: String
        get() = system.signedUrlDuration?.let { duration ->
            system.signer.presignGetObject {
                it.signatureDuration(duration.toJavaDuration())
                it.getObjectRequest {
                    it.bucket(system.bucket)
                    it.key(unixPath)
                }
            }.url().toString()
        } ?: url

    internal fun uploadUrlOfficial(timeout: Duration): String =
        system.signer.presignPutObject {
            it.signatureDuration(timeout.toJavaDuration())
            it.putObjectRequest {
                it.bucket(system.bucket)
                it.key(unixPath)
            }
        }.url().toString()

    override fun toString(): String = url
    override fun equals(other: Any?): Boolean = other is S3FileObject && other.system == system && other.unixPath == unixPath
    override fun hashCode(): Int = 31 * system.hashCode() + unixPath.hashCode()

    internal fun assertSignatureValid(queryParams: String) {
        if (system.signedUrlDuration != null) {
            try {
                val headers = queryParams.split('&').associate {
                    URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(
                        it.substringAfter(
                            '=', ""
                        ), Charsets.UTF_8
                    )
                }
                val secretKey = system.credentialProvider.resolveCredentials().secretAccessKey()
                val objectPath = path.path.replace("\\", "/")
                val date = headers["X-Amz-Date"] ?: throw IllegalArgumentException("No query parameter 'X-Amz-Date' found.")
                val algorithm = headers["X-Amz-Algorithm"] ?: throw IllegalArgumentException("No query parameter 'X-Amz-Algorithm' found.")
                val credential = headers["X-Amz-Credential"] ?: throw IllegalArgumentException("No query parameter 'X-Amz-Credential' found.")
                val scope = credential.substringAfter("/")

                val canonicalRequest = """
                GET
                ${"/" + objectPath.removePrefix("/")}
                ${queryParams.substringBefore("&X-Amz-Signature=").split('&').sorted().joinToString("&")}
                host:${system.bucket}.s3.${system.region.id()}.amazonaws.com
                
                host
                UNSIGNED-PAYLOAD
                """.trimIndent()

                val toSignString = """
                $algorithm
                $date
                $scope
                ${canonicalRequest.sha256()}
                """.trimIndent()

                val signingKey = "AWS4$secretKey".toByteArray().let { date.substringBefore('T').toByteArray().mac(it) }
                    .let { system.region.id().toByteArray().mac(it) }.let { "s3".toByteArray().mac(it) }
                    .let { "aws4_request".toByteArray().mac(it) }

                val regeneratedSig = toSignString.toByteArray().mac(signingKey).toHex()

                if (regeneratedSig == headers["X-Amz-Signature"]!!) return
            } catch (e: Exception) {
                /* squish */
            }
            return runBlocking {
                val response = client.get("$url?$queryParams") {
                    header("Range", "0-0")
                }
                if(!response.status.isSuccess()) throw IllegalArgumentException("Could not verify signature")
            }
        }
    }
    
    public companion object {
        private val CONSTANT_BYTES_A = "GET\n/".toByteArray()
        private val CONSTANT_BYTES_C = "\nhost:".toByteArray()
        private val CONSTANT_BYTES_D = ".s3.".toByteArray()
        private val CONSTANT_BYTES_E = (".amazonaws.com\n\nhost\nUNSIGNED-PAYLOAD").toByteArray()
        private val CONSTANT_BYTES_F = "AWS4-HMAC-SHA256\n".toByteArray()
        private val CONSTANT_BYTE_NEWLINE = '\n'.code.toByte()
        private val CONSTANT_BYTE_SLASH = '/'.code.toByte()
        private val CONSTANT_BYTES_H = "/s3/aws4_request\n".toByteArray()
        
        /**
         * Converts a byte array to a hexadecimal string.
         */
        private fun ByteArray.toHex(): String = buildString {
            for(item in this@toHex) {
                append(item.toUByte().toString(16).padStart(2, '0'))
            }
        }
        
        /**
         * Applies a MAC operation to this byte array using the given key.
         */
        private fun ByteArray.mac(key: ByteArray): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(this)
        
        /**
         * Computes the SHA-256 hash of this string.
         */
        private fun String.sha256(): String = java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()
    }
}