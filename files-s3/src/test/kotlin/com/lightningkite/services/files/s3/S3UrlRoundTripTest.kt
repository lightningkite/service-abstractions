package com.lightningkite.services.files.s3

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.files.ExternalFile
import com.lightningkite.services.files.serverFile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Backward-compatibility guard for S3 URLs already stored in a database by the PRE-redesign
 * version of this library (before [FileObject] was folded into [S3ExternalFileSystem] / [ExternalPath]).
 *
 * ## What's actually stored
 *
 * A `ServerFile` persists its `location` verbatim, and that location is the *unsigned internal* URL
 * produced by `url(path)` — `https://{bucket}.s3.{region}.amazonaws.com/{key}` with the object key
 * written **literally, without percent-encoding**. When such a record is later read and serialized
 * out to a client, `ExternalServerFileSerializer.serialize` calls [S3ExternalFileSystem.parseLegacyUrl]
 * on that stored string. So the invariant that protects existing data is:
 *
 * > Parsing the exact URL the old version stored must resolve to the exact same S3 object key.
 *
 * These tests pin that invariant for the two characters whose handling changed in the redesign:
 * `+` and the percent-escape introducer `%`. They deliberately exercise both the unsigned
 * ("internal", what the DB holds) and signed ("external", what a client submits) round-trips.
 *
 * The keys here contain no `/`, `.`/`..`, or empty segments, so [ExternalPath] construction is not
 * itself the thing under test — only URL encode/decode fidelity is.
 */
class S3UrlRoundTripTest {

    init {
        // Ensure the "s3" scheme registration side effect has run; the bare reference
        // is the point of this line.
        @Suppress("UNUSED_EXPRESSION")
        S3ExternalFileSystem
    }

    private fun system(): S3ExternalFileSystem = system(signedUrlDuration = 1.hours)

    private fun system(signedUrlDuration: kotlin.time.Duration?): S3ExternalFileSystem = S3ExternalFileSystem(
        name = "test",
        region = Region.US_EAST_1,
        credentialProvider = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("AKIAEXAMPLEKEYID0000", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        ),
        bucket = "example-bucket",
        signedUrlDuration = signedUrlDuration,
        context = TestSettingContext(),
    )

    /**
     * Object keys that stress `+` and `%` handling. A single path segment each (no `/`).
     */
    private val edgeCaseKeys = listOf(
        "hello world.txt",      // literal space (invalid in a URL, but this is what the old url() wrote)
        "a+b.txt",              // a single literal plus
        "one+two+three.txt",    // several literal pluses
        "file%20name.txt",      // a literal percent sequence that *looks* like an escape for a space
        "100%done.txt",         // a literal percent NOT followed by valid hex digits
        "50%off%20sale.txt",    // mixed literal percents
        "café.txt",        // non-ascii
        "a=b&c.txt",            // reserved characters
    )

    /**
     * The literal string the old version wrote into the database for [key]: the bucket URL with the
     * object key appended verbatim, no percent-encoding. Spelled out here rather than taken from the
     * current code so the test keeps pinning the historical format even as the code moves on.
     */
    private fun legacyStoredUrl(key: String): String =
        "https://example-bucket.s3.us-east-1.amazonaws.com/$key"

    /**
     * The unsigned URL is exactly what the previous version persisted to the database. Re-parsing it
     * with [S3ExternalFileSystem.parseLegacyUrl] must land on the original object for every key.
     *
     * This is the case that regressed once: percent-decoding the stored path makes a key literally
     * containing `%20` decode to a space (wrong object), and a key with a bare `%` (invalid escape)
     * throw instead of resolving.
     */
    @Test
    fun storedUnsignedUrlResolvesToSameObject() {
        val system = system()
        val failures = edgeCaseKeys.mapNotNull { key ->
            val file = system.root.then(key)
            val stored = legacyStoredUrl(key)

            val parsed = runCatching { system.parseLegacyUrl(stored) }
            when {
                parsed.isFailure ->
                    "key='$key': parseLegacyUrl threw ${parsed.exceptionOrNull()} for stored url '$stored'"
                parsed.getOrNull() != file ->
                    "key='$key': stored url '$stored' resolved to key='${parsed.getOrNull()?.path}', expected key='${file.path}'"
                else -> null
            }
        }
        assertTrue(
            failures.isEmpty(),
            "Stored (unsigned) S3 URLs must resolve to the original object:\n" + failures.joinToString("\n")
        )
    }

    /**
     * Signed URLs are percent-encoded by [signUrl], and a client submits them back through
     * [parseExternalUrl]. Decoding here is correct and expected; this test guards that the encode →
     * decode round-trip is faithful for the same edge-case keys.
     */
    @Test
    fun signedUrlResolvesToSameObject() {
        val system = system()
        val failures = edgeCaseKeys.mapNotNull { key ->
            val file = system.root.then(key)
            val signed = file.signedUrl

            val parsed = runCatching { system.parseExternalUrl(signed) }
            when {
                parsed.isFailure ->
                    "key='$key': parseExternalUrl threw ${parsed.exceptionOrNull()} for signed url '$signed'"
                parsed.getOrNull() != file ->
                    "key='$key': signed url resolved to key='${parsed.getOrNull()?.path}', expected key='${file.path}'"
                else -> null
            }
        }
        assertTrue(
            failures.isEmpty(),
            "Signed S3 URLs must round-trip back to the original object:\n" + failures.joinToString("\n")
        )
    }

    /**
     * Focused check that `+` is preserved (NOT turned into a space) on the stored-URL path, matching
     * the fact that `decodeURLPart` treats `+` literally for URL *path* segments. A key `a+b.txt`
     * must resolve to `a+b.txt`, never `a b.txt`.
     */
    @Test
    fun plusInStoredUrlIsPreservedNotSpaced() {
        val system = system()
        val file = system.root.then("a+b.txt")
        val stored = legacyStoredUrl("a+b.txt")

        assertEquals(
            file,
            system.parseLegacyUrl(stored),
            "A '+' in a stored object key must stay a '+', not decode to a space"
        )
    }

    /**
     * The canonical `sf://<name>/<key>` form (what new rows persist) must round-trip back to the
     * same object for the same edge-case keys. Canonical escapes the key conservatively, so no
     * `%`/`+` mangling should occur.
     */
    @Test
    fun canonicalFormRoundTripsToSameObject() {
        val system = system()
        val parser = ExternalFile.Parser(listOf(system))
        val failures = edgeCaseKeys.mapNotNull { key ->
            val file = system.root.then(key)
            val canonical = file.serverFile.location
            val parsed = runCatching { parser.parse(canonical) }
            when {
                !canonical.startsWith("sf://") ->
                    "key='$key': persisted form was not canonical: '$canonical'"
                parsed.isFailure ->
                    "key='$key': parse threw ${parsed.exceptionOrNull()} for canonical '$canonical'"
                parsed.getOrNull() != file ->
                    "key='$key': canonical '$canonical' resolved to key='${parsed.getOrNull()?.path}', expected key='${file.path}'"
                else -> null
            }
        }
        assertTrue(
            failures.isEmpty(),
            "Canonical sf:// references must resolve to the original object:\n" + failures.joinToString("\n")
        )
    }

    /**
     * Security boundary: a canonical `sf://` reference is server-internal and unsigned. It must NOT
     * be accepted through [parseExternalUrl] (the untrusted client-input path), or a client could
     * reference arbitrary keys with no signature. Rejection may be either a null result or a thrown
     * signature failure; the essential guarantee is that it never resolves to a usable file.
     */
    @Test
    fun parseExternalUrlRejectsCanonicalReference() {
        // Use an UNSIGNED system: with signing on, the signature check would reject these anyway and
        // mask whether the guard itself works. Unsigned makes the sf:// guard the deciding control.
        val system = system(signedUrlDuration = null)
        val canonical = system.root.then("someones/private/file.txt").serverFile.location
        val result = runCatching { system.parseExternalUrl(canonical) }
        assertTrue(
            result.isFailure || result.getOrNull() == null,
            "parseExternalUrl must not accept a canonical sf:// reference, but returned ${result.getOrNull()}"
        )
    }

    /**
     * The `sf://` guard must resist percent-encoding: `parseExternalUrl` decodes before delegating,
     * so a raw `sf%3A//...` would otherwise decode to `sf://...` and slip past a raw-string guard.
     * Exercised on an unsigned system so the guard - not the signature check - is what rejects it.
     */
    @Test
    fun parseExternalUrlRejectsPercentEncodedCanonicalReference() {
        val system = system(signedUrlDuration = null)
        // system names the file system "test", so its canonical prefix is sf://test/.
        val encoded = "sf%3A//test/someones/private/file.txt"
        val result = runCatching { system.parseExternalUrl(encoded) }
        assertTrue(
            result.isFailure || result.getOrNull() == null,
            "parseExternalUrl must not accept an encoded sf%3A// reference, but returned ${result.getOrNull()}"
        )
    }
}
