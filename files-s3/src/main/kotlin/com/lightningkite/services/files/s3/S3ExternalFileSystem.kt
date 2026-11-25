package com.lightningkite.services.files.s3

import com.lightningkite.services.SettingContext
import com.lightningkite.services.aws.AwsConnections
import com.lightningkite.services.data.*
import com.lightningkite.services.data.DataSize.Companion.bytes
import com.lightningkite.services.files.ExternalFile
import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.files.ExternalPath
import com.lightningkite.services.files.FileInfo
import com.lightningkite.services.get
import com.lightningkite.services.kfile.temporary
import com.lightningkite.services.http.client
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.enrich
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
import kotlinx.io.files.SystemFileSystem
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid

// Attributes these operations emit that no OpenTelemetry semantic convention covers.
private val fileOperation: TelemetryKey.OfString = TelemetryKey.OfString("file.operation")
private val awsS3DestinationKey: TelemetryKey.OfString = TelemetryKey.OfString("aws.s3.destination.key")
private val fileCopyServerSide: TelemetryKey.OfBoolean = TelemetryKey.OfBoolean("file.copy.server_side")

/**
 * An implementation of [ExternalFileSystem] that uses AWS S3 for storage.
 *
 * This implementation provides access to files stored in an AWS S3 bucket with support for:
 * - Signed URLs for secure access control
 * - Multiple credential providers (static keys, profiles, default chain)
 * - Optimized URL signing using a custom implementation ([S3PresignedUrlSigner], or [CloudFrontUrlSigner] on a custom domain)
 * - Server-side copies within the same bucket
 * - Connection pooling via [AwsConnections]
 *
 * @property name The service name for logging and identification
 * @property region The AWS region where the bucket is located
 * @property credentialProvider The AWS credentials provider for authentication
 * @property bucket The S3 bucket name
 * @property signedUrlDuration The duration for which signed URLs are valid. If null, URLs are not signed (public bucket).
 * @property context The setting context for accessing shared resources
 * @property urlSigner Decides where files are served from ([S3UrlSigner.publicRoot]), signs download and
 * upload URLs, and verifies the signed URLs clients send back. Defaults to S3 presigning with [region],
 * [bucket] and [credentialProvider]. For a custom domain, a [CloudFrontUrlSigner] wrapping that: URLs
 * handed to clients are then on the domain, upload URLs still point at S3 directly, and URLs on the S3
 * host are still accepted so references issued before the domain was added keep working.
 */
public class S3ExternalFileSystem(
    override val name: String,
    public val region: Region,
    public val credentialProvider: AwsCredentialsProvider,
    public val bucket: String,
    public val signedUrlDuration: Duration? = null,
    override val context: SettingContext,
    public val urlSigner: S3UrlSigner = S3PresignedUrlSigner(region, bucket, credentialProvider),
) : ExternalFileSystem {


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
        put(fileOperation, operation)
        put(TelemetryKeys.Aws.s3Key, context.telemetrySanitization.sanitizeFilePath(unixPath))
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
        // S3's prefix match is a raw byte-prefix match, not a path-boundary match. Without a trailing
        // "/", listing "reports" would also match an unrelated sibling key like "reportsBackup.zip" -
        // and would fail to surface real children of "reports/" at all, since S3's delimiter logic
        // collapses anything after "reports" + "/" into a single CommonPrefix rather than an entry in
        // Contents. Appending "/" for every non-root path fixes both. Root stays "" (listing
        // everything at the top level).
        val prefix = if (unixPath.isEmpty()) "" else "$unixPath/"
        return kotlinx.coroutines.flow.flow {
            var token: String? = null
            while (true) {
                val response = telemetryTrace("list", attributes = s3SpanAttrs("list", unixPath)) { span ->
                    s3Async.listObjectsV2 {
                        it.bucket(bucket)
                        it.prefix(prefix)
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
                    .filter { !it.key().substringAfter(prefix).contains('/') }
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
                            type = MediaType(it.contentType() ?: "application/octet-stream"),
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
                val request = PutObjectRequest.builder().also {
                    it.bucket(bucket)
                    it.key(unixPath)
                    it.contentType(content.mediaType.toString())
                }.build()
                val knownSize = content.data.size
                if (knownSize != null) {
                    s3.putObject(request, RequestBody.fromInputStream(content.data.source().asInputStream(), knownSize))
                } else {
                    // The sync S3 client needs a known content length before it can start a
                    // PutObject/UploadPart, for every size - there's no way around providing one up
                    // front. Spool unknown-size content to a temp file first (a streaming copy, not a
                    // single in-memory buffer) so its length is known without materializing the whole
                    // object as a ByteArray on the heap, then upload from the file and clean it up.
                    val spooled = SystemFileSystem.temporary()
                    try {
                        // Data.write() explicitly does not close the sink it's given (the caller owns
                        // its lifecycle) - KFile.sink() is buffered, so without closing it here the
                        // final, still-buffered segment (up to 8KB) would never reach disk, silently
                        // truncating the spooled file.
                        spooled.sink().use { sink -> content.data.write(sink) }
                        val size = spooled.metadataOrNull()?.size
                            ?: throw IllegalStateException("Failed to spool upload content to $spooled")
                        s3.putObject(request, RequestBody.fromInputStream(spooled.source().asInputStream(), size))
                    } finally {
                        spooled.delete()
                    }
                }
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
     * Downloads a byte window of the file at [path], letting S3 do the slicing so that only the
     * requested bytes cross the network.
     */
    override suspend fun getRange(path: ExternalPath, range: LongRange): TypedData? {
        ExternalFileSystem.requireValidRange(range)
        val unixPath = unixPathOf(path)
        return telemetryTrace("getRange", attributes = s3SpanAttrs("getRange", unixPath)) { span ->
            val result = withContext(Dispatchers.IO) {
                try {
                    val response = s3.getObject(
                        GetObjectRequest.builder().also {
                            it.bucket(bucket)
                            it.key(unixPath)
                            // Inclusive at both ends, which is what an ExternalFileSystem range means too.
                            it.range("bytes=${range.first}-${range.last}")
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
                } catch (e: S3Exception) {
                    // A range starting at or past the end of the object (an empty object included)
                    // comes back as 416 rather than an empty body. The contract calls that an empty
                    // window, so answer with one - and re-head the object, since a 416 response
                    // carries no content type of its own.
                    if (e.statusCode() != 416) throw e
                    head(path)?.let { TypedData.bytes(ByteArray(0), it.type) }
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
                awsS3DestinationKey,
                if (otherSystem != null) context.telemetrySanitization.sanitizeFilePath(unixPathOf(other.path))
                else context.telemetrySanitization.sanitizeFilePath(other.toString())
            )
            put(fileCopyServerSide, isServerSideCopy)
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

    /** The bucket's own S3 endpoint. S3 presigned URLs only work against this host. */
    private val s3Root: String = "https://${bucket}.s3.${region.id()}.amazonaws.com/"

    /** Where clients fetch files from: a custom domain when [urlSigner] has one, otherwise S3 itself. */
    private val publicRoot: String = urlSigner.publicRoot

    /**
     * The unsigned URL for the file at [path], with the key written literally (un-encoded).
     * This URL will only work if the bucket (or the CloudFront distribution in front of it) allows public reads.
     */
    private fun url(path: ExternalPath): String = publicRoot + unixPathOf(path)

    private fun encodedUrl(unixPath: String): String = publicRoot + unixPath.aggressiveEncodeURLPath()

    /**
     * A signed URL for secure, time-limited access to the file at [path], from [urlSigner].
     *
     * @param timeout How long the URL should remain valid. Defaults to [signedUrlDuration]; if
     * that is also unset, the unsigned [url] is returned instead.
     */
    override fun signUrl(path: ExternalPath, timeout: Duration?): String {
        val unixPath = unixPathOf(path)
        val duration = timeout ?: signedUrlDuration ?: return encodedUrl(unixPath)
        return urlSigner.signDownload(unixPath, duration)
    }

    /**
     * Generates a signed URL for uploading content to the file at [path].
     *
     * This uses [urlSigner] when [signedUrlDuration] is set, otherwise falls back to the AWS SDK
     * presigner. Either way it points at S3 directly, even with a custom domain.
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
        return urlSigner.signUpload(unixPath, timeout)
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
        publicRoot,
        s3Root,
        "https://s3-${region.id()}.amazonaws.com/${bucket}/",
    ).distinct()
    override fun parseLegacyUrl(url: String): ExternalFile? {
        val matchingPrefix = rootUrls.firstOrNull { prefix -> url.startsWith(prefix) } ?: return null
        // Internal URLs come from url(path), which writes the object key literally (un-encoded).
        // Do NOT percent-decode here, or a stored key that literally contains '%xx' would be
        // decoded to a different object (or throw on an invalid escape). Decoding of signed,
        // encoded URLs happens in parseExternalUrl instead.
        val relative = url.substringAfter(matchingPrefix)
        return ExternalFile(this, pathFromUnix(relative))
    }

    /** Signed URLs carry a signature this service verifies; unsigned ones are just paths. */
    override val referencesAreUnforgeable: Boolean get() = signedUrlDuration != null

    override fun parseExternalUrl(url: String): ExternalFile? {
        // Signed URLs are percent-encoded by signUrl/encodedUrl, so decode the path before matching.
        val decodedPath = url.substringBefore('?').decodeURLPart()
        return parseLegacyUrl(decodedPath)
            ?.also { assertSignatureValid(it.path, url.substringAfter('?')) }
    }

    /**
     * Validates the signature of an external URL's query parameters with [urlSigner].
     *
     * @throws IllegalArgumentException if the signature is invalid or expired
     */
    internal fun assertSignatureValid(
        path: ExternalPath,
        queryParams: String,
        now: java.time.Instant = java.time.Instant.now(),
    ) {
        if (signedUrlDuration == null) return
        urlSigner.verify(unixPathOf(path), queryParams, now)
    }

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

            val result = client.get(s3Root + unixPathOf(testFile.path))
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
            // - domain: Custom host name served by CloudFront in front of the bucket (e.g. files.example.com)
            // - cloudFrontKeyId: ID of the CloudFront public key in the distribution's trusted key group
            // - cloudFrontPrivateKey: Matching RSA private key - PEM, or base64 of PEM or DER (percent-encoding allowed)
            //   Both CloudFront parameters are required for a private bucket on a custom domain; a public
            //   bucket on one (signedUrlDuration=forever) needs only the domain.
            // - urlSecret: A secret (16+ characters, percent-encoding allowed) shared by every server using the
            //   bucket. S3 presigned download URLs then carry a signature made with it, so any of those servers
            //   can verify them locally rather than asking S3. Without it, the key comes from
            //   context.deriveSecret("files-s3/<bucket>") (Lightning Server derives it from its secretBasis);
            //   if that is null too, URLs from other credentials are verified by asking S3.
            ExternalFileSystem.Settings.register("s3") { name, url, context ->
                val regex =
                    Regex("""s3:\/\/(?:(?<user>[^:]+):(?<password>[^@]+)@)?(?:(?<profile>[^:]+)@)?(?<bucket>[^.]+)\.(?:s3-)?(?<region>[^.]+)\.amazonaws.com\/?(?:\?(?<params>.*))?""")
                val match = regex.matchEntire(url) ?: throw IllegalArgumentException(
                    "Invalid S3 URL. The URL should match one of the patterns:" +
                            "   s3://[user]:[password]@[bucket].[region].amazonaws.com/?[params]," +
                            "   s3://[profile]@[bucket].[region].amazonaws.com/?[params]," +
                            "       Available params are: signedUrlDuration, domain, cloudFrontKeyId, cloudFrontPrivateKey, urlSecret"
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
                        value == "forever" || value == "null" || value == "Infinity" -> null
                        value.all { it.isDigit() } -> value.toLong().seconds
                        else -> Duration.parse(value)
                    }
                }

                val domain = params["domain"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                val cloudFrontKeyId = params["cloudFrontKeyId"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                val cloudFrontPrivateKey = params["cloudFrontPrivateKey"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                require((cloudFrontKeyId == null) == (cloudFrontPrivateKey == null)) {
                    "S3 URL parameters 'cloudFrontKeyId' and 'cloudFrontPrivateKey' must be given together"
                }
                val credentialProvider = when {
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
                }
                val urlSecret = params["urlSecret"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?.decodeURLQueryComponent(plusIsSpace = false)
                    ?.also { require(it.length >= 16) { "S3 URL parameter 'urlSecret' must be at least 16 characters" } }
                // Keyed by bucket, so every setting on the same bucket verifies the others' URLs.
                val serverKey = urlSecret?.encodeToByteArray() ?: context.deriveSecret("files-s3/$bucket")
                val s3Signer = S3PresignedUrlSigner(Region.of(region), bucket, credentialProvider, serverKey)
                val urlSigner = when {
                    domain == null -> {
                        require(cloudFrontKeyId == null) {
                            "S3 URL parameters 'cloudFrontKeyId' and 'cloudFrontPrivateKey' need 'domain' as well"
                        }
                        s3Signer
                    }
                    cloudFrontKeyId == null || cloudFrontPrivateKey == null -> {
                        require(signedUrlDuration == null) {
                            "A private bucket on a custom domain needs CloudFront signing ('cloudFrontKeyId' and " +
                                    "'cloudFrontPrivateKey'), because S3 presigned URLs only work on the S3 host. " +
                                    "For a public bucket, set signedUrlDuration=forever."
                        }
                        CloudFrontUrlSigner(s3 = s3Signer, domain = domain)
                    }
                    else -> {
                        require(signedUrlDuration != null) {
                            "S3 URL parameters 'cloudFrontKeyId' and 'cloudFrontPrivateKey' sign URLs, so they need " +
                                    "a signedUrlDuration; for a public bucket, drop them."
                        }
                        CloudFrontUrlSigner(
                            s3 = s3Signer,
                            domain = domain,
                            keyPairId = cloudFrontKeyId,
                            // Base64 never contains '%', so decoding only undoes percent-encoding; '+' stays '+'.
                            privateKey = CloudFrontUrlSigner.parsePrivateKey(
                                cloudFrontPrivateKey.decodeURLQueryComponent(plusIsSpace = false)
                            ),
                        )
                    }
                }

                S3ExternalFileSystem(
                    name = name,
                    region = Region.of(region),
                    credentialProvider = credentialProvider,
                    bucket = bucket,
                    signedUrlDuration = signedUrlDuration,
                    context = context,
                    urlSigner = urlSigner,
                )
            }
        }
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

/**
 * Percent-encodes a unix-style S3 object key for use in a URL path, leaving path separators
 * and the usual "safe" punctuation untouched.
 */
internal fun String.aggressiveEncodeURLPath(): String = buildString {
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
