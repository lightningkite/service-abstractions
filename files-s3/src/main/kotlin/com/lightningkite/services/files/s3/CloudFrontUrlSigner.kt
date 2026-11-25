package com.lightningkite.services.files.s3

import java.net.URLDecoder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Serves downloads from a custom domain in front of the bucket through CloudFront, wrapping another
 * [S3UrlSigner] for everything else.
 *
 * Unsigned URLs ([publicRoot]) are on [domain]. For a public bucket that's all this does; without a
 * signing key, anything that does need signing is left to [s3].
 *
 * For a private bucket, give it the signing key ([keyPairId] and [privateKey]). S3's own presigned URLs
 * can't be used on the domain: their signature covers the S3 host name, and CloudFront re-signs its
 * requests to the bucket anyway. Instead, CloudFront checks a canned-policy signature made with a
 * private key whose public half is in one of the distribution's trusted key groups. With a key:
 *
 * - Downloads are CloudFront signed URLs on [domain].
 * - Uploads go to [s3], since they go straight to the bucket rather than through CloudFront.
 * - Verification handles CloudFront signed URLs itself (they carry `Key-Pair-Id`) and hands anything
 *   else to [s3], so S3 presigned URLs issued before the domain was added keep working.
 *
 * A canned-policy signature is RSA-SHA1 over a fixed JSON policy naming the exact URL and its expiry.
 * Verification checks it with the public key, derived from the private key, so no extra configuration is
 * needed. An RSA public-key operation is far cheaper than signing (see `CloudFrontUrlSignerBenchmark`),
 * which matters because every client-submitted reference is verified.
 *
 * @property s3 The signer for uploads, and for anything this one doesn't sign or recognize.
 * @property domain The host name CloudFront serves the bucket on, e.g. `files.example.com`.
 * @property keyPairId The ID of the CloudFront public key matching [privateKey] (`Key-Pair-Id` in the URL),
 * or null for a distribution that doesn't require signed URLs. Given together with [privateKey].
 */
public class CloudFrontUrlSigner(
    public val s3: S3PresignedUrlSigner,
    public val domain: String,
    public val keyPairId: String? = null,
    private val privateKey: PrivateKey? = null,
) : S3UrlSigner {
    init {
        require(domain.isNotBlank() && domain.none { it == '/' || it == '?' || it == '#' || it == '@' }) {
            "CloudFront domain must be a bare host name like 'files.example.com', got '$domain'"
        }
        require((keyPairId == null) == (privateKey == null)) {
            "CloudFront keyPairId and privateKey must be given together"
        }
        if (keyPairId != null) require(keyPairId.isNotBlank()) { "CloudFront key pair ID must not be blank" }
        if (privateKey != null) {
            require(privateKey.algorithm == "RSA") { "CloudFront signing keys must be RSA, got ${privateKey.algorithm}" }
        }
    }

    /** Whether this signer has a key, i.e. serves a distribution that requires signed URLs. */
    public val signs: Boolean get() = privateKey != null

    override val publicRoot: String = "https://$domain/"

    /**
     * The public half of [privateKey], for fast verification. Keys parsed from PKCS#1 or PKCS#8 always
     * carry the public exponent; a bare modulus-and-exponent key doesn't, so it falls back to re-signing.
     */
    private val publicKey: PublicKey? = (privateKey as? RSAPrivateCrtKey)?.let {
        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(it.modulus, it.publicExponent))
    }

    private fun urlOf(key: String): String = publicRoot + key.aggressiveEncodeURLPath()

    /** A CloudFront signed URL on [domain]; without a signing key, [s3]'s URL instead. */
    override fun signDownload(key: String, duration: Duration): String =
        if (signs) signResource(urlOf(key), Instant.now().plusSeconds(duration.coerceAtMost(1000.days).inWholeSeconds))
        else s3.signDownload(key, duration)

    override fun signUpload(key: String, duration: Duration): String = s3.signUpload(key, duration)

    /**
     * Checks a CloudFront signed URL for [key] on [domain]; parameters without `Key-Pair-Id`, or any
     * parameters when this signer has no key, go to [s3].
     *
     * @throws IllegalArgumentException if the parameters are missing, signed by another key, tampered with,
     * or expired
     */
    override fun verify(key: String, queryParams: String, now: Instant) {
        if (signs && hasQueryParameter(queryParams, "Key-Pair-Id")) verifyResource(urlOf(key), queryParams, now)
        else s3.verify(key, queryParams, now)
    }

    /**
     * Returns [resourceUrl] with the query parameters CloudFront needs to accept it until [expires].
     * [resourceUrl] must already be percent-encoded exactly as clients will request it, and must not have
     * a query string of its own.
     */
    internal fun signResource(resourceUrl: String, expires: Instant): String {
        val keyPairId = checkNotNull(keyPairId) { "This CloudFront signer has no signing key" }
        val epochSeconds = expires.epochSecond
        return "$resourceUrl?Expires=$epochSeconds&Signature=${signature(resourceUrl, epochSeconds)}&Key-Pair-Id=$keyPairId"
    }

    /**
     * Checks that [queryParams] (the part of a signed URL after `?`) is a signature this signer made for
     * [resourceUrl] that hasn't expired by [now].
     *
     * @throws IllegalArgumentException if the parameters are missing, signed by another key, tampered with,
     * or expired
     */
    internal fun verifyResource(resourceUrl: String, queryParams: String, now: Instant = Instant.now()) {
        val params = queryParams.split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                    URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }
        val presentedKeyPairId = params["Key-Pair-Id"]
            ?: throw IllegalArgumentException("No query parameter 'Key-Pair-Id' found.")
        val expires = params["Expires"]?.toLongOrNull()
            ?: throw IllegalArgumentException("No valid query parameter 'Expires' found.")
        val presentedSignature = params["Signature"]
            ?: throw IllegalArgumentException("No query parameter 'Signature' found.")
        require(presentedKeyPairId == keyPairId) { "Signed URL was not signed with this file system's key" }
        val valid = if (publicKey != null) {
            verifyWithPublicKey(publicKey, resourceUrl, expires, presentedSignature)
        } else {
            verifyByResigning(resourceUrl, expires, presentedSignature)
        }
        require(valid) { "Could not verify signature" }
        // CloudFront's condition is DateLessThan, so the URL stops working at the expiry second itself.
        require(now.epochSecond < expires) { "Signed URL has expired" }
    }

    private fun verifyWithPublicKey(
        publicKey: PublicKey,
        resourceUrl: String,
        expiresEpochSeconds: Long,
        presentedSignature: String,
    ): Boolean {
        val signatureBytes = try {
            // Undo CloudFront's URL-safe base64 variant.
            Base64.getDecoder().decode(
                presentedSignature.replace('-', '+').replace('_', '=').replace('~', '/')
            )
        } catch (e: IllegalArgumentException) {
            return false
        }
        return try {
            Signature.getInstance("SHA1withRSA").run {
                initVerify(publicKey)
                update(policy(resourceUrl, expiresEpochSeconds))
                verify(signatureBytes)
            }
        } catch (e: java.security.SignatureException) {
            // Wrong length or otherwise malformed.
            false
        }
    }

    /** Signing is deterministic, so a signature can also be checked by making it again. */
    internal fun verifyByResigning(resourceUrl: String, expiresEpochSeconds: Long, presentedSignature: String): Boolean =
        // Constant-time, so the comparison doesn't leak how many leading bytes matched.
        java.security.MessageDigest.isEqual(
            presentedSignature.toByteArray(Charsets.UTF_8),
            signature(resourceUrl, expiresEpochSeconds).toByteArray(Charsets.UTF_8)
        )

    internal fun signature(resourceUrl: String, expiresEpochSeconds: Long): String {
        val privateKey = checkNotNull(privateKey) { "This CloudFront signer has no signing key" }
        val signed = Signature.getInstance("SHA1withRSA").run {
            initSign(privateKey)
            update(policy(resourceUrl, expiresEpochSeconds))
            sign()
        }
        // CloudFront's URL-safe base64 variant.
        return Base64.getEncoder().encodeToString(signed)
            .replace('+', '-')
            .replace('=', '_')
            .replace('/', '~')
    }

    // CloudFront rebuilds this exact string (no whitespace) from the request to check the signature.
    private fun policy(resourceUrl: String, expiresEpochSeconds: Long): ByteArray =
        """{"Statement":[{"Resource":"$resourceUrl","Condition":{"DateLessThan":{"AWS:EpochTime":$expiresEpochSeconds}}}]}"""
            .toByteArray(Charsets.UTF_8)

    // Never print the private key.
    override fun toString(): String =
        "CloudFrontUrlSigner(domain=$domain, keyPairId=$keyPairId, privateKey=${privateKey?.let { "***" }}, s3=$s3)"

    public companion object {
        /**
         * Parses an RSA private key in any of the forms it's likely to arrive in through a settings URL:
         * PEM text, or base64 (standard or URL-safe) of either PEM text or DER bytes. Both PKCS#8
         * (`BEGIN PRIVATE KEY`) and PKCS#1 (`BEGIN RSA PRIVATE KEY`) keys are accepted. Encrypted keys
         * are not.
         */
        public fun parsePrivateKey(encoded: String): PrivateKey {
            val text = encoded.trim()
            require(text.isNotEmpty()) { "CloudFront private key is empty" }
            val pem = if (text.startsWith("-----BEGIN")) text
            else runCatching { base64Decode(text).toString(Charsets.UTF_8) }.getOrNull()
                ?.trim()
                ?.takeIf { it.startsWith("-----BEGIN") }
            val pkcs8: ByteArray = when {
                pem == null -> base64Decode(text).let { if (looksLikePkcs1(it)) wrapPkcs1(it) else it }
                pem.startsWith("-----BEGIN PRIVATE KEY-----") ->
                    base64Decode(pem.substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END"))
                pem.startsWith("-----BEGIN RSA PRIVATE KEY-----") ->
                    wrapPkcs1(base64Decode(pem.substringAfter("-----BEGIN RSA PRIVATE KEY-----").substringBefore("-----END")))
                else -> throw IllegalArgumentException(
                    "Unsupported CloudFront private key format '${pem.substringBefore('\n').trim()}'; " +
                            "expected an unencrypted RSA key ('BEGIN PRIVATE KEY' or 'BEGIN RSA PRIVATE KEY')."
                )
            }
            return try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            } catch (e: Exception) {
                throw IllegalArgumentException("Could not read the CloudFront private key as an RSA key", e)
            }
        }

        private fun base64Decode(value: String): ByteArray =
            // The MIME decoder skips line breaks and other non-alphabet characters, as PEM bodies need.
            Base64.getMimeDecoder().decode(value.replace('-', '+').replace('_', '/'))

        /**
         * A PKCS#1 RSAPrivateKey is SEQUENCE { INTEGER 0, INTEGER modulus, ... }, while PKCS#8 has a nested
         * SEQUENCE (the algorithm identifier) as its second element.
         */
        private fun looksLikePkcs1(der: ByteArray): Boolean {
            if (der.size < 4 || der[0] != 0x30.toByte()) return false
            val lengthBytes = if (der[1].toInt() and 0x80 == 0) 0 else der[1].toInt() and 0x7f
            val versionAt = 2 + lengthBytes
            val secondElementAt = versionAt + 3
            return der.size > secondElementAt &&
                    der[versionAt] == 0x02.toByte() && der[versionAt + 1] == 0x01.toByte() &&
                    der[secondElementAt] == 0x02.toByte()
        }

        /** Wraps a PKCS#1 RSAPrivateKey in the PKCS#8 PrivateKeyInfo structure Java's [KeyFactory] reads. */
        private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
            val version = byteArrayOf(0x02, 0x01, 0x00)
            // SEQUENCE { OID 1.2.840.113549.1.1.1 (rsaEncryption), NULL }
            val algorithm = byteArrayOf(
                0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
                0x0D, 0x01, 0x01, 0x01, 0x05, 0x00
            )
            val key = byteArrayOf(0x04) + derLength(pkcs1.size) + pkcs1
            val body = version + algorithm + key
            return byteArrayOf(0x30) + derLength(body.size) + body
        }

        private fun derLength(length: Int): ByteArray {
            if (length < 0x80) return byteArrayOf(length.toByte())
            val bytes = generateSequence(length) { it ushr 8 }.takeWhile { it > 0 }
                .map { (it and 0xff).toByte() }.toList().reversed()
            return byteArrayOf((0x80 or bytes.size).toByte()) + bytes
        }
    }
}
