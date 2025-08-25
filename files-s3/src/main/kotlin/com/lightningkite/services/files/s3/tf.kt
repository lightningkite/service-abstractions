package com.lightningkite.services.files.s3

import com.lightningkite.services.Untested
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.terraform.PolicyStatement
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProvider
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

/**
 * Creates an AWS S3 bucket for file storage.
 *
 * @param forceDestroy Whether to force destroy the bucket when the resource is deleted. Default is true.
 * @param signedUrlDuration The duration for which signed URLs are valid. If null, public access is enabled.
 * @return A TerraformServiceResult with the configuration for the S3 bucket.
 */
@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<PublicFileSystem.Settings>.awsS3Bucket(
    forceDestroy: Boolean = true,
    signedUrlDuration: Duration? = null
): Unit {
    emitter.fulfillSetting(
        name, JsonPrimitive(
            value = if (signedUrlDuration == null) {
                "s3://\${aws_s3_bucket.${name}.bucket}.s3-\${aws_s3_bucket.${name}.region}.amazonaws.com/"
            } else {
                "s3://\${aws_s3_bucket.${name}.bucket}.s3-\${aws_s3_bucket.${name}.region}.amazonaws.com/?signedUrlDuration=$signedUrlDuration"
            }
        )
    )
    emptyList<TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }
    emitter.emit(name) {
        "resource.aws_s3_bucket.${name}" {
            "bucket_prefix" - "${emitter.projectPrefix}-${name.lowercase()}"
            "force_destroy" - forceDestroy
        }
        if (signedUrlDuration == null) {
            "resource.aws_s3_bucket_public_access_block.${name}" {
                "bucket" - expression("aws_s3_bucket.${name}.id")

                "block_public_acls" - false
                "block_public_policy" - false
                "ignore_public_acls" - false
                "restrict_public_buckets" - false
            }

            "resource.aws_s3_bucket_policy.${name}" {
                "depends_on" - listOf<String>("aws_s3_bucket_public_access_block.${name}")
                "bucket" - expression("aws_s3_bucket.${name}.id")
                "policy" - $$"""
                {
                    "Version": "2012-10-17",
                    "Statement": [
                        {
                            "Sid": "PublicReadGetObject",
                            "Effect": "Allow",
                            "Principal": "*",
                            "Action": [
                                "s3:GetObject"
                            ],
                            "Resource": [
                                "arn:aws:s3:::${aws_s3_bucket.$${name}.id}",
                                "arn:aws:s3:::${aws_s3_bucket.$${name}.id}/*",
                            ]
                        }
                    ]
                }
                """
            }
        }
    }
    emitter.policyStatements += (PolicyStatement(
        action = listOf("s3:*"),
        resource = listOf(
            $$"arn:aws:s3:::${aws_s3_bucket.$${name}.id}",
            $$"arn:aws:s3:::${aws_s3_bucket.$${name}.id}/*",
        ))
    )
}