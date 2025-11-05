package com.lightningkite.services.files.s3

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.aws.AwsConnections
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.get
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * An implementation of [PublicFileSystem] that uses AWS S3 for storage.
 *
 * This implementation provides access to files stored in an AWS S3 bucket with support for:
 * - Signed URLs for secure access control
 * - Multiple credential providers (static keys, profiles, default chain)
 * - Optimized URL signing using custom implementation
 * - Connection pooling via [AwsConnections]
 *
 * @property name The service name for logging and identification
 * @property region The AWS region where the bucket is located
 * @property credentialProvider The AWS credentials provider for authentication
 * @property bucket The S3 bucket name
 * @property signedUrlDuration The duration for which signed URLs are valid. If null, URLs are not signed (public bucket).
 * @property context The setting context for accessing shared resources
 */
public class S3PublicFileSystem(
    override val name: String,
    public val region: Region,
    public val credentialProvider: AwsCredentialsProvider,
    public val bucket: String,
    public val signedUrlDuration: Duration? = null,
    override val context: SettingContext
) : PublicFileSystem {

    override val rootUrls: List<String> = listOf(
        "https://${bucket}.s3.${region.id()}.amazonaws.com/",
        "https://s3-${region.id()}.amazonaws.com/${bucket}/",
    )

    private var credsOnHand: AwsCredentials? = null
    private var credsOnHandMs: Long = 0
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
        val token: String? = null
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

    private var lastSigningKey: SecretKeySpec? = null
    private var lastSigningKeyDate: String = ""

    /**
     * Gets a signing key for the given date, caching it for reuse within the same day.
     *
     * The signing key is derived from AWS credentials following AWS Signature Version 4 specification.
     * It is cached per date to avoid expensive key derivation operations on every signature.
     *
     * @param date The date string in YYYYMMDD format
     * @return A [SecretKeySpec] for signing requests
     */
    public fun signingKey(date: String): SecretKeySpec {
        val lastSigningKey = lastSigningKey
        if (lastSigningKey == null || lastSigningKeyDate != date) {
            val secretKey = creds().secret
            val newKey = "AWS4$secretKey".toByteArray()
                .let { date.toByteArray().mac(it) }
                .let { region.id().toByteArray().mac(it) }
                .let { "s3".toByteArray().mac(it) }
                .let { "aws4_request".toByteArray().mac(it) }
                .let { SecretKeySpec(it, "HmacSHA256") }
            this.lastSigningKey = newKey
            lastSigningKeyDate = date
            return newKey
        } else return lastSigningKey
    }

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

    override val root: S3FileObject = S3FileObject(this, File(""))

    override fun parseInternalUrl(url: String): S3FileObject? {
        val matchingPrefix = rootUrls.firstOrNull { prefix -> url.startsWith(prefix) } ?: return null
        val path = url.substringAfter(matchingPrefix).substringBefore('?')
        return S3FileObject(this, File(path))
    }

    override fun parseExternalUrl(url: String): S3FileObject? {
        return parseInternalUrl(url)?.also {
            it.assertSignatureValid(url.substringAfter('?'))
        }
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
    override suspend fun healthCheck(): HealthStatus {
        return try {
            val testFile = root.then("health-check/test-file.txt")
            val testContent = "Test Content ${System.currentTimeMillis()}"

            // Test write
            testFile.put(TypedData(Data.Text(testContent), MediaType.Text.Plain))

            // Test read
            val readContent = testFile.get()
            if (readContent == null) {
                return HealthStatus(
                    level = HealthStatus.Level.ERROR,
                    additionalMessage = "Failed to read test file"
                )
            }

            val readText = readContent.data.text()
            if (readText != testContent) {
                return HealthStatus(
                    level = HealthStatus.Level.ERROR,
                    additionalMessage = "Test content did not match: expected '$testContent', got '$readText'"
                )
            }

            // Test delete
            testFile.delete()

            HealthStatus(level = HealthStatus.Level.OK)
        } catch (e: Exception) {
            HealthStatus(
                level = HealthStatus.Level.ERROR,
                additionalMessage = "Health check failed: ${e.message}"
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
        public fun PublicFileSystem.Settings.Companion.s3(
            user: String,
            password: String,
            region: Region,
            bucket: String,
        ): PublicFileSystem.Settings =
            PublicFileSystem.Settings("s3://$user:$password@$bucket.s3-$region.amazonaws.com")

        /**
         * Creates S3 file system settings using a named AWS profile.
         *
         * @param profile AWS profile name from ~/.aws/credentials
         * @param region AWS region
         * @param bucket S3 bucket name
         * @return Settings URL for S3 file system
         */
        public fun PublicFileSystem.Settings.Companion.s3(
            profile: String,
            region: Region,
            bucket: String,
        ): PublicFileSystem.Settings = PublicFileSystem.Settings("s3://$profile@$bucket.s3-$region.amazonaws.com")

        /**
         * Creates S3 file system settings using default AWS credential chain.
         *
         * This will use environment variables, instance profile, or other default credential sources.
         *
         * @param region AWS region
         * @param bucket S3 bucket name
         * @return Settings URL for S3 file system
         */
        public fun PublicFileSystem.Settings.Companion.s3(
            region: Region,
            bucket: String,
        ): PublicFileSystem.Settings = PublicFileSystem.Settings("s3://$bucket.s3-$region.amazonaws.com")

        init {
            // Registers the "s3" URL scheme with the PublicFileSystem.Settings parser
            // Supports formats:
            // - s3://bucket.region.amazonaws.com/                           (default credentials)
            // - s3://profile@bucket.region.amazonaws.com/                   (named profile)
            // - s3://user:password@bucket.region.amazonaws.com/             (static credentials)
            // Query parameters:
            // - signedUrlDuration: Duration for signed URLs (default: 1h, "forever"/"null" for unsigned)
            PublicFileSystem.Settings.register("s3") { name, url, context ->
                val regex =
                    Regex("""s3:\/\/(?:(?<user>[^:]+):(?<password>[^@]+)@)?(?:(?<profile>[^:]+)@)?(?<bucket>[^.]+)\.(?:s3-)?(?<region>[^.]+)\.amazonaws.com\/?""")
                val match = regex.matchEntire(url.substringBefore('?')) ?: throw IllegalArgumentException(
                    "Invalid S3 URL. The URL should match the pattern: s3:" +
                            "//[user]:[password]@[bucket].[region].amazonaws.com/"
                )

                val user = match.groups["user"]?.value ?: ""
                val password = match.groups["password"]?.value ?: ""
                val profile = match.groups["profile"]?.value ?: ""
                val bucket = match.groups["bucket"]?.value ?: throw IllegalArgumentException("No bucket provided")
                val region = match.groups["region"]?.value ?: throw IllegalArgumentException("No region provided")

                val params = url.substringAfter("?", "").substringBefore("#", "")
                    .takeIf { it.isNotEmpty() }
                    ?.split("&")
                    ?.associate { it.substringBefore("=") to it.substringAfter("=", "") }
                    ?: emptyMap()

                val signedUrlDuration = params["signedUrlDuration"].let {
                    when{
                        it == null -> 1.hours
                        it == "forever" || it == "null" -> null
                        it.all { it.isDigit() } -> it.toLong().seconds
                        else -> Duration.parse(it)
                    }
                }

                S3PublicFileSystem(
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

/*
 * TODO: API Recommendations for S3PublicFileSystem
 *
 * 1. Connection Lifecycle: Consider implementing connect() and disconnect() methods from the Service interface
 *    to properly manage S3 client resources in serverless environments (AWS Lambda, SnapStart).
 *
 * 2. Thread Safety: The credential caching mechanism (credsDirect, credsOnHandMs) and signing key cache
 *    (lastSigningKey, lastSigningKeyDate) are not thread-safe. Consider using @Volatile or atomic operations
 *    if this class will be accessed from multiple threads concurrently.
 *
 * 3. Credential Refresh: The credential expiration check uses > instead of >=, which could lead to using
 *    expired credentials for a brief moment. Consider using >= for safer behavior.
 *
 * 4. URL Parsing: The parseInternalUrl method now correctly handles multiple root URL patterns, but consider
 *    adding URL decoding for paths that contain encoded characters.
 *
 * 5. Health Check: Consider making the health check path configurable or using a more unique path to avoid
 *    potential conflicts with user data (e.g., a UUID-based path).
 *
 * 6. Logging: Replace the println statement in the profile credential provider with proper logging using
 *    kotlin-logging for consistency with the rest of the library.
 *
 * 7. Settings Builder: Consider adding a fluent builder API in addition to the URL-based configuration for
 *    improved type safety and IDE support.
 *
 * 8. Multipart Upload: For large files, consider exposing multipart upload capabilities through the API
 *    to improve upload performance and reliability.
 *
 * 9. Bucket Validation: Consider adding an optional bucket existence check during initialization to fail
 *    fast if the bucket doesn't exist or is inaccessible.
 *
 * 10. Metrics/Observability: Consider adding OpenTelemetry spans for S3 operations to track performance
 *     and errors in production environments.
 */