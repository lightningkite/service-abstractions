package com.lightningkite.services.embedding.bedrock

import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/**
 * Pure-Kotlin (KMP) implementation of AWS Signature Version 4 for the Bedrock
 * embedding runtime endpoints.
 *
 * Copied from the `:ai-bedrock` module (which marks its SigV4 code `internal`)
 * so that this module is self-contained and does not require cross-module access
 * to internal symbols.
 */
internal object SigV4 {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"

    /**
     * Sign a single request. Returns the map of additional headers the caller must
     * add (`Authorization`, `X-Amz-Date`, `X-Amz-Content-Sha256`, and
     * `X-Amz-Security-Token` when a session token is present).
     */
    fun signHeaders(
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

        return buildMap {
            put("Authorization", authorization)
            put("X-Amz-Date", amzDate)
            if (includeContentSha256Header) put("X-Amz-Content-Sha256", payloadHash)
            credentials.sessionToken?.let { put("X-Amz-Security-Token", it) }
        }
    }

    private fun deriveSigningKey(
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

internal fun sha256Hex(data: ByteArray): String = SHA256().digest(data).toHex()

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    HmacSHA256(key).doFinal(data)

private val HEX = "0123456789abcdef".toCharArray()

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0f])
    }
    return sb.toString()
}
