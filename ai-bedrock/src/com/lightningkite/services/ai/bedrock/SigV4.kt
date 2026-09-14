package com.lightningkite.services.ai.bedrock

import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/**
 * Pure-Kotlin (KMP) implementation of AWS Signature Version 4 — the header-based variant used
 * by the Bedrock runtime endpoints.
 *
 * This is a direct translation of the algorithm described in the AWS docs; it does not use
 * the AWS Kotlin SDK, aws-crt, or any JVM-only crypto API. HMAC-SHA256 and SHA-256 come from
 * [KotlinCrypto] which supports every target this module compiles for.
 *
 * Only the header-based flow (`Authorization` header with `AWS4-HMAC-SHA256`) is implemented:
 * Bedrock does not accept query-string-signed requests.
 */
public object SigV4 {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"

    /**
     * Sign a single request. Returns the map of additional headers the caller must add to
     * the wire request (`Authorization`, `X-Amz-Date`, `X-Amz-Content-Sha256`, and
     * `X-Amz-Security-Token` when a session token is present).
     *
     * The caller is responsible for sending [host] as the `Host` header (ktor does this
     * automatically when the URL is well-formed).
     *
     * @param method HTTP method in upper case ("POST", "GET", ...).
     * @param host fully-qualified host used for the Host header and the canonical request.
     * @param path URI path beginning with `/`, already URI-encoded per AWS rules (do not
     *   double-encode — Bedrock model paths contain `:` which is fine unescaped).
     * @param query pre-built canonical query string, or empty string for none.
     * @param headers additional request headers (e.g. `content-type`); keys are compared
     *   case-insensitively. `host` and `x-amz-*` headers added by the signer should not be
     *   passed in here.
     * @param body raw payload bytes (empty array for GET/HEAD). The signer hashes this.
     * @param credentials credentials to sign with.
     * @param region AWS region (e.g. "us-west-2").
     * @param service AWS service name (e.g. "bedrock").
     * @param amzDate ISO-8601 timestamp `YYYYMMDDTHHMMSSZ` used in the signature — callers
     *   normally leave this null to use the current wall-clock, but tests pin it for
     *   deterministic vectors.
     */
    public fun signHeaders(
        method: String,
        host: String,
        path: String,
        query: String,
        headers: Map<String, String>,
        body: ByteArray,
        credentials: AwsCredentials,
        region: String,
        service: String,
        amzDate: String,
        includeContentSha256Header: Boolean = false,
    ): Map<String, String> {
        val dateStamp = amzDate.substring(0, 8)

        val payloadHash = sha256Hex(body)

        // Headers that must appear in the signature calculation: always `host` and the amz
        // date; the payload SHA header is opt-in (S3 requires it; most other services accept
        // SigV4 without it). Session token, when present, is signed too. `content-length`
        // is excluded from the signed set — AWS SDKs skip it because transport layers fill
        // it in automatically, and the official test vectors (`post-x-www-form-urlencoded`)
        // confirm this shape.
        val allHeaders = buildMap {
            put("host", host)
            put("x-amz-date", amzDate)
            if (includeContentSha256Header) put("x-amz-content-sha256", payloadHash)
            credentials.sessionToken?.let { put("x-amz-security-token", it) }
            for ((k, v) in headers) {
                val key = k.lowercase()
                if (key == "content-length") continue
                put(key, v.trim())
            }
        }

        // Canonical headers: sorted by lowercase name, each "name:trimmed-value\n".
        val sortedKeys = allHeaders.keys.sorted()
        val canonicalHeaders = sortedKeys.joinToString(separator = "") { k ->
            k + ":" + collapseWhitespace(allHeaders.getValue(k)) + "\n"
        }
        val signedHeaders = sortedKeys.joinToString(";")

        val canonicalRequest = buildString {
            append(method.uppercase()); append('\n')
            append(path); append('\n')
            append(query); append('\n')
            append(canonicalHeaders); append('\n')
            append(signedHeaders); append('\n')
            append(payloadHash)
        }
        val canonicalRequestHash = sha256Hex(canonicalRequest.encodeToByteArray())

        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = buildString {
            append(ALGORITHM); append('\n')
            append(amzDate); append('\n')
            append(credentialScope); append('\n')
            append(canonicalRequestHash)
        }

        val signingKey = deriveSigningKey(credentials.secretAccessKey, dateStamp, region, service)
        val signature = hmacSha256(signingKey, stringToSign.encodeToByteArray()).toHex()

        val authorization = "$ALGORITHM " +
                "Credential=${credentials.accessKeyId}/$credentialScope, " +
                "SignedHeaders=$signedHeaders, " +
                "Signature=$signature"

        // Build the header set the caller should put on the wire. We return only the headers
        // the signer generated; the body of the request already carries anything the caller
        // supplied.
        return buildMap {
            put("Authorization", authorization)
            put("X-Amz-Date", amzDate)
            if (includeContentSha256Header) put("X-Amz-Content-Sha256", payloadHash)
            credentials.sessionToken?.let { put("X-Amz-Security-Token", it) }
        }
    }

    /**
     * Derive the per-request signing key via the chain documented in the AWS SigV4 spec:
     *   kSecret  = "AWS4" + secretKey (UTF-8)
     *   kDate    = HMAC(kSecret, date)
     *   kRegion  = HMAC(kDate, region)
     *   kService = HMAC(kRegion, service)
     *   kSigning = HMAC(kService, "aws4_request")
     */
    internal fun deriveSigningKey(
        secretAccessKey: String,
        dateStamp: String,
        region: String,
        service: String,
    ): ByteArray {
        val kSecret = ("AWS4" + secretAccessKey).encodeToByteArray()
        val kDate = hmacSha256(kSecret, dateStamp.encodeToByteArray())
        val kRegion = hmacSha256(kDate, region.encodeToByteArray())
        val kService = hmacSha256(kRegion, service.encodeToByteArray())
        return hmacSha256(kService, "aws4_request".encodeToByteArray())
    }

    /**
     * Collapse runs of whitespace inside a header value into a single space per AWS rules.
     * Leading/trailing whitespace is already stripped by the caller via [kotlin.text.trim].
     */
    private fun collapseWhitespace(value: String): String {
        if (value.isEmpty()) return value
        val sb = StringBuilder(value.length)
        var inSpace = false
        for (c in value) {
            if (c == ' ' || c == '\t') {
                if (!inSpace) {
                    sb.append(' ')
                    inSpace = true
                }
            } else {
                sb.append(c)
                inSpace = false
            }
        }
        return sb.toString()
    }
}

/** Lower-case hex string of the SHA-256 digest of [data]. */
internal fun sha256Hex(data: ByteArray): String = SHA256().digest(data).toHex()

/** Raw HMAC-SHA-256 bytes. */
internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    HmacSHA256(key).doFinal(data)

private val HEX = "0123456789abcdef".toCharArray()

/** Lowercase hex encoding — matches the format AWS expects for canonical hashes. */
internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0f])
    }
    return sb.toString()
}
