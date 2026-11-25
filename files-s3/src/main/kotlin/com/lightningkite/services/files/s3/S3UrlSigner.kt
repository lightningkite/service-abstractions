package com.lightningkite.services.files.s3

import java.time.Instant
import kotlin.time.Duration

/**
 * Signs time-limited URLs for objects in one bucket, and verifies the signed URLs clients send back.
 *
 * Methods take the object key (unix-style, not percent-encoded); the signer builds the URL itself,
 * since a signature only works on the host it was made for.
 *
 * - [S3PresignedUrlSigner] signs everything as S3 presigned URLs on the bucket's S3 host.
 * - [CloudFrontUrlSigner] wraps another signer and serves downloads from a custom domain through
 *   CloudFront, leaving uploads to the wrapped signer.
 */
public interface S3UrlSigner {
    /**
     * Where files are fetched from without a signature, as `https://<host>/`: the S3 host, or the custom
     * domain in front of it. Unsigned URLs only work if the bucket (or distribution) allows public reads.
     */
    public val publicRoot: String

    /** Returns a URL for reading [key], valid for [duration]. */
    public fun signDownload(key: String, duration: Duration): String

    /** Returns a URL for uploading [key] with a PUT, valid for [duration]. */
    public fun signUpload(key: String, duration: Duration): String

    /**
     * Checks that [queryParams] (the part of a URL after `?`) are a signature this signer made for
     * downloading [key] that hasn't expired by [now].
     *
     * @throws IllegalArgumentException if the signature is missing, invalid, or expired
     */
    public fun verify(key: String, queryParams: String, now: Instant = Instant.now())
}

/** Whether any `&`-separated parameter in [queryParams] is named [name]. */
internal fun hasQueryParameter(queryParams: String, name: String): Boolean =
    queryParams.split('&').any { it.substringBefore('=') == name }
