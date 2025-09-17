package com.lightningkite.services.files.s3

import aws.sdk.kotlin.services.s3.copyObject
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import com.lightningkite.MediaType
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.FileInfo
import com.lightningkite.services.files.FileObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.net.URLEncoder
import kotlin.time.toKotlinInstant
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaDuration

/**
 * An implementation of [FileObject] that uses AWS S3 for storage.
 */
public class S3FileObject(
    public val system: S3PublicFileSystem,
    public val path: Path
) : FileObject {
    
    /**
     * The Unix-style path for this file.
     */
    private val unixPath: String get() = path.toString().replace('\\', '/')
    
    override fun then(path: String): S3FileObject = S3FileObject(system, this.path.resolve(path))
    
    override val name: String get() = path.name
    
    override val parent: FileObject?
        get() = path.parent?.let { S3FileObject(system, it) } ?: if (unixPath.isNotEmpty()) system.root else null

    override suspend fun list(): List<FileObject>? = withContext(Dispatchers.IO) {
        try {
            val results = ArrayList<S3FileObject>()
            var token: String? = null
            while (true) {
                val r = system.s3.listObjectsV2 {
                    bucket = system.bucket
                    prefix = unixPath
                    delimiter = "/"
                    token?.let { t -> continuationToken = t }
                }
                results += r.contents!!.filter { !it.key!!.substringAfter(unixPath).contains('/') }
                    .map { S3FileObject(system, Path(it.key!!)) }
                if (r.isTruncated!!) token = r.nextContinuationToken!!
                else break
            }
            results
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun head(): FileInfo? = withContext(Dispatchers.IO) {
        try {
            system.s3.headObject {
                bucket = system.bucket
                key = unixPath
            }.let {
                FileInfo(
                    type = MediaType(it.contentType!!),
                    size = it.contentLength!!,
                    lastModified = Instant.fromEpochMilliseconds(it.lastModified!!.epochMilliseconds)
                )
            }
        } catch (e: NoSuchKey) {
            null
        }
    }

    override suspend fun put(content: TypedData) {
        withContext(Dispatchers.IO) {
            system.s3.putObject {
                bucket = system.bucket
                key = unixPath
                contentType = content.mediaType.toString()
                this.body = ByteStream.fromBytes(content.data.bytes())
            }
        }
    }

    override suspend fun get(): TypedData? = withContext(Dispatchers.IO) {
        try {
            system.s3.getObject(
                GetObjectRequest {
                    bucket = system.bucket
                    key = unixPath
                }
            ) {
                val body = it.body!!
                val len = body.contentLength
                if(len == null || len > 100_000 || len < 0) {
                    // copy to file first
                    TypedData(
                        data = Data.Bytes(body.toByteArray()),
                        mediaType = MediaType(it.contentType!!)
                    )
                } else {
                    TypedData(
                        data = Data.Bytes(body.toByteArray()),
                        mediaType = MediaType(it.contentType!!)
                    )
                }
            }
        } catch (e: NoSuchKey) {
            null
        }
    }

    override suspend fun copyTo(other: FileObject) {
        if (other is S3FileObject && other.system.bucket == system.bucket) {
            withContext(Dispatchers.IO) {
                system.s3.copyObject {
                    bucket = system.bucket
                    copySource = system.bucket + "/" + unixPath
                    key = other.unixPath
                }
            }
        } else {
            super.copyTo(other)
        }
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) {
            system.s3.deleteObject {
                bucket = system.bucket
                key = unixPath
            }
        }
    }

    /**
     * Encodes a string for use in a URL path, preserving slashes.
     */
    private fun String.encodeURLPathSafe(): String = URLEncoder.encode(this, Charsets.UTF_8)
        .replace("%2F", "/")
        .replace("+", "%20")

    override val url: String
        get() = "https://${system.bucket}.s3.${system.region}.amazonaws.com/${unixPath.encodeURLPathSafe()}"

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