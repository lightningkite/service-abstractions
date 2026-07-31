package com.lightningkite.services.files.s3

import com.lightningkite.services.SettingContext
import com.lightningkite.services.aws.AwsConnections
import com.lightningkite.services.data.*
import com.lightningkite.services.data.DataSize.Companion.bytes
import com.lightningkite.services.files.ExternalFile
import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.files.ExternalPath
import com.lightningkite.services.files.ExternalServerFileSerializer
import com.lightningkite.services.files.FileInfo
import com.lightningkite.services.get
import com.lightningkite.services.http.client
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.enrich
import com.lightningkite.services.telemetry.telemetryAttributesOf
import com.lightningkite.services.telemetry.telemetryTrace
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.charsets.encode
import io.ktor.utils.io.core.canRead
import io.ktor.utils.io.core.takeWhile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import kotlinx.io.*
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URLDecoder
import java.time.ZoneOffset
import javax.crypto.spec.SecretKeySpec
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid

/**
 * An implementation of [ExternalFileSystem] that uses AWS S3 for storage.
 *
 * This implementation provides access to files stored in an AWS S3 bucket with support for:
 * - Signed URLs for secure access control
 * - Multiple credential providers (static keys, profiles, default chain)
 * - Optimized URL signing using custom implementation
 * - Server-side copies within the same bucket
 * - Connection pooling via [AwsConnections]
 *
 * @property name The service name for logging and identification
 * @property region The AWS region where the bucket is located
 * @property credentialProvider The AWS credentials provider for authentication
 * @property bucket The S3 bucket name
 * @property signedUrlDuration The duration for which signed URLs are valid. If null, URLs are not signed (public bucket).
 * @property context The setting context for accessing shared resources
 */
public class S3ExternalFileSystem(
    override val name: String,
    public val region: Region,
    public val credentialProvider: AwsCredentialsProvider,
    public val bucket: String,
    public val signedUrlDuration: Duration? = null,
    override val context: SettingContext,
) : ExternalFileSystem {

    @Volatile
    private var credsOnHand: AwsCredentials? = null
    @Volatile
    private var credsOnHandMs: Long = 0
    @Volatile
    private var credsDirect: DirectAwsCredentials? = null

    /**
     * Direct AWS credentials with pre-encoded session token for efficient URL generation.
     *
     * @property access The AWS access key ID
     * @property secret The AWS secret access key
     * @property token The optional session token for temporary credentials
     * @property tokenPreEncoded The pre-encoded session token for use in URLs
     */
    public data class DirectAwsCredentials(
        val access: String,
        val secret: String,
        val token: String? = null,
    ) {
        public val tokenPreEncoded: String? = token?.let { java.net.URLEncoder.encode(it, Charsets.UTF_8) }
    }

    /**
     * Gets the current AWS credentials, caching them until expiration.
     *
     * This method caches credentials to avoid repeated calls to the credential provider.
     * Credentials are refreshed when they expire or when the cache is empty.
     *
     * @return The current [DirectAwsCredentials]
     */
    public fun creds(): DirectAwsCredentials {
        val onHand = credsDirect
        return if (onHand == null || System.currentTimeMillis() > credsOnHandMs) {
            val x = credentialProvider.resolveCredentials()
            credsOnHand = x
            val y = DirectAwsCredentials(
                access = x.accessKeyId(),
                secret = x.secretAccessKey(),
                token = (x as? AwsSessionCredentials)?.sessionToken(),
            )
            credsDirect = y
            credsOnHandMs =
                x.expirationTime().getOrNull()?.toEpochMilli() ?: (System.currentTimeMillis() + 24L * 60 * 60 * 1000)
            y
        } else onHand
    }

    @Volatile
    private var lastSigningKey: SecretKeySpec? = null
    @Volatile
    private var lastSigningKeyDate: String = ""
    @Volatile
    private var lastSigningKeyAccessKey: String = ""

    /**
     * Gets a signing key for the given date and credentials, caching it for reuse.
     *
     * The signing key is derived from AWS credentials following AWS Signature Version 4 specification.
     * It is cached per (date, access key) pair so that it is correctly invalidated when IAM
     * credentials rotate (as happens on EC2 instance profiles approximately every hour).
     *
     * @param date The date string in YYYYMMDD format
     * @param currentCreds The current credentials, used both for key derivation and cache invalidation
     * @return A [SecretKeySpec] for signing requests
     */
    public fun signingKey(date: String, currentCreds: DirectAwsCredentials): SecretKeySpec {
        val lastSigningKey = lastSigningKey
        if (lastSigningKey == null || lastSigningKeyDate != date || lastSigningKeyAccessKey != currentCreds.access) {
            val newKey = "AWS4${currentCreds.secret}".toByteArray()
                .let { date.toByteArray().mac(it) }
                .let { region.id().toByteArray().mac(it) }
                .let { "s3".toByteArray().mac(it) }
                .let { "aws4_request".toByteArray().mac(it) }
                .let { SecretKeySpec(it, "HmacSHA256") }
            this.lastSigningKey = newKey
            lastSigningKeyDate = date
            lastSigningKeyAccessKey = currentCreds.access
            return newKey
        } else return lastSigningKey
    }

    /**
     * Total operation budget for S3 API calls. Large-object transfers (uploads/downloads) are
     * legitimately long, so this is generous. The connection and per-attempt timeouts supplied by
     * [AwsConnections] stay short, so an unreachable S3 endpoint still fails fast despite this
     * long total budget.
     */
    private val s3ApiCallTimeout: Duration = 1.hours

    /**
     * The synchronous S3 client for blocking operations.
     *
     * This client uses the HTTP client from [AwsConnections] for connection pooling.
     * It is lazily initialized on first access.
     */
    public val s3: S3Client by lazy {
        S3Client.builder()
            .region(region)
            .credentialsProvider(credentialProvider)
            .httpClient(context[AwsConnections].client)
            .overrideConfiguration(context[AwsConnections].buildOverrideConfiguration(s3ApiCallTimeout))
            .build()
    }

    /**
     * The asynchronous S3 client for non-blocking operations.
     *
     * This client uses the async HTTP client from [AwsConnections] for connection pooling.
     * It is lazily initialized on first access.
     */
    public val s3Async: S3AsyncClient by lazy {
        S3AsyncClient.builder()
            .region(region)
            .credentialsProvider(credentialProvider)
            .httpClient(context[AwsConnections].asyncClient)
            .overrideConfiguration(context[AwsConnections].buildOverrideConfiguration(s3ApiCallTimeout))
            .build()
    }

    /**
     * The S3 presigner for creating signed URLs using AWS SDK.
     *
     * This is used as a fallback or for comparison with the custom signing implementation.
     * It is lazily initialized on first access.
     */
    public val signer: S3Presigner by lazy {
        S3Presigner.builder()
            .region(region)
            .credentialsProvider(credentialProvider)
            .build()
    }

    private fun unixPathOf(path: ExternalPath): String = path.parts.joinToString("/")

    private fun pathFromUnix(unixPath: String): ExternalPath =
        ExternalPath(unixPath.split("/").filter { it.isNotEmpty() })

    private fun s3SpanAttrs(operation: String, unixPath: String): TelemetryAttributes = TelemetryAttributes {
        put(TelemetryKey.OfString("file.operation"), operation)
        put(TelemetryKeys.Aws.s3Key, context.telemetrySanitization.sanitizeFilePathWithDepth(unixPath))
        put(TelemetryKeys.Aws.s3Bucket, bucket)
        put(TelemetryKeys.Rpc.system, "aws.s3")
    }

    /**
     * Lists all direct children of the directory at [path].
     *
     * This method uses pagination to handle directories with many files efficiently.
     * It filters results to only include direct children (not nested subdirectories).
     *
     * S3 has no concept of directories - "directory" is just a naming convention on object keys.
     * `listObjectsV2` returns an empty result set for a non-existent prefix rather than throwing,
     * so this method never returns null in practice. Use [head] to distinguish "exists and empty"
     * from "doesn't exist" if that distinction matters to the caller. Operational failures
     * (NoSuchBucket, AccessDenied, throttling, network) propagate so the surrounding telemetry
     * span records them as errors.
     */
    override suspend fun flow(path: ExternalPath): Flow<ExternalPath> {
        val unixPath = unixPathOf(path)
        return kotlinx.coroutines.flow.flow {
            var token: String? = null
            while (true) {
                val response = telemetryTrace("list", attributes = s3SpanAttrs("list", unixPath)) { span ->
                    s3Async.listObjectsV2 {
                        it.bucket(bucket)
                        it.prefix(unixPath)
                        it.delimiter("/")
                        token?.let { t -> it.continuationToken(t) }
                    }.await().also {
                        span.enrich(
                            TelemetryKeys.File.count to it.contents().size.toLong()
                        )
                    }
                }

                response.contents()
                    .asSequence()
                    .filter { !it.key().substringAfter(unixPath).contains('/') }
                    .map { pathFromUnix(it.key()) }
                    .forEach { emit(it) }

                if (response.isTruncated) token = response.nextContinuationToken()
                else break
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Gets metadata about the file at [path] without downloading its contents.
     */
    override suspend fun head(path: ExternalPath): FileInfo? {
        val unixPath = unixPathOf(path)
        return telemetryTrace("head", attributes = s3SpanAttrs("head", unixPath)) { span ->
            val result = withContext(Dispatchers.IO) {
                try {
                    s3Async.headObject {
                        it.bucket(bucket)
                        it.key(unixPath)
                    }.await().let {
                        FileInfo(
                            type = MediaType(it.contentType()),
                            size = it.contentLength().bytes,
                            lastModified = it.lastModified().toKotlinInstant()
                        )
                    }
                } catch (e: NoSuchKeyException) {
                    null
                }
            }
            result?.let {
                span.enrich(
                    TelemetryKeys.File.size to it.size.bytes,
                    TelemetryKeys.File.contentType to it.type.toString()
                )
            }
            result
        }
    }

    /**
     * Uploads content to the file at [path] in S3.
     */
    override suspend fun put(path: ExternalPath, content: TypedData): Unit {
        val unixPath = unixPathOf(path)
        telemetryTrace("put", attributes = TelemetryAttributes {
            putAll(s3SpanAttrs("put", unixPath))
            put(TelemetryKeys.File.size, content.data.size ?: -1L)
            put(TelemetryKeys.File.contentType, content.mediaType.toString())
        }) {
            withContext(Dispatchers.IO) {
                s3.putObject(PutObjectRequest.builder().also {
                    it.bucket(bucket)
                    it.key(unixPath)
                    it.contentType(content.mediaType.toString())
                }.build(), content.data.size?.let { size ->
                    RequestBody.fromInputStream(content.data.source().asInputStream(), size)
                } ?: run {
                    RequestBody.fromBytes(content.data.bytes())
                })
            }
            Unit
        }
    }

    /**
     * Downloads the contents of the file at [path] from S3.
     */
    override suspend fun get(path: ExternalPath): TypedData? {
        val unixPath = unixPathOf(path)
        return telemetryTrace("get", attributes = s3SpanAttrs("get", unixPath)) { span ->
            val result = withContext(Dispatchers.IO) {
                try {
                    val response = s3.getObject(
                        GetObjectRequest.builder().also {
                            it.bucket(bucket)
                            it.key(unixPath)
                        }.build()
                    )

                    val rr = response.response()
                    TypedData.source(
                        source = response.asSource().buffered(),
                        mediaType = MediaType(rr.contentType() ?: "application/octet-stream"),
                        size = rr.contentLength()
                    )
                } catch (e: NoSuchKeyException) {
                    null
                }
            }
            result?.let {
                span.enrich(TelemetryAttributes {
                    put(TelemetryKeys.File.size, it.data.size ?: -1L)
                    put(TelemetryKeys.File.contentType, it.mediaType.toString())
                })
            }
            result
        }
    }

    /**
     * Copies the file at [path] to [other].
     *
     * If the destination is also an S3 file in the same bucket, this performs a server-side copy
     * which is faster and doesn't require downloading/uploading the file contents.
     * Otherwise, it falls back to the default download/re-upload implementation.
     */
    override suspend fun copyTo(path: ExternalPath, other: ExternalFile) {
        val unixPath = unixPathOf(path)
        val otherSystem = other.fileSystem as? S3ExternalFileSystem
        val isServerSideCopy = otherSystem != null && otherSystem.bucket == bucket
        telemetryTrace("copy", attributes = TelemetryAttributes {
            putAll(s3SpanAttrs("copy", unixPath))
            put(
                TelemetryKey.OfString("aws.s3.destination.key"),
                if (otherSystem != null) context.telemetrySanitization.sanitizeFilePathWithDepth(unixPathOf(other.path))
                else context.telemetrySanitization.sanitizeFilePath(other.toString())
            )
            put(TelemetryKey.OfBoolean("file.copy.server_side"), isServerSideCopy)
        }) {
            if (isServerSideCopy) {
                withContext(Dispatchers.IO) {
                    s3Async.copyObject {
                        it.sourceBucket(bucket)
                        it.destinationBucket(bucket)
                        it.sourceKey(unixPath)
                        it.destinationKey(unixPathOf(other.path))
                    }.await()
                }
            } else {
                super.copyTo(path, other)
            }
        }
    }

    /**
     * Deletes the file at [path] from S3.
     *
     * Note: S3 delete operations are eventually consistent and may not be immediately visible.
     */
    override suspend fun delete(path: ExternalPath): Unit {
        val unixPath = unixPathOf(path)
        telemetryTrace("delete", attributes = s3SpanAttrs("delete", unixPath)) {
            withContext(Dispatchers.IO) {
                s3Async.deleteObject {
                    it.bucket(bucket)
                    it.key(unixPath)
                }.await()
            }
            Unit
        }
    }

    /**
     * The unsigned URL for the file at [path].
     * This URL will only work if the bucket has public read access configured.
     */
    private fun url(path: ExternalPath): String =
        "https://${bucket}.s3.${region.id()}.amazonaws.com/${unixPathOf(path)}"

    private fun encodedUrl(unixPath: String): String =
        "https://${bucket}.s3.${region.id()}.amazonaws.com/${unixPath.aggressiveEncodeURLPath()}"

    /**
     * A signed URL for secure, time-limited access to the file at [path].
     *
     * This implementation uses a custom AWS Signature V4 signing process that is significantly
     * faster than the AWS SDK's built-in presigner.
     *
     * @param timeout How long the URL should remain valid. Defaults to [signedUrlDuration]; if
     * that is also unset, the unsigned [url] is returned instead.
     */
    override fun signUrl(path: ExternalPath, timeout: Duration?): String {
        val unixPath = unixPathOf(path)
        val duration = timeout ?: signedUrlDuration ?: return encodedUrl(unixPath)

        val creds = creds()
        val accessKey = creds.access
        val tokenPreEncoded = creds.tokenPreEncoded
        var dateOnly: String
        val date = java.time.ZonedDateTime.now(ZoneOffset.UTC).run {
            buildString {
                append(year.toString().padStart(4, '0'))
                append(monthValue.toString().padStart(2, '0'))
                append(dayOfMonth.toString().padStart(2, '0'))
                dateOnly = toString()
                append("T")
                append(hour.toString().padStart(2, '0'))
                append(minute.toString().padStart(2, '0'))
                append(second.toString().padStart(2, '0'))
                append("Z")
            }
        }
        val regionId = region.id()
        val preHeaders = tokenPreEncoded?.let {
            "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=${accessKey}%2F$dateOnly%2F$regionId%2Fs3%2Faws4_request&X-Amz-Date=$date&X-Amz-Expires=${duration.inWholeSeconds}&X-Amz-Security-Token=${it}&X-Amz-SignedHeaders=host"
        } ?: run {
            "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=${accessKey}%2F$dateOnly%2F$regionId%2Fs3%2Faws4_request&X-Amz-Date=$date&X-Amz-Expires=${duration.inWholeSeconds}&X-Amz-SignedHeaders=host"
        }
        val hashHolder = ByteArray(32)
        val canonicalRequestHasher = java.security.MessageDigest.getInstance("SHA-256")
        canonicalRequestHasher.update(CONSTANT_BYTES_GET)
        canonicalRequestHasher.update(unixPath.removePrefix("/").aggressiveEncodeURLPath().toByteArray())
        canonicalRequestHasher.update(CONSTANT_BYTE_NEWLINE)
        canonicalRequestHasher.update(preHeaders.toByteArray())
        canonicalRequestHasher.update(CONSTANT_BYTES_C)
        canonicalRequestHasher.update(bucket.toByteArray())
        canonicalRequestHasher.update(CONSTANT_BYTES_D)
        canonicalRequestHasher.update(regionId.toByteArray())
        canonicalRequestHasher.update(CONSTANT_BYTES_E)
        canonicalRequestHasher.digest(hashHolder, 0, 32)
        val canonicalRequestHash = hashHolder.toHex()
        val finalHasher = javax.crypto.Mac.getInstance("HmacSHA256")
        finalHasher.init(signingKey(dateOnly, creds))
        finalHasher.update(CONSTANT_BYTES_F)
        finalHasher.update(date.toByteArray())
        finalHasher.update(CONSTANT_BYTE_NEWLINE)
        finalHasher.update(dateOnly.toByteArray())
        finalHasher.update(CONSTANT_BYTE_SLASH)
        finalHasher.update(regionId.toByteArray())
        finalHasher.update(CONSTANT_BYTES_H)
        finalHasher.update(canonicalRequestHash.toByteArray())
        finalHasher.doFinal(hashHolder, 0)
        val regeneratedSig = hashHolder.toHex()
        return "${encodedUrl(unixPath)}?$preHeaders&X-Amz-Signature=$regeneratedSig"
    }

    /**
     * Generates a signed URL for uploading content to the file at [path].
     *
     * This uses the custom signing implementation when [signedUrlDuration] is set,
     * otherwise falls back to the AWS SDK presigner.
     */
    override fun uploadUrl(path: ExternalPath, timeout: Duration): String {
        val unixPath = unixPathOf(path)
        if (signedUrlDuration == null) {
            return signer.presignPutObject {
                it.signatureDuration(timeout.toJavaDuration())
                it.putObjectRequest {
                    it.bucket(bucket)
                    it.key(unixPath)
                }
            }.url().toString()
        }

        val creds = creds()
        val accessKey = creds.access
        val tokenPreEncoded = creds.tokenPreEncoded
        var dateOnly: String
        val date = java.time.ZonedDateTime.now(ZoneOffset.UTC).run {
            buildString {
                append(year.toString().padStart(4, '0'))
                append(monthValue.toString().padStart(2, '0'))
                append(dayOfMonth.toString().padStart(2, '0'))
                dateOnly = toString()
                append("T")
                append(hour.toString().padStart(2, '0'))
                append(minute.toString().padStart(2, '0'))
                append(second.toString().padStart(2, '0'))
                append("Z")
            }
        }
        val regionId = region.id()
        val preHeaders = tokenPreEncoded?.let {
            "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=${accessKey}%2F$dateOnly%2F$regionId%2Fs3%2Faws4_request&X-Amz-Date=$date&X-Amz-Expires=${timeout.inWholeSeconds}&X-Amz-Security-Token=${it}&X-Amz-SignedHeaders=host"
        } ?: run {
            "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=${accessKey}%2F$dateOnly%2F$regionId%2Fs3%2Faws4_request&X-Amz-Date=$date&X-Amz-Expires=${timeout.inWholeSeconds}&X-Amz-SignedHeaders=host"
        }

        // For PUT requests, we need to modify the canonical request
        val hashHolder = ByteArray(32)
        val canonicalRequestHasher = java.security.MessageDigest.getInstance("SHA-256")
        canonicalRequestHasher.update(CONSTANT_BYTES_PUT)
        canonicalRequestHasher.update(unixPath.removePrefix("/").aggressiveEncodeURLPath().toByteArray())
        canonicalRequestHasher.update(CONSTANT_BYTE_NEWLINE)
        canonicalRequestHasher.update(preHeaders.toByteArray())
        canonicalRequestHasher.update(CONSTANT_BYTES_C)
        canonicalRequestHasher.update(bucket.toByteArray())
        canonicalRequestHasher.update(CONSTANT_BYTES_D)
        canonicalRequestHasher.update(regionId.toByteArray())
        canonicalRequestHasher.update(CONSTANT_BYTES_E)
        canonicalRequestHasher.digest(hashHolder, 0, 32)
        val canonicalRequestHash = hashHolder.toHex()
        val finalHasher = javax.crypto.Mac.getInstance("HmacSHA256")
        finalHasher.init(signingKey(dateOnly, creds))
        finalHasher.update(CONSTANT_BYTES_F)
        finalHasher.update(date.toByteArray())
        finalHasher.update(CONSTANT_BYTE_NEWLINE)
        finalHasher.update(dateOnly.toByteArray())
        finalHasher.update(CONSTANT_BYTE_SLASH)
        finalHasher.update(regionId.toByteArray())
        finalHasher.update(CONSTANT_BYTES_H)
        finalHasher.update(canonicalRequestHash.toByteArray())
        finalHasher.doFinal(hashHolder, 0)
        val regeneratedSig = hashHolder.toHex()
        return "${encodedUrl(unixPath)}?$preHeaders&X-Amz-Signature=$regeneratedSig"
    }

    /**
     * Alternative signed URL using AWS SDK's official presigner.
     * Used for performance comparison testing.
     */
    internal fun signedUrlOfficial(path: ExternalPath): String {
        val unixPath = unixPathOf(path)
        return signedUrlDuration?.let { duration ->
            signer.presignGetObject {
                it.signatureDuration(duration.toJavaDuration())
                it.getObjectRequest {
                    it.bucket(bucket)
                    it.key(unixPath)
                }
            }.url().toString()
        } ?: url(path)
    }


    private val rootUrls: List<String> = listOf(
        "https://${bucket}.s3.${region.id()}.amazonaws.com/",
        "https://s3-${region.id()}.amazonaws.com/${bucket}/",
    )
    override fun parseLegacyUrl(url: String): ExternalFile? {
        val matchingPrefix = rootUrls.firstOrNull { prefix -> url.startsWith(prefix) } ?: return null
        // Internal URLs come from url(path), which writes the object key literally (un-encoded).
        // Do NOT percent-decode here, or a stored key that literally contains '%xx' would be
        // decoded to a different object (or throw on an invalid escape). Decoding of signed,
        // encoded URLs happens in parseExternalUrl instead.
        val relative = url.substringAfter(matchingPrefix)
        return ExternalFile(this, pathFromUnix(relative))
    }

    override fun parseExternalUrl(url: String): ExternalFile? {
        // Signed URLs are percent-encoded by signUrl/encodedUrl, so decode the path before matching.
        val decodedPath = url.substringBefore('?').decodeURLPart()
        return parseLegacyUrl(decodedPath)
            ?.also { assertSignatureValid(it.path, url.substringAfter('?')) }
    }

    /**
     * Validates the signature of an external URL's query parameters.
     *
     * Verification is performed purely by recomputing the AWS Signature V4 signature with our own
     * signing key (derived from [credentialProvider]) over the presented URL's own query
     * parameters, then comparing it to the supplied `X-Amz-Signature` in a constant-time manner.
     * This is the same HMAC signing logic used by [signUrl]; no network round-trip is required and
     * none is performed on the default path. A signature we did not produce - whether tampered or
     * simply foreign - is rejected.
     *
     * Only when [ExternalServerFileSerializer.inlineScanOnDeserialize] is enabled (the shared
     * backward-compat flag, disabled by default) do we fall back to the legacy behavior of issuing
     * an HTTP request to S3 to validate the URL when local recomputation does not match.
     *
     * @throws IllegalArgumentException if the signature is invalid
     */
    internal fun assertSignatureValid(
        path: ExternalPath,
        queryParams: String,
        now: java.time.Instant = java.time.Instant.now(),
    ) {
        if (signedUrlDuration == null) return
        val unixPath = unixPathOf(path)

        val presentedSignature: String?
        val recomputedSignature: String?
        val expiresAt: java.time.Instant
        try {
            val headers = queryParams.split('&').associate {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(
                    it.substringAfter('=', ""), Charsets.UTF_8
                )
            }
            val secretKey = credentialProvider.resolveCredentials().secretAccessKey()
            val objectPath = unixPath.aggressiveEncodeURLPath()
            val date =
                headers["X-Amz-Date"] ?: throw IllegalArgumentException("No query parameter 'X-Amz-Date' found.")
            val expiresSeconds = headers["X-Amz-Expires"]?.toLongOrNull()
                ?: throw IllegalArgumentException("No query parameter 'X-Amz-Expires' found.")
            expiresAt = parseAmzDate(date).plusSeconds(expiresSeconds)
            val algorithm = headers["X-Amz-Algorithm"]
                ?: throw IllegalArgumentException("No query parameter 'X-Amz-Algorithm' found.")
            val credential = headers["X-Amz-Credential"]
                ?: throw IllegalArgumentException("No query parameter 'X-Amz-Credential' found.")
            val scope = credential.substringAfter("/")

            val canonicalRequest = """
            GET
            ${"/" + objectPath.removePrefix("/")}
            ${queryParams.substringBefore("&X-Amz-Signature=").split('&').sorted().joinToString("&")}
            host:${bucket}.s3.${region.id()}.amazonaws.com

            host
            UNSIGNED-PAYLOAD
            """.trimIndent()

            val toSignString = """
            $algorithm
            $date
            $scope
            ${canonicalRequest.sha256()}
            """.trimIndent()

            val signingKeyBytes = "AWS4$secretKey".toByteArray().let { date.substringBefore('T').toByteArray().mac(it) }
                .let { region.id().toByteArray().mac(it) }.let { "s3".toByteArray().mac(it) }
                .let { "aws4_request".toByteArray().mac(it) }

            recomputedSignature = toSignString.toByteArray().mac(signingKeyBytes).toHex()
            presentedSignature = headers["X-Amz-Signature"]
        } catch (e: Exception) {
            // Recomputation could not even be set up (malformed/foreign URL). Treat as a verification
            // failure unless the compat flag re-enables the legacy HTTP fallback below.
            verifySignatureOverNetwork(path, queryParams)
            return
        }

        // Constant-time comparison so verification time does not leak how many leading bytes matched.
        if (presentedSignature != null && constantTimeEquals(presentedSignature, recomputedSignature)) {
            // A matching signature only proves authenticity; the URL must also be within its
            // X-Amz-Date + X-Amz-Expires validity window. AWS S3 enforces this server-side when the
            // URL is used against it, but local recomputation must enforce it too - otherwise an
            // expired-but-once-valid URL is accepted here and laundered into a permanent,
            // auto-renewing reference by the serializer. (The local backend and future: path both
            // already check expiry; this keeps S3 consistent.)
            if (now.isAfter(expiresAt)) throw IllegalArgumentException("Signed URL has expired")
            return
        }

        verifySignatureOverNetwork(path, queryParams)
    }

    /**
     * Legacy fallback: validates the signed URL by asking S3 directly. Performs a blocking network
     * round-trip and is only reachable when the backward-compat flag is enabled.
     */
    private fun verifySignatureOverNetwork(path: ExternalPath, queryParams: String) {
        // The shared client applies a 60s engine timeout.
        runBlocking {
            val response = client.get("${url(path)}?$queryParams") {
                header("Range", "0-0")
            }
            if (!response.status.isSuccess()) throw IllegalArgumentException("Could not verify signature")
        }
    }

    /**
     * Length-aware constant-time string comparison, used to compare HMAC signature hex digests
     * without leaking match progress through timing.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.isEqual(aBytes, bBytes)
    }

    /**
     * Parses an AWS `X-Amz-Date` value (e.g. `20260722T101054Z`) into a UTC [java.time.Instant].
     * This is the signing timestamp; adding `X-Amz-Expires` seconds gives the URL's expiry.
     */
    private fun parseAmzDate(amzDate: String): java.time.Instant =
        java.time.LocalDateTime.parse(amzDate, AMZ_DATE_FORMATTER).toInstant(ZoneOffset.UTC)

    /**
     * Checks the health of the S3 connection by performing a test write, read, and delete.
     *
     * This health check validates:
     * - Write permissions to the bucket
     * - Read permissions from the bucket
     * - Delete permissions in the bucket
     * - Content integrity (written data matches read data)
     *
     * @return [HealthStatus] with OK level if all operations succeed, ERROR otherwise
     */
    override suspend fun healthCheck(): HealthStatus = telemetryTrace("healthCheck") {
        val results = mutableListOf<Pair<HealthStatus.Level, String?>>()
        try {
            val testFile = root.then("health-check/test-file-${Uuid.random()}.txt")
            val testContent = "Test Content ${System.currentTimeMillis()}"

            // Test write
            testFile.put(TypedData(Data.Text(testContent), MediaType.Text.Plain))

            // Test read
            val readContent = testFile.get()
            if (readContent == null) {
                return@telemetryTrace HealthStatus(
                    level = HealthStatus.Level.ERROR,
                    additionalMessage = "Failed to read test file"
                )
            }

            val readText = readContent.data.text()
            if (readText != testContent) {
                return@telemetryTrace HealthStatus(
                    level = HealthStatus.Level.ERROR,
                    additionalMessage = "Test content did not match: expected '$testContent', got '$readText'"
                )
            }

            val result = client.get(url(testFile.path))
            if (result.status.isSuccess() && signedUrlDuration != null) {
                results.add(
                    HealthStatus.Level.WARNING to
                            "File Signing is configured, but the test file was retrieved with an unsigned URL. Is the S3 Bucket permissions configured correctly?"
                )
            } else if (!result.status.isSuccess() && signedUrlDuration == null) {
                return@telemetryTrace HealthStatus(
                    level = HealthStatus.Level.ERROR,
                    additionalMessage = "File Signing is null, but the test failed to be retrieved with an unsigned URL. Is the S3 Bucket permissions configured correctly?"
                )
            }

            // Test delete
            testFile.delete()

        } catch (e: Exception) {
            results.add(HealthStatus.Level.ERROR to "Health check failed: ${e.message}")
        }

        results.fold(HealthStatus(HealthStatus.Level.OK)) { acc, item ->
            HealthStatus(
                maxOf(acc.level, item.first),
                additionalMessage = if (acc.additionalMessage != null || item.second != null)
                    (acc.additionalMessage?.let { "$it:" } ?: "") + (item.second ?: "")
                else null
            )
        }
    }

    public companion object {
        /**
         * Creates S3 file system settings using static credentials.
         *
         * @param user AWS access key ID
         * @param password AWS secret access key
         * @param region AWS region
         * @param bucket S3 bucket name
         * @return Settings URL for S3 file system
         */
        public fun ExternalFileSystem.Settings.Companion.s3(
            user: String,
            password: String,
            region: Region,
            bucket: String,
        ): ExternalFileSystem.Settings =
            ExternalFileSystem.Settings("s3://$user:$password@$bucket.s3-$region.amazonaws.com")

        /**
         * Creates S3 file system settings using a named AWS profile.
         *
         * @param profile AWS profile name from ~/.aws/credentials
         * @param region AWS region
         * @param bucket S3 bucket name
         * @return Settings URL for S3 file system
         */
        public fun ExternalFileSystem.Settings.Companion.s3(
            profile: String,
            region: Region,
            bucket: String,
        ): ExternalFileSystem.Settings = ExternalFileSystem.Settings("s3://$profile@$bucket.s3-$region.amazonaws.com")

        /**
         * Creates S3 file system settings using default AWS credential chain.
         *
         * This will use environment variables, instance profile, or other default credential sources.
         *
         * @param region AWS region
         * @param bucket S3 bucket name
         * @return Settings URL for S3 file system
         */
        public fun ExternalFileSystem.Settings.Companion.s3(
            region: Region,
            bucket: String,
        ): ExternalFileSystem.Settings = ExternalFileSystem.Settings("s3://$bucket.s3-$region.amazonaws.com")

        init {
            // Registers the "s3" URL scheme with the PublicFileSystem.Settings parser
            // Supports formats:
            // - s3://bucket.region.amazonaws.com/                           (default credentials)
            // - s3://profile@bucket.region.amazonaws.com/                   (named profile)
            // - s3://user:password@bucket.region.amazonaws.com/             (static credentials)
            // Query parameters:
            // - signedUrlDuration: Duration for signed URLs (default: 1h, "forever"/"null" for unsigned)
            ExternalFileSystem.Settings.register("s3") { name, url, context ->
                val regex =
                    Regex("""s3:\/\/(?:(?<user>[^:]+):(?<password>[^@]+)@)?(?:(?<profile>[^:]+)@)?(?<bucket>[^.]+)\.(?:s3-)?(?<region>[^.]+)\.amazonaws.com\/?(?:\?(?<params>.*))?""")
                val match = regex.matchEntire(url) ?: throw IllegalArgumentException(
                    "Invalid S3 URL. The URL should match one of the patterns:" +
                            "   s3://[user]:[password]@[bucket].[region].amazonaws.com/?[params]," +
                            "   s3://[profile]@[bucket].[region].amazonaws.com/?[params]," +
                            "       Available params are: signedUrlDuration"
                )

                val user = match.groups["user"]?.value ?: ""
                val password = match.groups["password"]?.value ?: ""
                val profile = match.groups["profile"]?.value ?: ""
                val bucket = match.groups["bucket"]?.value ?: throw IllegalArgumentException("No bucket provided")
                val region = match.groups["region"]?.value ?: throw IllegalArgumentException("No region provided")

                val params = match.groups["params"]?.value
                    ?.takeIf { it.isNotBlank() }
                    ?.split("&")
                    ?.filter { it.isNotBlank() }
                    ?.map {
                        it.substringBefore('=') to it.substringAfter('=', "")
                    }
                    ?.groupBy { it.first }
                    ?.mapValues { it.value.map { it.second } }
                    ?: emptyMap()

                val signedUrlDuration = params["signedUrlDuration"].let {
                    val value = it?.firstOrNull()
                    when {
                        value == null -> 1.hours
                        value == "forever" || value == "null" -> null
                        value.all { it.isDigit() } -> value.toLong().seconds
                        else -> Duration.parse(value)
                    }
                }

                S3ExternalFileSystem(
                    name = name,
                    region = Region.of(region),
                    credentialProvider = when {
                        user.isNotBlank() && password.isNotBlank() -> {
                            StaticCredentialsProvider.create(object : AwsCredentials {
                                override fun accessKeyId(): String = user
                                override fun secretAccessKey(): String = password
                            })
                        }

                        profile.isNotBlank() -> {
                            DefaultCredentialsProvider.builder().profileName(profile).build()
                        }

                        else -> DefaultCredentialsProvider.builder().build()
                    },
                    bucket = bucket,
                    signedUrlDuration = signedUrlDuration,
                    context = context
                )
            }
        }
    }
}

private val AMZ_DATE_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

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

/**
 * Percent-encodes a unix-style S3 object key for use in a URL path, leaving path separators
 * and the usual "safe" punctuation untouched.
 */
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

private val CONSTANT_BYTES_GET = "GET\n/".toByteArray()
private val CONSTANT_BYTES_PUT = "PUT\n/".toByteArray()
private val CONSTANT_BYTES_C = "\nhost:".toByteArray()
private val CONSTANT_BYTES_D = ".s3.".toByteArray()
private val CONSTANT_BYTES_E = (".amazonaws.com\n\nhost\nUNSIGNED-PAYLOAD").toByteArray()
private val CONSTANT_BYTES_F = "AWS4-HMAC-SHA256\n".toByteArray()
private val CONSTANT_BYTE_NEWLINE = '\n'.code.toByte()
private val CONSTANT_BYTE_SLASH = '/'.code.toByte()
private val CONSTANT_BYTES_H = "/s3/aws4_request\n".toByteArray()

/**
 * Applies a MAC operation to this byte array using the given key.
 */
internal fun ByteArray.mac(key: ByteArray): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").apply {
    init(SecretKeySpec(key, "HmacSHA256"))
}.doFinal(this)

/**
 * Computes the SHA-256 hash of this string.
 */
internal fun String.sha256(): String = java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()

/**
 * Converts this byte array to a hexadecimal string.
 */
internal fun ByteArray.toHex(): String = buildString {
    for (item in this@toHex) {
        append(item.toUByte().toString(16).padStart(2, '0'))
    }
}
