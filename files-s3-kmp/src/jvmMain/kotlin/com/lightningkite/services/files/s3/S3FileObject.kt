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
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.FileInfo
import com.lightningkite.services.files.FileObject
import com.lightningkite.services.TelemetryAttributes
import com.lightningkite.services.TelemetryKey
import com.lightningkite.services.TelemetryKeys
import com.lightningkite.services.http.client
import com.lightningkite.services.telemetryTrace
import com.lightningkite.services.TelemetrySanitization
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.charsets.encode
import io.ktor.utils.io.core.canRead
import io.ktor.utils.io.core.takeWhile
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
     * Converts Windows-style backslashes to forward slashes for S3 compatibility.
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

    private fun s3SpanAttrs(operation: String): TelemetryAttributes = TelemetryAttributes {
        put(TelemetryKey.OfString("file.operation"), operation)
        put(TelemetryKey.OfString("file.path"), context.telemetrySanitization.sanitizeFilePathWithDepth(unixPath))
        put(TelemetryKey.OfString("file.bucket"), system.bucket)
        put(TelemetryKey.OfString("storage.system"), "s3")
    }

    override suspend fun list(): List<FileObject>? = system.telemetryTrace("list", attributes = s3SpanAttrs("list")) { span ->
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
                span.enrich(TelemetryAttributes { put(TelemetryKey.OfLong("file.count"), results.size.toLong()) })
                results
            }
        } catch (e: Exception) {
            // Swallow exception and return null, but record it on the active span for diagnostics.
            system.context.reportException(e)
            null
        }
    }

    override suspend fun head(): FileInfo? = system.telemetryTrace("head", attributes = s3SpanAttrs("head")) { span ->
        try {
            withContext(Dispatchers.IO) {
                system.s3.headObject {
                    bucket = system.bucket
                    key = unixPath
                }.let {
                    span.enrich(TelemetryAttributes {
                        put(TelemetryKey.OfLong("file.size"), it.contentLength!!)
                        put(TelemetryKey.OfString("file.content_type"), it.contentType!!)
                    })
                    FileInfo(
                        type = MediaType(it.contentType!!),
                        size = it.contentLength!!,
                        lastModified = Instant.fromEpochMilliseconds(it.lastModified!!.epochMilliseconds)
                    )
                }
            }
        } catch (e: NoSuchKey) {
            // Not finding a file is not an error.
            null
        }
    }

    override suspend fun put(content: TypedData): Unit = system.telemetryTrace("put", attributes = TelemetryAttributes {
        putAll(s3SpanAttrs("put"))
        put(TelemetryKey.OfLong("file.size"), content.data.size!!) // TODO: Unknown sizes will die
        put(TelemetryKey.OfString("file.content_type"), content.mediaType.toString())
    }) {
        withContext(Dispatchers.IO) {
            system.s3.putObject {
                bucket = system.bucket
                key = unixPath
                contentType = content.mediaType.toString()
                this.body = ByteStream.fromBytes(content.data.bytes())
            }
        }
        Unit
    }

    override suspend fun get(): TypedData? = system.telemetryTrace("get", attributes = s3SpanAttrs("get")) { span ->
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
                    span.enrich(TelemetryAttributes {
                        put(TelemetryKey.OfLong("file.size"), len ?: -1L)
                        put(TelemetryKey.OfString("file.content_type"), it.contentType!!)
                    })

                    TypedData(
                        data = Data.Bytes(body.toByteArray()),
                        mediaType = MediaType(it.contentType!!)
                    )
                }
            }
        } catch (e: NoSuchKey) {
            // Not finding a file is not an error.
            null
        }
    }

    override suspend fun copyTo(other: FileObject): Unit = system.telemetryTrace("copy", attributes = TelemetryAttributes {
        putAll(s3SpanAttrs("copy"))
        if (other is S3FileObject) {
            put(TelemetryKey.OfString("file.destination.path"), context.telemetrySanitization.sanitizeFilePathWithDepth(other.unixPath))
            put(TelemetryKey.OfString("file.destination.bucket"), other.system.bucket)
            put(TelemetryKey.OfBoolean("file.same_bucket"), other.system.bucket == system.bucket)
        }
    }) {
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

    override suspend fun delete(): Unit = system.telemetryTrace("delete", attributes = s3SpanAttrs("delete")) {
        withContext(Dispatchers.IO) {
            system.s3.deleteObject {
                bucket = system.bucket
                key = unixPath
            }
        }
        Unit
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

    // Cache for signed URL to avoid repeated runBlocking calls.
    // Safety window: refresh 60s before expiry to avoid handing out nearly-expired URLs.
    // TODO: signedUrl should be suspend in interface to avoid runBlocking
    @Volatile private var cachedSignedUrl: String? = null
    @Volatile private var cachedSignedUrlExpiresAt: Long = 0L

    override val signedUrl: String
        get() = system.signedUrlDuration?.let { duration ->
            val safetyWindowMs = 60_000L
            val now = System.currentTimeMillis()
            if (cachedSignedUrl != null && now < cachedSignedUrlExpiresAt - safetyWindowMs) {
                return@let cachedSignedUrl!!
            }
            val url = runBlocking {
                system.s3.presignGetObject(GetObjectRequest {
                    bucket = system.bucket
                    key = unixPath
                }, duration = duration).url.toString()
            }
            cachedSignedUrl = url
            cachedSignedUrlExpiresAt = now + duration.inWholeMilliseconds
            url
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
            // Local verification is not feasible here (the KMP module uses KMP presigner without exposed signing details).
            // Falling back to HTTP verification with the shared client's 60s engine timeout.
            // TODO: make assertSignatureValid suspend to avoid runBlocking.
            runBlocking {
                val response = client.get("$url?$queryParams") {
                    header("Range", "0-0")
                }
                if (!response.status.isSuccess()) throw IllegalArgumentException("Could not verify signature")
            }
        }
    }
}