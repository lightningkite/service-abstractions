package com.lightningkite.services.files.s3

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.get
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import kotlin.io.path.Path

/**
 * An implementation of [PublicFileSystem] that uses AWS S3 for storage.
 */
public class S3PublicFileSystem(
    override val name: String,
    public val region: String,
    public val credentialProvider: CredentialsProvider,
    public val bucket: String,
    public val signedUrlDuration: Duration? = null,
    override val context: SettingContext
) : PublicFileSystem {

    override val rootUrls: List<String> = listOf(
        "https://${bucket}.s3.${region.id()}.amazonaws.com/",
        "https://s3-${region.id()}.amazonaws.com/${bucket}/",
    )

    private var credsOnHand: Credentials? = null
    private var credsOnHandMs: Long = 0
    private var credsDirect: DirectAwsCredentials? = null

    public data class DirectAwsCredentials(
        val access: String,
        val secret: String,
        val token: String? = null
    ) {
        public val tokenPreEncoded: String? = token?.let { java.net.URLEncoder.encode(it, Charsets.UTF_8) }
    }

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
            region: Region,
            bucket: String,
        ): PublicFileSystem.Settings =
            PublicFileSystem.Settings("s3://$user:$password@$bucket.s3-$region.amazonaws.com")

        public fun PublicFileSystem.Settings.Companion.s3(
            profile: String,
            region: Region,
            bucket: String,
        ): PublicFileSystem.Settings = PublicFileSystem.Settings("s3://$profile@$bucket.s3-$region.amazonaws.com")

        public fun PublicFileSystem.Settings.Companion.s3(
            region: Region,
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
                    region = Region.of(region),
                    credentialProvider = when {
                        user.isNotBlank() && password.isNotBlank() -> {
                            StaticCredentialsProvider.create(object : AwsCredentials {
                                override fun accessKeyId(): String = user
                                override fun secretAccessKey(): String = password
                            })
                        }

                        profile.isNotBlank() -> {
                            println("Using profile name $profile")
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