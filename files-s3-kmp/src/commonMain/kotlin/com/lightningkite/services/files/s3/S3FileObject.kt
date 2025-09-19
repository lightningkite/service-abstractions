package com.lightningkite.services.files.s3

import aws.sdk.kotlin.services.s3.copyObject
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.http.request.header
import aws.smithy.kotlin.runtime.time.epochMilliseconds
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
    
    override fun then(path: String): S3FileObject = S3FileObject(system, Path(this.path, path))
    
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
        get() = system.signedUrlDuration?.let { duration ->
            runBlocking {
                system.s3.presignGetObject(GetObjectRequest {
                    bucket = system.bucket
                    key = unixPath
                }, duration = duration).url.toString()
            }
        } ?: url

    override fun uploadUrl(timeout: Duration): String {
        return runBlocking {
            system.s3.presignPutObject(PutObjectRequest {
                bucket = system.bucket
                key = unixPath
            }, duration = timeout).url.toString()
        }
    }

    override fun toString(): String = url
    override fun equals(other: Any?): Boolean = other is S3FileObject && other.system == system && other.unixPath == unixPath
    override fun hashCode(): Int = 31 * system.hashCode() + unixPath.hashCode()

    internal fun assertSignatureValid(queryParams: String) {
        if (system.signedUrlDuration != null) {
            runBlocking {
                val response = client.get("$url?$queryParams") {
                    header("Range", "0-0")
                }
                if(!response.status.isSuccess()) throw IllegalArgumentException("Could not verify signature")
            }
        }
    }
}