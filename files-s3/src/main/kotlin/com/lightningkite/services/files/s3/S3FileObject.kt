package com.lightningkite.services.files.s3

import com.lightningkite.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.FileInfo
import com.lightningkite.services.files.FileObject
import com.lightningkite.services.http.client
import com.lightningkite.services.otel.TelemetrySanitization
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.charsets.encode
import io.ktor.utils.io.core.canRead
import io.ktor.utils.io.core.takeWhile
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.io.asInputStream
import kotlinx.io.asSource
import kotlinx.io.buffered
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinInstant

/**
 * An implementation of [FileObject] that uses AWS S3 for storage.
 *
 * This class represents a file or directory in an S3 bucket. It provides:
 * - Efficient signed URL generation using a custom AWS Signature V4 implementation
 * - Optimized copy operations within the same bucket (server-side copy)
 * - Pagination support for listing large directories
 * - Proper handling of URL encoding for paths with special characters
 *
 * @property system The parent [S3PublicFileSystem] managing this file object
 * @property path The file path relative to the bucket root (uses java.io.File for path manipulation)
 */
public class S3FileObject(
    public val system: S3PublicFileSystem,
    public val path: File,
) : FileObject {

    /**
     * The Unix-style path for this file.
     * Converts Windows-style backslashes to forward slashes for S3 compatibility.
     */
    private val unixPath: String get() = path.toString().replace('\\', '/')

    override fun then(path: String): S3FileObject = S3FileObject(
        system,
        this.path.resolve(path.decodeURLPart()
            .also { if (it.contains("+")) throw IllegalArgumentException("File Path cannot contain '+'") })
    )

    override val name: String get() = path.name

    override val parent: FileObject?
        get() = path.parentFile?.let { S3FileObject(system, it) } ?: if (unixPath.isNotEmpty()) system.root else null

    /**
     * Lists all direct children of this directory.
     *
     * This method uses pagination to handle directories with many files efficiently.
     * It filters results to only include direct children (not nested subdirectories).
     *
     * @return A list of [S3FileObject] representing the directory contents, or null if this is not a directory
     */
    override suspend fun list(): List<FileObject>? {
        val span = system.tracer?.spanBuilder("file.list")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("file.operation", "list")
            ?.setAttribute("file.path", TelemetrySanitization.sanitizeFilePathWithDepth(unixPath))
            ?.setAttribute("file.bucket", system.bucket)
            ?.setAttribute("storage.system", "s3")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                val result = withContext(Dispatchers.IO) {
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
                result?.let { span?.setAttribute("file.count", it.size.toLong()) }
                span?.setStatus(StatusCode.OK)
                return result
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to list directory: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    /**
     * Gets metadata about this file without downloading its contents.
     *
     * @return [FileInfo] containing media type, size, and last modified time, or null if the file doesn't exist
     */
    override suspend fun head(): FileInfo? {
        val span = system.tracer?.spanBuilder("file.head")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("file.operation", "head")
            ?.setAttribute("file.path", TelemetrySanitization.sanitizeFilePathWithDepth(unixPath))
            ?.setAttribute("file.bucket", system.bucket)
            ?.setAttribute("storage.system", "s3")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                val result = withContext(Dispatchers.IO) {
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
                result?.let {
                    span?.setAttribute("file.size", it.size)
                    span?.setAttribute("file.content_type", it.type.toString())
                }
                span?.setStatus(StatusCode.OK)
                return result
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to get file metadata: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    /**
     * Uploads content to this file in S3.
     *
     * @param content The typed data to upload, including media type information
     */
    override suspend fun put(content: TypedData) {
        val span = system.tracer?.spanBuilder("file.put")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("file.operation", "put")
            ?.setAttribute("file.path", TelemetrySanitization.sanitizeFilePathWithDepth(unixPath))
            ?.setAttribute("file.bucket", system.bucket)
            ?.setAttribute("file.size", content.data.size)
            ?.setAttribute("file.content_type", content.mediaType.toString())
            ?.setAttribute("storage.system", "s3")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                withContext(Dispatchers.IO) {
                    system.s3.putObject(PutObjectRequest.builder().also {
                        it.bucket(system.bucket)
                        it.key(unixPath)
                        it.contentType(content.mediaType.toString())
                    }.build(), content.data.size.let { size ->
                        RequestBody.fromInputStream(content.data.source().asInputStream(), size)
                    })
                }
                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to upload file: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    /**
     * Downloads the contents of this file from S3.
     *
     * @return [TypedData] containing the file contents and media type, or null if the file doesn't exist
     */
    override suspend fun get(): TypedData? {
        val span = system.tracer?.spanBuilder("file.get")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("file.operation", "get")
            ?.setAttribute("file.path", TelemetrySanitization.sanitizeFilePathWithDepth(unixPath))
            ?.setAttribute("file.bucket", system.bucket)
            ?.setAttribute("storage.system", "s3")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val response = system.s3.getObject(
                            GetObjectRequest.builder().also {
                                it.bucket(system.bucket)
                                it.key(unixPath)
                            }.build()
                        )

                        val contentType = response.response().contentType() ?: "application/octet-stream"
                        val mediaType = MediaType(contentType)
                        TypedData.source(response.asSource().buffered(), mediaType = mediaType)
                    } catch (e: NoSuchKeyException) {
                        null
                    }
                }
                result?.let {
                    span?.setAttribute("file.size", it.data.size)
                    span?.setAttribute("file.content_type", it.mediaType.toString())
                }
                span?.setStatus(StatusCode.OK)
                return result
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to download file: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    /**
     * Copies this file to another location.
     *
     * If the destination is also an S3 file in the same bucket, this performs a server-side copy
     * which is faster and doesn't require downloading/uploading the file contents.
     * Otherwise, it falls back to downloading and re-uploading the file.
     *
     * @param other The destination file object
     */
    override suspend fun copyTo(other: FileObject) {
        val isServerSideCopy = other is S3FileObject && other.system.bucket == system.bucket
        val span = system.tracer?.spanBuilder("file.copy")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("file.operation", "copy")
            ?.setAttribute("file.source.path", TelemetrySanitization.sanitizeFilePathWithDepth(unixPath))
            ?.setAttribute("file.source.bucket", system.bucket)
            ?.setAttribute("file.destination.path", if (other is S3FileObject) TelemetrySanitization.sanitizeFilePathWithDepth(other.unixPath) else TelemetrySanitization.sanitizeFilePath(other.toString()))
            ?.setAttribute("file.copy.server_side", isServerSideCopy)
            ?.setAttribute("storage.system", "s3")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                if (isServerSideCopy) {
                    withContext(Dispatchers.IO) {
                        system.s3Async.copyObject {
                            it.sourceBucket(system.bucket)
                            it.destinationBucket(system.bucket)
                            it.sourceKey(unixPath)
                            it.destinationKey((other as S3FileObject).unixPath)
                        }.await()
                    }
                } else {
                    super.copyTo(other)
                }
                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to copy file: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    /**
     * Deletes this file from S3.
     *
     * Note: S3 delete operations are eventually consistent and may not be immediately visible.
     */
    override suspend fun delete() {
        val span = system.tracer?.spanBuilder("file.delete")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("file.operation", "delete")
            ?.setAttribute("file.path", TelemetrySanitization.sanitizeFilePathWithDepth(unixPath))
            ?.setAttribute("file.bucket", system.bucket)
            ?.setAttribute("storage.system", "s3")
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
                withContext(Dispatchers.IO) {
                    system.s3Async.deleteObject {
                        it.bucket(system.bucket)
                        it.key(unixPath)
                    }.await()
                }
                span?.setStatus(StatusCode.OK)
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to delete file: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }


    private val URL_ALPHABET_CHARS = ((('a'..'z') + ('A'..'Z') + ('0'..'9'))).toSet()
    private val VALID_PATH_PART = setOf('-', '.', '_', '/')

    private fun Source.forEach(block: (Byte) -> Unit) {
        takeWhile { buffer ->
            while (buffer.canRead()) {
                block(buffer.readByte())
            }
            true
        }
    }

    private fun hexDigitToChar(digit: Int): Char = when (digit) {
        in 0..9 -> '0' + digit
        else -> 'A' + digit - 10
    }

    private fun Byte.percentEncode(): String {
        val code = toInt() and 0xff
        val array = CharArray(3)
        array[0] = '%'
        array[1] = hexDigitToChar(code shr 4)
        array[2] = hexDigitToChar(code and 0xf)
        return array.concatToString()
    }

    private fun String.aggressiveEncodeURLPath(): String = buildString {
        val charset = io.ktor.utils.io.charsets.Charsets.UTF_8

        var index = 0
        while (index < this@aggressiveEncodeURLPath.length) {
            val current = this@aggressiveEncodeURLPath[index]
            if (current in URL_ALPHABET_CHARS || current in VALID_PATH_PART) {
                append(current)
                index++
                continue
            }

            val symbolSize = if (current.isSurrogate()) 2 else 1
            // we need to call newEncoder() for every symbol, otherwise it won't work
            charset.newEncoder().encode(this@aggressiveEncodeURLPath, index, index + symbolSize).forEach {
                append(it.percentEncode())
            }
            index += symbolSize
        }
    }

    /**
     * The unsigned URL for this file.
     * This URL will only work if the bucket has public read access configured.
     */
    override val url: String
        get() = "https://${system.bucket}.s3.${system.region.id()}.amazonaws.com/${unixPath}"


    private val encodedUrl: String
        get() = "https://${system.bucket}.s3.${system.region.id()}.amazonaws.com/${unixPath.aggressiveEncodeURLPath()}"

    /**
     * A signed URL for secure, time-limited access to this file.
     *
     * This implementation uses a custom AWS Signature V4 signing process that is significantly
     * faster than the AWS SDK's built-in presigner. The URL includes authentication credentials
     * and is valid for the duration specified in [S3PublicFileSystem.signedUrlDuration].
     *
     * If [S3PublicFileSystem.signedUrlDuration] is null, returns the unsigned [url] instead.
     *
     * @return A signed URL valid for GET requests
     */
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
            canonicalRequestHasher.update(objectPath.removePrefix("/").aggressiveEncodeURLPath().toByteArray())
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
            val result = "${encodedUrl}?$preHeaders&X-Amz-Signature=$regeneratedSig"
            result
        } ?: encodedUrl

    /**
     * Generates a signed URL for uploading content to this file.
     *
     * This uses the custom signing implementation when [S3PublicFileSystem.signedUrlDuration] is set,
     * otherwise falls back to the AWS SDK presigner.
     *
     * @param timeout The duration for which the upload URL will be valid
     * @return A signed URL valid for PUT requests
     */
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
            canonicalRequestHasher.update(objectPath.removePrefix("/").aggressiveEncodeURLPath().toByteArray())
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
            val result = "${encodedUrl}?$preHeaders&X-Amz-Signature=$regeneratedSig"
            result
        } ?: system.signer.presignPutObject {
            it.signatureDuration(timeout.toJavaDuration())
            it.putObjectRequest {
                it.bucket(system.bucket)
                it.key(unixPath)
            }
        }.url().toString()


    /**
     * Alternative signed URL using AWS SDK's official presigner.
     * Used for performance comparison testing.
     */
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

    /**
     * Alternative upload URL using AWS SDK's official presigner.
     * Used for performance comparison testing.
     */
    internal fun uploadUrlOfficial(timeout: Duration): String =
        system.signer.presignPutObject {
            it.signatureDuration(timeout.toJavaDuration())
            it.putObjectRequest {
                it.bucket(system.bucket)
                it.key(unixPath)
            }
        }.url().toString()

    override fun toString(): String = url

    override fun equals(other: Any?): Boolean =
        other is S3FileObject && other.system == system && other.unixPath == unixPath

    override fun hashCode(): Int = 31 * system.hashCode() + unixPath.hashCode()

    /**
     * Validates the signature of an external URL's query parameters.
     *
     * This method attempts to verify the AWS Signature V4 signature locally first.
     * If local verification fails or throws an exception, it falls back to making
     * an HTTP request to S3 to validate the URL.
     *
     * @param queryParams The query parameters portion of the URL (after the '?')
     * @throws IllegalArgumentException if the signature is invalid
     */
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
                val date =
                    headers["X-Amz-Date"] ?: throw IllegalArgumentException("No query parameter 'X-Amz-Date' found.")
                val algorithm = headers["X-Amz-Algorithm"]
                    ?: throw IllegalArgumentException("No query parameter 'X-Amz-Algorithm' found.")
                val credential = headers["X-Amz-Credential"]
                    ?: throw IllegalArgumentException("No query parameter 'X-Amz-Credential' found.")
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
                // Ignore.  It's OK if this fails; it just indicates the signature wasn't one we understand.
                // We can validate it with a call to S3 instead.
            }
            return runBlocking {
                val response = client.get("$url?$queryParams") {
                    header("Range", "0-0")
                }
                if (!response.status.isSuccess()) throw IllegalArgumentException("Could not verify signature")
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
            for (item in this@toHex) {
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
        private fun String.sha256(): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()
    }
}

/*
 * TODO: API Recommendations for S3FileObject
 *
 * 1. Error Handling: The list() method catches all exceptions and returns null. Consider being more
 *    specific about which exceptions indicate "not a directory" vs actual errors that should be propagated.
 *
 * 2. Memory Efficiency: The get() method loads entire file into memory with readAllBytes(). For large files,
 *    consider adding streaming alternatives or documenting the memory implications.
 *
 * 3. Redundant Code: The put() method has an unnecessary size check - both branches do the same thing.
 *    Simplify to: RequestBody.fromBytes(content.data.bytes())
 *
 * 4. Cross-Region Copy: The copyTo() optimization only works within the same bucket. Consider extending
 *    this to support cross-bucket copies within the same region, which is also server-side.
 *
 * 5. Signature Verification: The assertSignatureValid method falls back to making an HTTP request with
 *    runBlocking, which could block the calling thread. Consider making this a suspend function or
 *    documenting the blocking behavior.
 *
 * 6. URL Encoding: The encodeURLPathSafe method may not handle all edge cases correctly. Consider using
 *    a more robust URL encoding library or AWS's URLEncoder for consistency with their SDKs.
 *
 * 7. Expiration Checking: The signedUrl and uploadUrl methods don't check if the credentials have expired
 *    before generating signatures. Consider adding expiration validation.
 *
 * 8. Multipart Uploads: For large files, single-part uploads are inefficient. Consider exposing multipart
 *    upload functionality or automatically using it for files above a threshold (e.g., 5MB).
 *
 * 9. Metadata Support: Consider adding methods to get/set custom metadata on S3 objects, which is a
 *    commonly needed feature.
 *
 * 10. Listing Directories: The list() method doesn't include subdirectories (common prefixes). Consider
 *     adding an option to include subdirectories or returning a richer result type.
 */