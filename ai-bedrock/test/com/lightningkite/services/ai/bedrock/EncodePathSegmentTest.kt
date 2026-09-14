package com.lightningkite.services.ai.bedrock

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for the SigV4 canonical-path encoding. Bedrock is a non-S3 service, so AWS
 * URI-encodes each path segment when it recomputes the canonical request — notably the `:` in a
 * model id's `:0` version suffix becomes `%3A`. If [encodePathSegment] stops encoding the colon,
 * every Bedrock request's signature is rejected with "The request signature we calculated does
 * not match the signature you provided."
 */
class EncodePathSegmentTest {

    @Test
    fun encodesColonInModelVersionSuffix() {
        assertEquals(
            "amazon.nova-lite-v1%3A0",
            encodePathSegment("amazon.nova-lite-v1:0"),
        )
        assertEquals(
            "anthropic.claude-sonnet-4-5-20250929-v1%3A0",
            encodePathSegment("anthropic.claude-sonnet-4-5-20250929-v1:0"),
        )
    }

    @Test
    fun leavesUnreservedCharactersUntouched() {
        // RFC 3986 unreserved: A-Z a-z 0-9 - . _ ~ pass through verbatim.
        val unreserved = "AZaz09-._~"
        assertEquals(unreserved, encodePathSegment(unreserved))
    }
}
