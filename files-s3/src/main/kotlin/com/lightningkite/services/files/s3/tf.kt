package com.lightningkite.services.files.s3

import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.terraform.*
import kotlinx.serialization.json.*
import kotlin.time.Duration

/**
 * Creates an AWS S3 bucket for file storage using Terraform.
 *
 * This function generates Terraform configuration for an S3 bucket with the following features:
 * - Automatic bucket naming with project prefix
 * - Optional public access configuration (when signedUrlDuration is null or infinite)
 * - CORS configuration for web browser uploads
 * - IAM policy granting full S3 access to the Lambda execution role
 *
 * When [signedUrlDuration] is null or [Duration.INFINITE], the bucket is configured for public read access
 * (and not encrypted at rest) with:
 * - Public access block disabled
 * - Bucket policy allowing s3:GetObject for all principals
 *
 * When [signedUrlDuration] is set, the bucket remains private and signed URLs are used for access control.
 * Without a [domain], those are S3 presigned URLs, which every server can verify locally when the
 * application's `SettingContext.deriveSecret` supplies a key (Lightning Server derives one from its
 * `secretBasis`); otherwise a URL signed with another server's credentials is verified by asking S3.
 *
 * ## Custom domain
 *
 * When [domain] is set, files are served from `https://<domain>/` through a CloudFront distribution
 * instead of the raw S3 host. This adds:
 * - An ACM certificate for the domain (in us-east-1, as CloudFront requires), validated through Route53
 * - A CloudFront distribution reading the bucket through an origin access control, with a CORS
 *   response headers policy for [corsOrigins]
 * - Route53 A/AAAA alias records pointing the domain at the distribution
 * - A bucket policy letting that distribution read the bucket. A private bucket blocks all other access;
 *   a public one stays publicly readable on the S3 host too.
 *
 * With [signedUrlDuration] also set, the distribution only serves CloudFront signed URLs: Terraform
 * generates an RSA key pair, registers the public key in a trusted key group, and puts the private key
 * in the setting so the application can sign. (Like any generated secret, the private key is kept in
 * the Terraform state.)
 *
 * Upload URLs still point at S3 directly. URLs on the S3 host keep resolving, so references issued
 * before the domain was added stay valid.
 *
 * CloudFront caches files (by default for up to a day), so an object overwritten or deleted under the
 * same path can still be served from the cache until it expires.
 *
 * If objects are encrypted with a customer-managed KMS key ([kmsKey] or the deployment default),
 * CloudFront must be allowed to decrypt with it. For [KmsKeySource.CreateDedicated] this function sets
 * the key policy itself; for [KmsKeySource.Existing], the key's policy must grant
 * `cloudfront.amazonaws.com` `kms:Decrypt`, or CloudFront will answer 403.
 *
 * @param signedUrlDuration The duration for which signed URLs are valid. If null or [Duration.INFINITE],
 *                   URLs are unsigned and public access is enabled.
 * @param forceDestroy Whether to force destroy the bucket when the Terraform resource is deleted,
 *                     even if the bucket contains objects. Default is true for easier cleanup in development.
 *                     Set to false in production to prevent accidental data loss.
 * @param versioning Whether to enable S3 bucket versioning, keeping noncurrent versions of objects
 *                   around after they're overwritten or deleted, for data protection and audit trails.
 * @param noncurrentVersionExpirationDays When set, adds a lifecycle rule that permanently deletes
 *                   noncurrent object versions after this duration - otherwise, with [versioning]
 *                   enabled, they're kept (and billed for) forever. Requires [versioning]. A common
 *                   choice is 30-90 days, balancing recovery/audit needs against storage cost.
 * @param domain A host name (e.g. `files.example.com`) to serve files from through CloudFront. Its
 *                   Route53 hosted zone must be [domainZoneId].
 * @param domainZoneId The Route53 hosted zone ID for [domain]. Defaults to the emitter's zone when it is a
 *                   [TerraformEmitterAwsDomain]; required otherwise.
 * @param cloudFrontPriceClass The CloudFront price class for [domain]'s distribution: `PriceClass_100`
 *                   (North America and Europe), `PriceClass_200`, or `PriceClass_All`.
 * @throws IllegalArgumentException if S3PublicFileSystem is not registered in the settings parser,
 *                   if [noncurrentVersionExpirationDays] is set without [versioning], or if [domain] is
 *                   set without a hosted zone to put it in
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<ExternalFileSystem.Settings>.awsS3Bucket(
    signedUrlDuration: Duration? = null,
    forceDestroy: Boolean = true,
    corsOrigins: Set<String> = setOf("*"),
    kmsKey: KmsKeySource? = null,
    versioning: Boolean = false,
    noncurrentVersionExpiration: Duration? = null,
    domain: String? = null,
    domainZoneId: String? = null,
    cloudFrontPriceClass: String = "PriceClass_100",
): Unit {
    if (!ExternalFileSystem.Settings.supports("s3")) throw IllegalArgumentException("You need to reference S3PublicFileSystem in your server definition to use this.")
    require(noncurrentVersionExpiration == null || versioning) {
        "noncurrentVersionExpirationDays requires versioning to be enabled"
    }
    val zoneId = if (domain == null) null else {
        require(domain.isNotBlank() && domain.none { it == '/' || it == ':' || it == '?' || it == '#' || it == '&' }) {
            "domain must be a bare host name like 'files.example.com', got '$domain'"
        }
        domainZoneId ?: (emitter as? TerraformEmitterAwsDomain)?.domainZoneId
        ?: throw IllegalArgumentException(
            "A custom domain needs a Route53 hosted zone: pass domainZoneId, or use a TerraformEmitterAwsDomain"
        )
    }
    val urlExpiration = if (signedUrlDuration == Duration.INFINITE) null else signedUrlDuration
    val signedViaCloudFront = domain != null && urlExpiration != null
    // null falls back to the deployment-wide default; objects stay private (signed URLs), so KMS is transparent.
    val effectiveKmsKey = kmsKey ?: emitter.encryptionKey
    val kmsKeyArn = effectiveKmsKey.resolveKeyArn(name)
    val bucketHost = $$"${aws_s3_bucket.$${name}.bucket}.s3-${aws_s3_bucket.$${name}.region}.amazonaws.com"
    emitter.fulfillSetting(
        name, JsonPrimitive(
            value = when {
                domain == null && (urlExpiration == null) -> "s3://$bucketHost/"
                domain == null -> "s3://$bucketHost/?signedUrlDuration=$urlExpiration"
                // "forever" is explicit here because an absent signedUrlDuration means 1h, which on a
                // custom domain would demand CloudFront signing keys this deployment doesn't have.
                urlExpiration == null -> "s3://$bucketHost/?signedUrlDuration=forever&domain=$domain"
                else -> "s3://$bucketHost/?signedUrlDuration=$urlExpiration&domain=$domain" +
                        $$"&cloudFrontKeyId=${aws_cloudfront_public_key.$${name}.id}" +
                        $$"&cloudFrontPrivateKey=${urlencode(base64encode(tls_private_key.$${name}_cloudfront.private_key_pem_pkcs8))}"
            }
        )
    )
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }
    if (signedViaCloudFront) emitter.require(TerraformProviderImport.tls)
    if (domain != null) {
        // CloudFront only accepts certificates issued in us-east-1. Same alias Lightning Server's builders
        // use, so the provider blocks dedupe.
        emitter.require(
            TerraformProvider(TerraformProviderImport.aws, "acm", buildJsonObject { put("region", "us-east-1") })
        )
    }
    emitter.emit(name) {
        "resource.aws_s3_bucket.${name}" {
            // .lowercase().replace("_", "") on projectPrefix is backwards compatibility with V4 and earlier.
            // We can't really remove this at this time.
            "bucket_prefix" - "${emitter.projectPrefix.lowercase().replace("_", "")}-${name.lowercase()}"
            "force_destroy" - forceDestroy
        }
        if (versioning) {
            "resource.aws_s3_bucket_versioning.${name}" {
                "bucket" - expression("aws_s3_bucket.${name}.id")
                "versioning_configuration" {
                    "status" - "Enabled"
                }
            }
        }
        if (noncurrentVersionExpiration != null) {
            "resource.aws_s3_bucket_lifecycle_configuration.${name}" {
                // Lifecycle rules that reference noncurrent versions need versioning to already be
                // enabled, so make the dependency explicit rather than relying on apply ordering.
                "depends_on" - listOf<String>("aws_s3_bucket_versioning.${name}")
                "bucket" - expression("aws_s3_bucket.${name}.id")
                "rule" - listOf(
                    terraformJsonObject {
                        "id" - "expire-noncurrent-versions"
                        "status" - "Enabled"
                        // Empty prefix: applies to every object in the bucket.
                        "filter" {
                            "prefix" - ""
                        }
                        "noncurrent_version_expiration" {
                            "noncurrent_days" - noncurrentVersionExpiration.inWholeDays
                        }
                    }
                )
            }
        }
        if (urlExpiration == null) {
            "resource.aws_s3_bucket_public_access_block.${name}" {
                "bucket" - expression("aws_s3_bucket.${name}.id")

                "block_public_acls" - false
                "block_public_policy" - false
                "ignore_public_acls" - false
                "restrict_public_buckets" - false
            }

            // On a custom domain, emitCloudFront writes the one bucket policy, public read included.
            if (domain == null) {
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
            }
        } else {
            // Private bucket: access is brokered exclusively through signed URLs or CloudFront, so block
            // all public access. (A bucket policy granting only one CloudFront distribution isn't public.)
            "resource.aws_s3_bucket_public_access_block.${name}" {
                "bucket" - expression("aws_s3_bucket.${name}.id")

                "block_public_acls" - true
                "block_public_policy" - true
                "ignore_public_acls" - true
                "restrict_public_buckets" - true
            }
        }
        if (urlExpiration != null) {
            // Encrypt objects at rest.
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
        if (domain != null) emitCloudFront(
            name = name,
            domain = domain,
            zoneId = zoneId!!,
            signed = signedViaCloudFront,
            publicRead = urlExpiration == null,
            corsOrigins = corsOrigins,
            priceClass = cloudFrontPriceClass,
            dedicatedKmsKey = signedViaCloudFront && effectiveKmsKey == KmsKeySource.CreateDedicated,
        )
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

/**
 * Emits everything [awsS3Bucket] needs to serve bucket [name] from [domain] through CloudFront.
 * See [awsS3Bucket]'s "Custom domain" section for what and why.
 */
context(emitter: TerraformEmitterAws)
private fun TerraformJsonObject.emitCloudFront(
    name: String,
    domain: String,
    zoneId: String,
    signed: Boolean,
    publicRead: Boolean,
    corsOrigins: Set<String>,
    priceClass: String,
    dedicatedKmsKey: Boolean,
) {
    // CloudFront resource names allow only letters, digits, hyphens and underscores.
    val awsName = "${emitter.projectPrefix}-$name".replace(Regex("[^A-Za-z0-9_-]"), "-")

    // CloudFront only takes certificates from us-east-1, hence the "acm" provider.
    "resource.aws_acm_certificate.${name}_cloudfront" {
        "provider" - "aws.acm"
        "domain_name" - domain
        "validation_method" - "DNS"
        "lifecycle" {
            "create_before_destroy" - true
        }
    }
    "resource.aws_route53_record.${name}_cloudfront_validation" {
        "zone_id" - zoneId
        "name" - expression("tolist(aws_acm_certificate.${name}_cloudfront.domain_validation_options)[0].resource_record_name")
        "type" - expression("tolist(aws_acm_certificate.${name}_cloudfront.domain_validation_options)[0].resource_record_type")
        "records" - listOf(expression("tolist(aws_acm_certificate.${name}_cloudfront.domain_validation_options)[0].resource_record_value"))
        "ttl" - 300
        "allow_overwrite" - true
    }
    "resource.aws_acm_certificate_validation.${name}_cloudfront" {
        "provider" - "aws.acm"
        "certificate_arn" - expression("aws_acm_certificate.${name}_cloudfront.arn")
        "validation_record_fqdns" - listOf(expression("aws_route53_record.${name}_cloudfront_validation.fqdn"))
    }

    "resource.aws_cloudfront_origin_access_control.${name}" {
        "name" - awsName.take(64)
        "description" - "Lets CloudFront read the $name files bucket"
        "origin_access_control_origin_type" - "s3"
        "signing_behavior" - "always"
        "signing_protocol" - "sigv4"
    }
    // A public bucket also stays readable on the S3 host, so links issued before the domain keep working.
    val publicReadStatement = if (!publicRead) "" else $$"""
                {
                    "Sid": "PublicReadGetObject",
                    "Effect": "Allow",
                    "Principal": "*",
                    "Action": "s3:GetObject",
                    "Resource": "${aws_s3_bucket.$${name}.arn}/*"
                },"""
    "resource.aws_s3_bucket_policy.${name}" {
        "depends_on" - listOf<String>("aws_s3_bucket_public_access_block.${name}")
        "bucket" - expression("aws_s3_bucket.${name}.id")
        "policy" - $$"""
        {
            "Version": "2012-10-17",
            "Statement": [$$publicReadStatement
                {
                    "Sid": "AllowCloudFrontRead",
                    "Effect": "Allow",
                    "Principal": { "Service": "cloudfront.amazonaws.com" },
                    "Action": "s3:GetObject",
                    "Resource": "${aws_s3_bucket.$${name}.arn}/*",
                    "Condition": {
                        "StringEquals": { "AWS:SourceArn": "${aws_cloudfront_distribution.$${name}.arn}" }
                    }
                }
            ]
        }
        """.trimIndent()
    }
    if (dedicatedKmsKey) {
        // The dedicated key's default policy only trusts the account; CloudFront needs its own grant to
        // decrypt objects for viewers. (The key is this bucket's alone, so replacing its policy is safe.)
        "data.aws_caller_identity.${name}_cloudfront" {}
        "resource.aws_kms_key_policy.${name}_cmk" {
            "key_id" - expression("aws_kms_key.${name}_cmk.id")
            "policy" - $$"""
            {
                "Version": "2012-10-17",
                "Statement": [
                    {
                        "Sid": "EnableAccountPermissions",
                        "Effect": "Allow",
                        "Principal": { "AWS": "arn:aws:iam::${data.aws_caller_identity.$${name}_cloudfront.account_id}:root" },
                        "Action": "kms:*",
                        "Resource": "*"
                    },
                    {
                        "Sid": "AllowCloudFrontDecrypt",
                        "Effect": "Allow",
                        "Principal": { "Service": "cloudfront.amazonaws.com" },
                        "Action": "kms:Decrypt",
                        "Resource": "*",
                        "Condition": {
                            "StringEquals": { "AWS:SourceArn": "${aws_cloudfront_distribution.$${name}.arn}" }
                        }
                    }
                ]
            }
            """.trimIndent()
        }
    }

    "data.aws_cloudfront_cache_policy.${name}" {
        "name" - "Managed-CachingOptimized"
    }
    // Forwards Origin and the preflight headers, so S3's own CORS rules can answer OPTIONS requests.
    "data.aws_cloudfront_origin_request_policy.${name}" {
        "name" - "Managed-CORS-S3Origin"
    }
    // Origin isn't part of the cache key, so a cached S3 CORS header could name some other origin;
    // CloudFront sets the CORS headers per request instead.
    "resource.aws_cloudfront_response_headers_policy.${name}" {
        "name" - "$awsName-cors".take(128)
        "cors_config" {
            "access_control_allow_credentials" - false
            "access_control_allow_headers" {
                "items" - listOf("*")
            }
            "access_control_allow_methods" {
                "items" - listOf("GET", "HEAD", "OPTIONS")
            }
            "access_control_allow_origins" {
                "items" - corsOrigins.sorted()
            }
            "access_control_max_age_sec" - 3000
            "origin_override" - true
        }
    }

    if (signed) {
        "resource.tls_private_key.${name}_cloudfront" {
            "algorithm" - "RSA"
            "rsa_bits" - 2048
        }
        "resource.aws_cloudfront_public_key.${name}" {
            // A prefix (not a fixed name) so a replacement key can exist alongside the old one.
            "name_prefix" - "${awsName.take(100)}-"
            "comment" - "Signs URLs for the $name files"
            "encoded_key" - expression("tls_private_key.${name}_cloudfront.public_key_pem")
            "lifecycle" {
                "create_before_destroy" - true
            }
        }
        "resource.aws_cloudfront_key_group.${name}" {
            "name" - awsName.take(128)
            "comment" - "Keys trusted to sign URLs for the $name files"
            "items" - listOf(expression("aws_cloudfront_public_key.${name}.id"))
        }
    }

    "resource.aws_cloudfront_distribution.${name}" {
        "enabled" - true
        "is_ipv6_enabled" - true
        "http_version" - "http2and3"
        "comment" - "$awsName files".take(128)
        "aliases" - listOf(domain)
        "price_class" - priceClass
        "origin" {
            "origin_id" - "s3"
            "domain_name" - expression("aws_s3_bucket.${name}.bucket_regional_domain_name")
            "origin_access_control_id" - expression("aws_cloudfront_origin_access_control.${name}.id")
        }
        "default_cache_behavior" {
            "target_origin_id" - "s3"
            "viewer_protocol_policy" - "redirect-to-https"
            "allowed_methods" - listOf("GET", "HEAD", "OPTIONS")
            "cached_methods" - listOf("GET", "HEAD")
            "compress" - true
            "cache_policy_id" - expression("data.aws_cloudfront_cache_policy.${name}.id")
            "origin_request_policy_id" - expression("data.aws_cloudfront_origin_request_policy.${name}.id")
            "response_headers_policy_id" - expression("aws_cloudfront_response_headers_policy.${name}.id")
            // Only URLs signed with a key in this group are served.
            if (signed) "trusted_key_groups" - listOf(expression("aws_cloudfront_key_group.${name}.id"))
        }
        "restrictions" {
            "geo_restriction" {
                "restriction_type" - "none"
            }
        }
        "viewer_certificate" {
            "acm_certificate_arn" - expression("aws_acm_certificate_validation.${name}_cloudfront.certificate_arn")
            "ssl_support_method" - "sni-only"
            "minimum_protocol_version" - "TLSv1.2_2021"
        }
    }
    for (type in listOf("A", "AAAA")) {
        "resource.aws_route53_record.${name}_cloudfront_${type.lowercase()}" {
            "zone_id" - zoneId
            "name" - domain
            "type" - type
            "alias" {
                "name" - expression("aws_cloudfront_distribution.${name}.domain_name")
                "zone_id" - expression("aws_cloudfront_distribution.${name}.hosted_zone_id")
                "evaluate_target_health" - false
            }
        }
    }
}

/*
 * TODO: API Recommendations for tf.kt
 *
 * 1. Security: The IAM policy grants s3:* (all S3 actions) on the bucket. Consider making this more granular
 *    with specific actions like s3:GetObject, s3:PutObject, s3:DeleteObject, s3:ListBucket for better security
 *    following the principle of least privilege.
 *
 * 4. Bucket Lifecycle: noncurrentVersionExpirationDays only covers noncurrent-version cleanup.
 *    Still no way to transition current objects to cheaper storage classes (e.g., Glacier).
 *
 * 6. Encryption: Consider adding server-side encryption configuration (SSE-S3 or SSE-KMS) as a parameter
 *    for security compliance.

 */