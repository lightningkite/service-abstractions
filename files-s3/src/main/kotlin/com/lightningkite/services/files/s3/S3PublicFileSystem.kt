package com.lightningkite.services.files.s3

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.FileObject
import com.lightningkite.services.files.PublicFileSystem
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlin.jvm.JvmInline
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * An implementation of [PublicFileSystem] that uses AWS S3 for storage.
 */
public class S3PublicFileSystem(
    override val name: String,
    public val region: Region,
    public val credentialProvider: AwsCredentialsProvider,
    public val bucket: String,
    public val signedUrlDuration: Duration? = null,
    override val context: SettingContext
) : PublicFileSystem {

    private val signedUrlDurationJava: java.time.Duration? = signedUrlDuration?.toJavaDuration()

    override val rootUrls: List<String> = listOf(
        "https://${bucket}.s3.${region.id()}.amazonaws.com/",
        "https://s3-${region.id()}.amazonaws.com/${bucket}/",
    )

    private var credsOnHand: AwsCredentials? = null
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
     * Gets the current AWS credentials.
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
     * Gets a signing key for the given date.
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
     * The S3 client.
     */
    public val s3: S3Client by lazy {
        S3Client.builder()
            .region(region)
            .credentialsProvider(credentialProvider)
            .build()
    }

    /**
     * The S3 async client.
     */
    public val s3Async: S3AsyncClient by lazy {
        S3AsyncClient.builder()
            .region(region)
            .credentialsProvider(credentialProvider)
            .build()
    }

    /**
     * The S3 presigner.
     */
    public val signer: S3Presigner by lazy {
        S3Presigner.builder()
            .region(region)
            .credentialsProvider(credentialProvider)
            .build()
    }

    override val root: S3FileObject = S3FileObject(this, File(""))

    override fun parseSignedUrlForRead(url: String): FileObject {
        val path = url.substringAfter(rootUrls.first()).substringBefore('?')
        return S3FileObject(this, File(path))
    }

    override fun parseSignedUrlForWrite(url: String): FileObject {
        val path = url.substringAfter(rootUrls.first()).substringBefore('?')
        return S3FileObject(this, File(path))
    }

    /**
     * Checks the health of the S3 connection by performing a test write and read.
     */
    override suspend fun healthCheck(): HealthStatus {
        return try {
            val testFile = root.resolve("health-check/test-file.txt")
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

                val signedUrlDuration = params["signedUrlDuration"]?.let {
                    if(it == "forever" || it == "null") null
                    else if(it.all { it.isDigit() }) it.toLong().seconds
                    else Duration.parse(it)
                } ?: 1.hours

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