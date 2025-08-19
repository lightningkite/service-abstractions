package com.lightningkite.services.files.s3

import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.assertTerraformApply
import com.lightningkite.services.test.expensive
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours

class TfTest {
    @Test
    fun testPublicBucket() {
        assertPlannableAws<PublicFileSystem.Settings>(vpc = false) {
            it.awsS3Bucket(
                forceDestroy = true,
                signedUrlDuration = null
            )
        }
    }

    @Test
    fun testSignedBucket() {
        assertPlannableAws<PublicFileSystem.Settings>(vpc = false) {
            it.awsS3Bucket(
                forceDestroy = true,
                signedUrlDuration = 1.hours
            )
        }
    }

    @Test
    fun expensiveTestPublicBucket() {
        S3PublicFileSystem
        expensive {
            assertTerraformApply(
                name = "aws-s3-public",
                domain = false,
                vpc = false,
                serializer = PublicFileSystem.Settings.serializer(),
                fulfill = {
                    it.awsS3Bucket(
                        forceDestroy = true,
                        signedUrlDuration = null
                    )
                }
            )
        }
    }

    @Test
    fun expensiveTestSignedBucket() {
        S3PublicFileSystem
        expensive {
            assertTerraformApply(
                name = "aws-s3-signed",
                domain = false,
                vpc = false,
                serializer = PublicFileSystem.Settings.serializer(),
                fulfill = {
                    it.awsS3Bucket(
                        forceDestroy = true,
                        signedUrlDuration = 1.hours
                    )
                }
            )
        }
    }
}