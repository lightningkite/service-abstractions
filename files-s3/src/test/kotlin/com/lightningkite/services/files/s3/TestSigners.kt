package com.lightningkite.services.files.s3

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import java.security.PrivateKey

/** Fixed, fake credentials: signing and verifying with them is CPU-only. */
internal val testCredentials: StaticCredentialsProvider = StaticCredentialsProvider.create(
    AwsBasicCredentials.create("AKIAEXAMPLEKEYID0000", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
)

/** S3 presigning for `example-bucket` in us-west-2. */
internal fun testS3Signer(): S3PresignedUrlSigner =
    S3PresignedUrlSigner(Region.US_WEST_2, "example-bucket", testCredentials)

/** CloudFront signing on `files.example.com`, wrapping [testS3Signer]. */
internal fun cloudFront(keyPairId: String, privateKey: PrivateKey): CloudFrontUrlSigner =
    CloudFrontUrlSigner(testS3Signer(), "files.example.com", keyPairId, privateKey)
