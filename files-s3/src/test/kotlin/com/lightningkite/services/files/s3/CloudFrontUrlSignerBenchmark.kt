package com.lightningkite.services.files.s3

import com.lightningkite.services.test.expensive
import com.lightningkite.services.test.performance
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest
import java.security.KeyPairGenerator
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Timings for [CloudFrontUrlSigner]: signing against the AWS SDK's signer, and public-key verification
 * against verifying by re-signing (how it worked before).
 *
 * RSA-2048 dominates both signers, so signing should be roughly even with the SDK. Verification is
 * where the public key pays off, and that's the only comparison asserted.
 *
 * Takes several seconds, so it only runs with `RUN_EXPENSIVE_TESTS=true`:
 * `RUN_EXPENSIVE_TESTS=true ./gradlew :files-s3:test --tests "*CloudFrontUrlSignerBenchmark"`
 */
class CloudFrontUrlSignerBenchmark {

    // Lazy so a skipped run doesn't pay for key generation.
    private val keyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private val signer by lazy { cloudFront("K2JCJMDEHXQW5F", keyPair.private) }
    private val resource = "https://files.example.com/folder/caf%C3%A9%20menu.txt"
    private val expires = Instant.now().plusSeconds(3600)

    @Test
    fun signing() = expensive {
        val sdk = CloudFrontUtilities.create()
        val ours = performance(2_000) { signer.signResource(resource, expires) }
        val theirs = performance(2_000) {
            sdk.getSignedUrlWithCannedPolicy(
                CannedSignerRequest.builder()
                    .resourceUrl(resource)
                    .privateKey(keyPair.private)
                    .keyPairId("K2JCJMDEHXQW5F")
                    .expirationDate(expires)
                    .build()
            ).url()
        }
        println("Sign - ours: $ours, AWS SDK: $theirs, ratio: ${ours / theirs}")
    }

    @Test
    fun verification() = expensive {
        val query = signer.signResource(resource, expires).substringAfter('?')
        val signature = query.substringAfter("Signature=").substringBefore('&')
        val publicKey = performance(20_000) { signer.verifyResource(resource, query) }
        val resigning = performance(2_000) { signer.verifyByResigning(resource, expires.epochSecond, signature) }
        println("Verify - public key: $publicKey, re-signing: $resigning, speedup: ${resigning / publicKey}x")
        assertTrue(publicKey < resigning, "Public-key verification ($publicKey) should beat re-signing ($resigning)")
    }
}
