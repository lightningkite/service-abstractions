package com.lightningkite.services.ai.bedrock

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SigV4 signatures checked against AWS's canonical test vectors. The reference files come
 * from `aws-sig-v4-test-suite` (AWS General Reference, Apache 2.0); the JSON the tests were
 * sourced from lives at https://github.com/saibotsivad/aws-sig-v4-test-suite.
 *
 * All vectors use AWS's documented example key:
 *   AKIDEXAMPLE / wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY
 *   region=us-east-1, service=service, date=20150830T123600Z
 *
 * A matching signature validates (1) the signing-key derivation chain, (2) canonical request
 * assembly, (3) string-to-sign construction, and (4) the final HMAC. Failure on any of these
 * breaks the test.
 */
class SigV4TestVectorsTest {

    private val creds = AwsCredentials(
        accessKeyId = "AKIDEXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
    )
    private val region = "us-east-1"
    private val service = "service"
    private val amzDate = "20150830T123600Z"

    /**
     * Extract just the `Signature=` value from an AWS-format `Authorization` header. Saves
     * each test a few lines of string surgery.
     */
    private fun signatureOf(headers: Map<String, String>): String {
        val auth = headers.getValue("Authorization")
        return auth.substringAfter("Signature=").trim()
    }

    /**
     * Vector: `get-vanilla` — GET / with only Host and X-Amz-Date.
     *
     * Expected signature per the AWS `.authz` file:
     *   5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31
     */
    @Test fun getVanilla() {
        val result = SigV4.signHeaders(
            method = "GET",
            host = "example.amazonaws.com",
            path = "/",
            query = "",
            headers = emptyMap(),
            body = ByteArray(0),
            credentials = creds,
            region = region,
            service = service,
            amzDate = amzDate,
        )
        assertEquals(
            "5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            signatureOf(result),
        )
    }

    /**
     * Vector: `post-vanilla` — POST / with empty body.
     *
     * Expected: 5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b
     */
    @Test fun postVanilla() {
        val result = SigV4.signHeaders(
            method = "POST",
            host = "example.amazonaws.com",
            path = "/",
            query = "",
            headers = emptyMap(),
            body = ByteArray(0),
            credentials = creds,
            region = region,
            service = service,
            amzDate = amzDate,
        )
        assertEquals(
            "5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b",
            signatureOf(result),
        )
    }

    /**
     * Vector: `post-x-www-form-urlencoded` — POST with form body. Exercises the caller-
     * supplied headers path (content-type is in the signature; content-length is excluded
     * per AWS convention — the transport layer fills it in).
     *
     * Expected: ff11897932ad3f4e8b18135d722051e5ac45fc38421b1da7b9d196a0fe09473a
     */
    @Test fun postFormUrlencoded() {
        val body = "Param1=value1".encodeToByteArray()
        val result = SigV4.signHeaders(
            method = "POST",
            host = "example.amazonaws.com",
            path = "/",
            query = "",
            headers = mapOf(
                "content-type" to "application/x-www-form-urlencoded",
                "content-length" to "13",
            ),
            body = body,
            credentials = creds,
            region = region,
            service = service,
            amzDate = amzDate,
        )
        assertEquals(
            "ff11897932ad3f4e8b18135d722051e5ac45fc38421b1da7b9d196a0fe09473a",
            signatureOf(result),
        )
    }

    /**
     * Sanity check on the chained signing-key derivation: the first step of every vector
     * pulls through the same kSigning, so testing the chain directly narrows the blame when
     * any of the three full-flow tests above fails.
     */
    @Test fun signingKeyDerivation() {
        val key = SigV4.deriveSigningKey(
            secretAccessKey = creds.secretAccessKey,
            dateStamp = "20150830",
            region = region,
            service = service,
        )
        // kSigning for (date=20150830, region=us-east-1, service=service). Independently
        // computed via a reference HMAC-SHA256 chain to pin the derivation.
        assertEquals(
            "938127b5336810ddb6a5d6af445fcac9e371f9ed418ed386b022aed82901be75",
            key.toHex(),
        )
    }
}
