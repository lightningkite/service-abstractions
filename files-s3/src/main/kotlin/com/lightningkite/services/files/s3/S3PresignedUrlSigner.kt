package com.lightningkite.services.files.s3

import com.lightningkite.services.http.client
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.regions.Region
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.spec.SecretKeySpec
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration

/**
 * Presigns S3 URLs (AWS Signature Version 4, query-string form) and verifies them.
 *
 * This is hand-rolled rather than using the SDK's presigner because it is significantly faster: the
 * signature itself is a handful of HMACs, and the SDK's request pipeline around them was most of the
 * cost. Credentials and the derived signing key are cached between calls.
 *
 * URLs are on the bucket's own S3 host (`https://<bucket>.s3.<region>.amazonaws.com/<key>`). The host is
 * part of what's signed, so they only work against S3 itself, not a custom domain in front of it
 * (wrap this in a [CloudFrontUrlSigner] for that).
 *
 * @property region The bucket's region, part of every signature's scope.
 * @property bucket The bucket the URLs point into.
 * @property credentialProvider Credentials to sign with. Without [serverKey], verification recomputes with
 * these, so URLs signed with other credentials can only be verified by asking S3 (see [verify]).
 * @param serverKey A key (16+ bytes) shared by every server using this bucket. When set, download URLs also
 * carry a [SERVER_SIGNATURE_PARAM] made with it, so any of those servers can verify them without asking S3,
 * even though each signs with its own (and rotating) AWS credentials. S3 ignores the parameter, but it is
 * covered by the S3 signature like any other.
 */
public class S3PresignedUrlSigner(
    public val region: Region,
    public val bucket: String,
    public val credentialProvider: AwsCredentialsProvider,
    serverKey: ByteArray? = null,
) : S3UrlSigner {
    /** The bucket's S3 host, part of every signature. */
    private val host: String = "$bucket.s3.${region.id()}.amazonaws.com"

    private val serverKey: ByteArray? = serverKey?.also {
        require(it.size >= 16) { "The server key must be at least 16 bytes" }
    }?.copyOf()

    /**
     * The [SERVER_SIGNATURE_PARAM] value for a download of [encodedPath] signed at [date] for
     * [expiresSeconds], with [key]. It covers everything that decides which file the URL is for and when
     * it expires.
     */
    private fun serverSignature(key: ByteArray, encodedPath: String, date: String, expiresSeconds: String): String =
        "GET\n$host\n/$encodedPath\n$date\n$expiresSeconds".toByteArray().mac(key).toHex()

    override val publicRoot: String = "https://$host/"
    /** Credentials with the time they expire, published together. */
    private class CachedCreds(val creds: DirectAwsCredentials, val expiresAtMs: Long)

    // One reference, so a reader never pairs credentials with some other expiry time.
    @Volatile
    private var cachedCreds: CachedCreds? = null

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

        // The compiler-generated toString() would print the raw secret/token - override it so logging,
        // exceptions, or a debugger view of this value never leak live AWS credentials.
        override fun toString(): String =
            "DirectAwsCredentials(access=$access, secret=***, token=${token?.let { "***" }})"
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
        val cached = cachedCreds
        if (cached != null && System.currentTimeMillis() <= cached.expiresAtMs) return cached.creds
        val x = credentialProvider.resolveCredentials()
        val y = DirectAwsCredentials(
            access = x.accessKeyId(),
            secret = x.secretAccessKey(),
            token = (x as? AwsSessionCredentials)?.sessionToken(),
        )
        val expiresAtMs =
            x.expirationTime().getOrNull()?.toEpochMilli() ?: (System.currentTimeMillis() + 24L * 60 * 60 * 1000)
        cachedCreds = CachedCreds(y, expiresAtMs)
        return y
    }

    /** A derived key with the date and access key it was derived for, published together. */
    private class CachedSigningKey(val date: String, val accessKey: String, val key: SecretKeySpec)

    // One reference, so a reader never pairs a key with some other date or access key.
    @Volatile
    private var cachedSigningKey: CachedSigningKey? = null

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
        val cached = cachedSigningKey
        if (cached != null && cached.date == date && cached.accessKey == currentCreds.access) return cached.key
        val newKey = "AWS4${currentCreds.secret}".toByteArray()
            .let { date.toByteArray().mac(it) }
            .let { region.id().toByteArray().mac(it) }
            .let { "s3".toByteArray().mac(it) }
            .let { "aws4_request".toByteArray().mac(it) }
            .let { SecretKeySpec(it, "HmacSHA256") }
        cachedSigningKey = CachedSigningKey(date, currentCreds.access, newKey)
        return newKey
    }

    override fun signDownload(key: String, duration: Duration): String =
        presign(CONSTANT_BYTES_GET, key.aggressiveEncodeURLPath(), duration, serverSigned = true)

    override fun signUpload(key: String, duration: Duration): String =
        presign(CONSTANT_BYTES_PUT, key.aggressiveEncodeURLPath(), duration, serverSigned = false)

    private fun presign(method: ByteArray, encodedPath: String, duration: Duration, serverSigned: Boolean): String {
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
        val baseHeaders = tokenPreEncoded?.let {
            "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=${accessKey}%2F$dateOnly%2F$regionId%2Fs3%2Faws4_request&X-Amz-Date=$date&X-Amz-Expires=${duration.inWholeSeconds}&X-Amz-Security-Token=${it}&X-Amz-SignedHeaders=host"
        } ?: run {
            "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=${accessKey}%2F$dateOnly%2F$regionId%2Fs3%2Faws4_request&X-Amz-Date=$date&X-Amz-Expires=${duration.inWholeSeconds}&X-Amz-SignedHeaders=host"
        }
        // SigV4 signs the query sorted by byte, and lowercase sorts after every "X-Amz-" name, so this goes last.
        val serverKey = serverKey
        val preHeaders = if (serverSigned && serverKey != null) {
            val serverSig = serverSignature(serverKey, encodedPath, date, duration.inWholeSeconds.toString())
            "$baseHeaders&$SERVER_SIGNATURE_PARAM=$serverSig"
        } else baseHeaders
        val hashHolder = ByteArray(32)
        val canonicalRequestHasher = java.security.MessageDigest.getInstance("SHA-256")
        canonicalRequestHasher.update(method)
        canonicalRequestHasher.update(encodedPath.toByteArray())
        canonicalRequestHasher.update(CONSTANT_BYTE_NEWLINE)
        canonicalRequestHasher.update(preHeaders.toByteArray())
        canonicalRequestHasher.update(CONSTANT_BYTES_HOST)
        canonicalRequestHasher.update(host.toByteArray())
        canonicalRequestHasher.update(CONSTANT_BYTES_SIGNED_HEADERS)
        canonicalRequestHasher.digest(hashHolder, 0, 32)
        val canonicalRequestHash = hashHolder.toHex()
        val finalHasher = javax.crypto.Mac.getInstance("HmacSHA256")
        finalHasher.init(signingKey(dateOnly, creds))
        finalHasher.update(CONSTANT_BYTES_ALGORITHM)
        finalHasher.update(date.toByteArray())
        finalHasher.update(CONSTANT_BYTE_NEWLINE)
        finalHasher.update(dateOnly.toByteArray())
        finalHasher.update(CONSTANT_BYTE_SLASH)
        finalHasher.update(regionId.toByteArray())
        finalHasher.update(CONSTANT_BYTES_SCOPE_END)
        finalHasher.update(canonicalRequestHash.toByteArray())
        finalHasher.doFinal(hashHolder, 0)
        val regeneratedSig = hashHolder.toHex()
        return "https://$host/$encodedPath?$preHeaders&X-Amz-Signature=$regeneratedSig"
    }

    /**
     * Checks that [queryParams] (the part of a presigned URL after `?`) presign a GET of [key] and
     * haven't expired by [now]. Tries, in order:
     *
     * 1. With a server key, the URL's [SERVER_SIGNATURE_PARAM]. This works for URLs signed by any server
     *    sharing the key, whatever AWS credentials each signed with.
     * 2. Recomputing the AWS Signature V4 signature with our own signing key (derived from
     *    [credentialProvider]) over the presented URL's own query parameters. This covers URLs this server
     *    signed before it had a server key, and ones from the SDK's presigner.
     * 3. Asking S3 directly over the network, for anything else - including URLs signed with other
     *    credentials, and malformed ones. S3 rejects tampered or foreign URLs.
     *
     * Signatures are compared in constant time.
     *
     * @throws IllegalArgumentException if the signature is invalid or expired
     */
    override fun verify(key: String, queryParams: String, now: Instant) {
        val encodedPath = key.aggressiveEncodeURLPath()
        val url = "https://$host/$encodedPath"
        val headers: Map<String, String>
        val date: String
        val expiresSeconds: String
        val expiresAt: Instant
        try {
            headers = queryParams.split('&').associate {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(
                    it.substringAfter('=', ""), Charsets.UTF_8
                )
            }
            date = headers["X-Amz-Date"] ?: throw IllegalArgumentException("No query parameter 'X-Amz-Date' found.")
            expiresSeconds = headers["X-Amz-Expires"]
                ?: throw IllegalArgumentException("No query parameter 'X-Amz-Expires' found.")
            expiresAt = parseAmzDate(date).plusSeconds(expiresSeconds.toLong())
        } catch (e: Exception) {
            // Can't even read the URL's validity window (malformed/foreign URL), so only S3 can say.
            verifyOverNetwork(url, queryParams)
            return
        }

        if (hasValidServerSignature(encodedPath, date, expiresSeconds, headers) ||
            hasValidS3Signature(encodedPath, queryParams, date, headers)
        ) {
            // A matching signature only proves authenticity; the URL must also be within its
            // X-Amz-Date + X-Amz-Expires validity window. AWS S3 enforces this server-side when the
            // URL is used against it, but local verification must enforce it too - otherwise an
            // expired-but-once-valid URL is accepted here and laundered into a permanent,
            // auto-renewing reference by the serializer. (The local backend and future: path both
            // already check expiry; this keeps S3 consistent.)
            if (now.isAfter(expiresAt)) throw IllegalArgumentException("Signed URL has expired")
            return
        }

        verifyOverNetwork(url, queryParams)
    }

    /** Whether [headers] carry a [SERVER_SIGNATURE_PARAM] made with our server key for this download. */
    private fun hasValidServerSignature(
        encodedPath: String,
        date: String,
        expiresSeconds: String,
        headers: Map<String, String>,
    ): Boolean {
        val serverKey = serverKey ?: return false
        val presented = headers[SERVER_SIGNATURE_PARAM] ?: return false
        val expected = serverSignature(serverKey, encodedPath, date, expiresSeconds)
        return java.security.MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    /**
     * Whether the URL's `X-Amz-Signature` is the one our current AWS credentials give for [queryParams].
     * False when it can't be recomputed (missing parameters, or the credentials can't be resolved).
     */
    private fun hasValidS3Signature(
        encodedPath: String,
        queryParams: String,
        date: String,
        headers: Map<String, String>,
    ): Boolean {
        val presentedSignature = headers["X-Amz-Signature"] ?: return false
        val recomputedSignature = try {
            val secretKey = credentialProvider.resolveCredentials().secretAccessKey()
            val algorithm = headers["X-Amz-Algorithm"] ?: return false
            val credential = headers["X-Amz-Credential"] ?: return false
            val scope = credential.substringAfter("/")

            val canonicalRequest = """
            GET
            /$encodedPath
            ${queryParams.substringBefore("&X-Amz-Signature=").split('&').sorted().joinToString("&")}
            host:$host

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

            toSignString.toByteArray().mac(signingKeyBytes).toHex()
        } catch (e: Exception) {
            return false
        }
        return java.security.MessageDigest.isEqual(
            presentedSignature.toByteArray(Charsets.UTF_8),
            recomputedSignature.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Fallback: validates the presigned URL by asking S3 directly, for URLs that can't be verified
     * locally. Performs a blocking network round-trip, so local recomputation is always tried first.
     */
    private fun verifyOverNetwork(url: String, queryParams: String) {
        // The shared client applies a 60s engine timeout.
        runBlocking(Dispatchers.IO) {
            val response = client.get("$url?$queryParams") {
                header("Range", "bytes=0-0")
            }
            if (!response.status.isSuccess()) throw IllegalArgumentException("Could not verify signature")
        }
    }

    /**
     * Parses an AWS `X-Amz-Date` value (e.g. `20260722T101054Z`) into a UTC [Instant].
     * This is the signing timestamp; adding `X-Amz-Expires` seconds gives the URL's expiry.
     */
    private fun parseAmzDate(amzDate: String): Instant =
        java.time.LocalDateTime.parse(amzDate, AMZ_DATE_FORMATTER).toInstant(ZoneOffset.UTC)

    // The credentials hold a secret.
    override fun toString(): String = "S3PresignedUrlSigner(region=$region, bucket=$bucket)"

    public companion object {
        /**
         * The query parameter holding a download URL's server signature (see the `serverKey` constructor
         * parameter). Lowercase so it sorts after every `X-Amz-` parameter.
         */
        public const val SERVER_SIGNATURE_PARAM: String = "serverSig"
    }
}

private val AMZ_DATE_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

// Fixed parts of the SigV4 canonical request and string to sign, pre-encoded for speed.
private val CONSTANT_BYTES_GET = "GET\n/".toByteArray()
private val CONSTANT_BYTES_PUT = "PUT\n/".toByteArray()
private val CONSTANT_BYTES_HOST = "\nhost:".toByteArray()
private val CONSTANT_BYTES_SIGNED_HEADERS = "\n\nhost\nUNSIGNED-PAYLOAD".toByteArray()
private val CONSTANT_BYTES_ALGORITHM = "AWS4-HMAC-SHA256\n".toByteArray()
private val CONSTANT_BYTE_NEWLINE = '\n'.code.toByte()
private val CONSTANT_BYTE_SLASH = '/'.code.toByte()
private val CONSTANT_BYTES_SCOPE_END = "/s3/aws4_request\n".toByteArray()

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
