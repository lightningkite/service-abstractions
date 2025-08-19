package com.lightningkite.services.files.s3

import com.lightningkite.services.Untested
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import com.lightningkite.services.terraform.TerraformServiceResult
import com.lightningkite.services.terraform.terraformJsonObject
import kotlin.time.Duration

/**
 * Creates an AWS S3 bucket for file storage.
 *
 * @param forceDestroy Whether to force destroy the bucket when the resource is deleted. Default is true.
 * @param signedUrlDuration The duration for which signed URLs are valid. If null, public access is enabled.
 * @return A TerraformServiceResult with the configuration for the S3 bucket.
 */
@Untested
public fun TerraformNeed<PublicFileSystem.Settings>.awsS3Bucket(
    forceDestroy: Boolean = true,
    signedUrlDuration: Duration? = null
): TerraformServiceResult<PublicFileSystem.Settings> = TerraformServiceResult(
    need = this,
    setting = if (signedUrlDuration == null) {
        "s3://\${aws_s3_bucket.${name}.bucket}.s3-\${aws_s3_bucket.${name}.region}.amazonaws.com/"
    } else {
        "s3://\${aws_s3_bucket.${name}.bucket}.s3-\${aws_s3_bucket.${name}.region}.amazonaws.com/?signedUrlDuration=$signedUrlDuration"
    },
    requireProviders = setOf(TerraformProviderImport.aws),
    content = terraformJsonObject {
        "resource.aws_s3_bucket.$name" {
            "bucket_prefix" - "${cloudInfo.projectPrefix}-${name.lowercase()}"
            "force_destroy" - forceDestroy
        }
        
        
        if (signedUrlDuration == null) {
            "resource.aws_s3_bucket_public_access_block.$name" {
                "bucket" - expression("aws_s3_bucket.${name}.id")
                
                "block_public_acls" - false
                "block_public_policy" - false
                "ignore_public_acls" - false
                "restrict_public_buckets" - false
            }
            
            "resource.aws_s3_bucket_policy.$name" {
                "depends_on" - listOf("aws_s3_bucket_public_access_block.${name}")
                "bucket" - expression("aws_s3_bucket.${name}.id")
                "policy" - """
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
                                "arn:aws:s3:::*/*"
                            ]
                        }
                    ]
                }
                """
            }
        }
        
        "resource.aws_iam_policy.$name" {
            "name" - "${cloudInfo.projectPrefix}-${name}"
            "path" - "/${cloudInfo.projectPrefix}/${name}/"
            "description" - "Access to the ${cloudInfo.projectPrefix}_${name} bucket"
            "policy" - """
            {
                "Version": "2012-10-17",
                "Statement": [
                    {
                        "Action": [
                            "s3:*"
                        ],
                        "Effect": "Allow",
                        "Resource": "*"
                    }
                ]
            }
            """
        }
    }
)