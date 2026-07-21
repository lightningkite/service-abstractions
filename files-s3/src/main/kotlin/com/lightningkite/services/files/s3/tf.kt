package com.lightningkite.services.files.s3

import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.terraform.*
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

/**
 * Creates an AWS S3 bucket for file storage using Terraform.
 *
 * This function generates Terraform configuration for an S3 bucket with the following features:
 * - Automatic bucket naming with project prefix
 * - Optional public access configuration (when signedUrlDuration is null)
 * - CORS configuration for web browser uploads
 * - IAM policy granting full S3 access to the Lambda execution role
 *
 * When [signedUrlDuration] is null, the bucket is configured for public read access with:
 * - Public access block disabled
 * - Bucket policy allowing s3:GetObject for all principals
 *
 * When [signedUrlDuration] is set, the bucket remains private and signed URLs are used for access control.
 *
 * @param signedUrlDuration The duration for which signed URLs are valid. If null, public access is enabled.
 * @param forceDestroy Whether to force destroy the bucket when the Terraform resource is deleted,
 *                     even if the bucket contains objects. Default is true for easier cleanup in development.
 *                     Set to false in production to prevent accidental data loss.
 * @throws IllegalArgumentException if S3PublicFileSystem is not registered in the settings parser
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<PublicFileSystem.Settings>.awsS3Bucket(
    signedUrlDuration: Duration? = null,
    forceDestroy: Boolean = true,
    corsOrigins: Set<String> = setOf("*"),
    kmsKey: KmsKeySource? = null,
): Unit {
    if (!PublicFileSystem.Settings.supports("s3")) throw IllegalArgumentException("You need to reference S3PublicFileSystem in your server definition to use this.")
    // null falls back to the deployment-wide default; objects stay private (signed URLs), so KMS is transparent.
    val kmsKeyArn = (kmsKey ?: emitter.encryptionKey).resolveKeyArn(name)
    emitter.fulfillSetting(
        name, JsonPrimitive(
            value = if (signedUrlDuration == null) {
                $$"s3://${aws_s3_bucket.$${name}.bucket}.s3-${aws_s3_bucket.$${name}.region}.amazonaws.com/"
            } else {
                $$"s3://${aws_s3_bucket.$${name}.bucket}.s3-${aws_s3_bucket.$${name}.region}.amazonaws.com/?signedUrlDuration=$$signedUrlDuration"
            }
        )
    )
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }
    emitter.emit(name) {
        "resource.aws_s3_bucket.${name}" {
            // .lowercase().replace("_", "") on projectPrefix is backwards compatibility with V4 and earlier.
            // We can't really remove this at this time.
            "bucket_prefix" - "${emitter.projectPrefix.lowercase().replace("_", "")}-${name.lowercase()}"
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
                                "arn:aws:s3:::${aws_s3_bucket.$${name}.id}/*",
                            ]
                        }
                    ]
                }
                """
            }
        } else {
            // Private bucket: access is brokered exclusively through signed URLs, so block all public
            // access and encrypt objects at rest.
            "resource.aws_s3_bucket_public_access_block.${name}" {
                "bucket" - expression("aws_s3_bucket.${name}.id")

                "block_public_acls" - true
                "block_public_policy" - true
                "ignore_public_acls" - true
                "restrict_public_buckets" - true
            }
            "resource.aws_s3_bucket_server_side_encryption_configuration.${name}" {
                "bucket" - expression("aws_s3_bucket.${name}.id")
                "rule" {
                    "apply_server_side_encryption_by_default" {
                        if (kmsKeyArn != null) {
                            "sse_algorithm" - "aws:kms"
                            "kms_master_key_id" - kmsKeyArn
                        } else {
                            "sse_algorithm" - "AES256"
                        }
                    }
                    "blocked_encryption_types" - listOf("SSE-C")
                    // With a CMK, cache one data key per bucket to cut KMS request cost ~99% on object ops.
                    if (kmsKeyArn != null) "bucket_key_enabled" - true
                }
            }
        }
        "resource.aws_s3_bucket_cors_configuration.$name" {
            "bucket" - expression("aws_s3_bucket.$name.bucket")

            "cors_rule" - listOf(
                terraformJsonObject {
                    "allowed_headers" - listOf("*")
                    "allowed_methods" - listOf("PUT", "POST")
                    "allowed_origins" - corsOrigins.sorted()
                    "expose_headers" - listOf("ETag")
                    "max_age_seconds" - 3000
                },
                terraformJsonObject {
                    "allowed_headers" - listOf("*")
                    "allowed_methods" - listOf("GET", "HEAD")
                    "allowed_origins" - corsOrigins.sorted()
                }
            )
        }
    }
    // Least-privilege: the application only lists the bucket and reads/writes/deletes objects (signed-URL
    // generation needs only the underlying Get/Put). Bucket-level actions are scoped to the bucket arn,
    // object-level actions to its contents.
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf("s3:ListBucket", "s3:GetBucketLocation"),
        resource = listOf($$"${aws_s3_bucket.$${name}.arn}")
    )
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf("s3:GetObject", "s3:PutObject", "s3:DeleteObject"),
        resource = listOf($$"${aws_s3_bucket.$${name}.arn}/*")
    )

}

/*
 * TODO: API Recommendations for tf.kt
 *
 * 1. Security: The IAM policy grants s3:* (all S3 actions) on the bucket. Consider making this more granular
 *    with specific actions like s3:GetObject, s3:PutObject, s3:DeleteObject, s3:ListBucket for better security
 *    following the principle of least privilege.
 *
 * 4. Bucket Lifecycle: Consider adding optional lifecycle policies for automatic deletion of old files
 *    or transitioning to cheaper storage classes (e.g., Glacier) after a certain period.
 *
 * 5. Versioning: Consider adding an optional parameter to enable S3 versioning for data protection
 *    and audit trails.
 *
 * 6. Encryption: Consider adding server-side encryption configuration (SSE-S3 or SSE-KMS) as a parameter
 *    for security compliance.

 */