package com.lightningkite.services.files.s3

import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.get
import kotlinx.io.files.Path
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Kotlin Multiplatform implementation of PublicFileSystem using AWS S3.
 *
 * Provides cloud file storage with S3 using the AWS SDK for Kotlin (KMP-compatible).
 * This is the multiplatform version of the S3 file system implementation.
 *
 * ## Features
 *
 * - **Multiplatform support**: Works on JVM, JS, and Native targets
 * - **S3 storage**: Reliable cloud storage with 99.999999999% durability
 * - **Signed URLs**: Optional temporary signed URLs for secure access
 * - **Health checks**: Comprehensive health monitoring with write/read/delete tests
 * - **Multiple credential sources**: Static credentials, profiles, or default chain
 * - **AWS SDK for Kotlin**: Uses modern KMP-compatible AWS SDK
 *
 * ## Supported URL Schemes
 *
 * - `s3://bucket.s3-region.amazonaws.com` - Default credentials
 * - `s3://user:password@bucket.s3-region.amazonaws.com` - Static credentials
 * - `s3://profile@bucket.s3-region.amazonaws.com` - AWS profile
 * - `s3://bucket.s3-region.amazonaws.com?signedUrlDuration=1h` - With signed URL config
 * - `s3://bucket.s3-region.amazonaws.com?signedUrlDuration=forever` - No signed URLs
 *
 * Format: `s3://[user]:[password]@[bucket].[s3-][region].amazonaws.com[?params]`
 *
 * Query parameters:
 * - `signedUrlDuration`: Duration for signed URLs (e.g., "1h", "30m", "forever", "null")
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Production with IAM role (default credentials)
 * PublicFileSystem.Settings("s3://my-app-files.s3-us-east-1.amazonaws.com")
 *
 * // Development with access key
 * PublicFileSystem.Settings("s3://AKIAIOSFODNN7EXAMPLE:wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLE@my-bucket.s3-us-west-2.amazonaws.com")
 *
 * // Using AWS profile
 * PublicFileSystem.Settings("s3://production@my-bucket.s3-eu-west-1.amazonaws.com")
 *
 * // With signed URLs (1 hour expiration)
 * PublicFileSystem.Settings("s3://my-bucket.s3-ap-southeast-1.amazonaws.com?signedUrlDuration=1h")
 *
 * // Public bucket (no signed URLs)
 * PublicFileSystem.Settings("s3://public-assets.s3-us-east-1.amazonaws.com?signedUrlDuration=forever")
 *
 * // Using helper functions
 * PublicFileSystem.Settings.Companion.s3(
 *     user = "AKIAIOSFODNN7EXAMPLE",
 *     password = "secretKey",
 *     region = "us-east-1",
 *     bucket = "my-bucket"
 * )
 *
 * PublicFileSystem.Settings.Companion.s3(
 *     profile = "production",
 *     region = "us-east-1",
 *     bucket = "my-bucket"
 * )
 * ```
 *
 * ## Implementation Notes
 *
 * - **AWS SDK for Kotlin**: Uses multiplatform AWS SDK (not Java SDK)
 * - **Credential providers**: Supports static, profile, and default chain
 * - **Lazy S3 client**: Client initialized on first use
 * - **Multiple root URLs**: Supports both bucket.s3.region and s3-region.amazonaws.com/bucket formats
 * - **Signed URL validation**: Validates signatures on external URLs
 * - **Health check**: Writes/reads/deletes test file in health-check/ folder
 *
 * ## Important Gotchas
 *
 * - **Region required**: Must specify AWS region in URL
 * - **Bucket must exist**: S3 bucket must be created beforehand
 * - **IAM permissions**: Requires s3:GetObject, s3:PutObject, s3:DeleteObject permissions
 * - **CORS for web**: Web apps need CORS configuration on S3 bucket
 * - **Signed URL security**: Don't use "forever" for sensitive files
 * - **Health check writes**: Creates test file in health-check/ prefix
 * - **Costs**: S3 charges for storage, requests, and data transfer
 * - **Multipart uploads**: Large files use multipart upload (handled automatically)
 *
 * ## Comparison with files-s3 (JVM-only)
 *
 * - **SDK**: This uses AWS SDK for Kotlin (KMP), files-s3 uses AWS SDK for Java v2
 * - **Platforms**: This supports all KMP targets, files-s3 is JVM-only
 * - **Signature**: This uses AWS Signature V4 signing, files-s3 has custom implementation
 * - **Features**: Both have similar feature parity for S3 operations
 *
 * ## S3 Bucket Setup
 *
 * 1. Create S3 bucket in AWS Console or via AWS CLI
 * 2. Configure bucket policy for required permissions
 * 3. (Optional) Enable CORS for web access
 * 4. (Optional) Configure lifecycle rules for old files
 * 5. Set up IAM role or access keys with appropriate permissions
 *
 * @property name Service name for logging/metrics
 * @property region AWS region (e.g., "us-east-1")
 * @property credentialProvider AWS credentials provider
 * @property bucket S3 bucket name
 * @property signedUrlDuration How long signed URLs remain valid (null = no signing)
 * @property context Service context
 */
public class S3PublicFileSystem(
    override val name: String,
    public val region: String,
    public val credentialProvider: CredentialsProvider,
    public val bucket: String,
    public val signedUrlDuration: Duration? = null,
    override val context: SettingContext
) : PublicFileSystem {

    internal val tracer: io.opentelemetry.api.trace.Tracer? = context.openTelemetry?.getTracer("files-s3-kmp")

    override val rootUrls: List<String> = listOf(
        "https://${bucket}.s3.${region}.amazonaws.com/",
        "https://s3-${region}.amazonaws.com/${bucket}/",
    )

    /**
     * The S3 client.
     */
    public val s3: S3Client by lazy {
        S3Client {
            this.region = this@S3PublicFileSystem.region
            this.credentialsProvider
        }
    }

    override val root: S3FileObject = S3FileObject(this, Path(""))

    override fun parseInternalUrl(url: String): S3FileObject? {
        if (rootUrls.none { prefix -> url.startsWith(prefix) }) return null
        val path = url.substringAfter(rootUrls.first()).substringBefore('?')
        return S3FileObject(this, Path(path))
    }

    override fun parseExternalUrl(url: String): S3FileObject? {
        return parseInternalUrl(url)?.also {
            it.assertSignatureValid(url.substringAfter('?'))
        }
    }

    /**
     * Checks the health of the S3 connection by performing a test write and read.
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
        public fun PublicFileSystem.Settings.Companion.s3(
            user: String,
            password: String,
            region: String,
            bucket: String,
        ): PublicFileSystem.Settings =
            PublicFileSystem.Settings("s3://$user:$password@$bucket.s3-$region.amazonaws.com")

        public fun PublicFileSystem.Settings.Companion.s3(
            profile: String,
            region: String,
            bucket: String,
        ): PublicFileSystem.Settings = PublicFileSystem.Settings("s3://$profile@$bucket.s3-$region.amazonaws.com")

        public fun PublicFileSystem.Settings.Companion.s3(
            region: String,
            bucket: String,
        ): PublicFileSystem.Settings = PublicFileSystem.Settings("s3://$bucket.s3-$region.amazonaws.com")

        init {
            PublicFileSystem.Settings.register("s3") { name, url, context ->
                val regex =
                    Regex("""s3://(?:(?<user>[^:]+):(?<password>[^@]+)@)?(?:(?<profile>[^:]+)@)?(?<bucket>[^.]+)\.(?:s3-)?(?<region>[^.]+)\.amazonaws.com/?""")
                val match = regex.matchEntire(url) ?: throw IllegalArgumentException(
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
                    region = region,
                    credentialProvider = when {
                        user.isNotBlank() && password.isNotBlank() -> StaticCredentialsProvider(
                            Credentials(
                                user,
                                password
                            )
                        )
                        profile.isNotBlank() -> {
                            DefaultChainCredentialsProvider(profileName = profile)
                        }
                        else -> DefaultChainCredentialsProvider()
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