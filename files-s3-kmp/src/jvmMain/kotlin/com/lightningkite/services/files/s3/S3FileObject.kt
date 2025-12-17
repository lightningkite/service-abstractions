package com.lightningkite.services.files.s3

import aws.sdk.kotlin.services.s3.*
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import com.lightningkite.MediaType
import com.lightningkite.services.data.Data
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.io.files.Path
import java.net.URLEncoder
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * An implementation of [FileObject] that uses AWS S3 for storage.
 */
public class S3FileObject(
    public val system: S3PublicFileSystem,
    public val path: Path,
) : FileObject {

    /**
     * The Unix-style path for this file.
     */
    private val unixPath: String get() = path.toString().replace('\\', '/')

    override fun then(path: String): S3FileObject = S3FileObject(
        system,
        Path(
            this.path,
            path.decodeURLPart()
                .also { if (it.contains("+")) throw IllegalArgumentException("File Path cannot contain '+'") }
        )
    )

    override val name: String get() = path.name

    override val parent: FileObject?
        get() = path.parent?.let { S3FileObject(system, it) } ?: if (unixPath.isNotEmpty()) system.root else null

    override suspend fun list(): List<FileObject>? {
        val span = system.tracer?.spanBuilder("s3.list")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("file.operation", "list")
            ?.setAttribute("file.path", TelemetrySanitization.sanitizeFilePathWithDepth(unixPath))
            ?.setAttribute("file.bucket", system.bucket)
            ?.setAttribute("storage.system", "s3")
            ?.startSpan()

        return try {
            val scope = span?.makeCurrent()
            try {
                withContext(Dispatchers.IO) {
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
                    span?.setAttribute("file.count", results.size.toLong())
                    span?.setStatus(StatusCode.OK)
                    results
                }
            } finally {
                scope?.close()
            }
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to list files: ${e.message}")
            span?.recordException(e)
            null
        } finally {
            span?.end()
        }
    }

    override suspend fun head(): FileInfo? {
        val span = system.tracer?.spanBuilder("s3.head")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("file.operation", "head")
            ?.setAttribute("file.path", TelemetrySanitization.sanitizeFilePathWithDepth(unixPath))
            ?.setAttribute("file.bucket", system.bucket)
            ?.setAttribute("storage.system", "s3")
            ?.startSpan()

        return try {
            val scope = span?.makeCurrent()
            try {
                withContext(Dispatchers.IO) {
                    system.s3.headObject {
                        bucket = system.bucket
                        key = unixPath
                    }.let {
                        span?.setAttribute("file.size", it.contentLength!!)
                        span?.setAttribute("file.content_type", it.contentType!!)
                        span?.setStatus(StatusCode.OK)
                        FileInfo(
                            type = MediaType(it.contentType!!),
                            size = it.contentLength!!,
                            lastModified = Instant.fromEpochMilliseconds(it.lastModified!!.epochMilliseconds)
                        )
                    }
                }
            } finally {
                scope?.close()
            }
        } catch (e: NoSuchKey) {
            span?.setStatus(StatusCode.OK) // Not finding a file is not an error
            null
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to get file metadata: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun put(content: TypedData) {
        val span = system.tracer?.spanBuilder("s3.put")
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
                    system.s3.putObject {
                        bucket = system.bucket
                        key = unixPath
                        contentType = content.mediaType.toString()
                        this.body = ByteStream.fromBytes(content.data.bytes())
                    }
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

    override suspend fun get(): TypedData? {
        val span = system.tracer?.spanBuilder("s3.get")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("file.operation", "get")
            ?.setAttribute("file.path", TelemetrySanitization.sanitizeFilePathWithDepth(unixPath))
            ?.setAttribute("file.bucket", system.bucket)
            ?.setAttribute("storage.system", "s3")
            ?.startSpan()

        return try {
            val scope = span?.makeCurrent()
            try {
                withContext(Dispatchers.IO) {
                    system.s3.getObject(
                        GetObjectRequest {
                            bucket = system.bucket
                            key = unixPath
                        }
                    ) {
                        val body = it.body!!
                        val len = body.contentLength
                        span?.setAttribute("file.size", len ?: -1L)
                        span?.setAttribute("file.content_type", it.contentType!!)

                        if (len == null || len > 100_000 || len < 0) {
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
                        }.also {
                            span?.setStatus(StatusCode.OK)
                        }
                    }
                }
            } finally {
                scope?.close()
            }
        } catch (e: NoSuchKey) {
            span?.setStatus(StatusCode.OK) // Not finding a file is not an error
            null
        } catch (e: Exception) {
            span?.setStatus(StatusCode.ERROR, "Failed to download file: ${e.message}")
            span?.recordException(e)
            throw e
        } finally {
            span?.end()
        }
    }

    override suspend fun copyTo(other: FileObject) {
        val span = system.tracer?.spanBuilder("s3.copy")
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setAttribute("file.operation", "copy")
            ?.setAttribute("file.path", TelemetrySanitization.sanitizeFilePathWithDepth(unixPath))
            ?.setAttribute("file.bucket", system.bucket)
            ?.setAttribute("storage.system", "s3")
            ?.also { builder ->
                if (other is S3FileObject) {
                    builder.setAttribute(
                        "file.destination.path",
                        TelemetrySanitization.sanitizeFilePathWithDepth(other.unixPath)
                    )
                    builder.setAttribute("file.destination.bucket", other.system.bucket)
                    builder.setAttribute("file.same_bucket", other.system.bucket == system.bucket)
                }
            }
            ?.startSpan()

        try {
            val scope = span?.makeCurrent()
            try {
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

    override suspend fun delete() {
        val span = system.tracer?.spanBuilder("s3.delete")
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
                    system.s3.deleteObject {
                        bucket = system.bucket
                        key = unixPath
                    }
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

    override val url: String
        get() = "https://${system.bucket}.s3.${system.region}.amazonaws.com/${unixPath}"

    private val encodedUrl: String
        get() = "https://${system.bucket}.s3.${system.region}.amazonaws.com/${unixPath.aggressiveEncodeURLPath()}"

    override val signedUrl: String
        get() = system.signedUrlDuration?.let { duration ->
            runBlocking {
                system.s3.presignGetObject(GetObjectRequest {
                    bucket = system.bucket
                    key = unixPath
                }, duration = duration).url.toString()
            }
        } ?: encodedUrl

    override fun uploadUrl(timeout: Duration): String {
        return runBlocking {
            system.s3.presignPutObject(PutObjectRequest {
                bucket = system.bucket
                key = unixPath
            }, duration = timeout).url.toString()
        }
    }

    override fun toString(): String = url
    override fun equals(other: Any?): Boolean =
        other is S3FileObject && other.system == system && other.unixPath == unixPath

    override fun hashCode(): Int = 31 * system.hashCode() + unixPath.hashCode()

    internal fun assertSignatureValid(queryParams: String) {
        if (system.signedUrlDuration != null) {
            runBlocking {
                val response = client.get("$url?$queryParams") {
                    header("Range", "0-0")
                }
                if (!response.status.isSuccess()) throw IllegalArgumentException("Could not verify signature")
            }
        }
    }
}