package com.lightningkite.services.files

import com.lightningkite.MediaType
import com.lightningkite.services.data.TypedData
import dev.whyoleg.cryptography.algorithms.HMAC
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.annotations.TestOnly
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A KSerializer for ServerFile that provides secure file upload handling with scanning and validation.
 *
 * This serializer handles three types of file references during deserialization:
 * 1. `future:` URLs - Files uploaded to a jail directory that need scanning before use
 * 2. `future-prescanned:` URLs - Files already scanned and ready for use
 * 3. `data:` URLs - Base64-encoded inline data that will be uploaded and scanned
 * 4. Regular URLs - Files from known file systems that will be validated
 *
 * During serialization, it converts internal file URLs to signed URLs for secure client access.
 *
 * @param clock Clock for timestamp validation
 * @param scanners List of file scanners to run on uploaded files
 * @param fileSystems List of file systems to check for file ownership
 * @param jail Directory for uploaded files awaiting scanning (quarantine area)
 * @param ready Directory for scanned and approved files
 * @param onUse Callback invoked when a file is used
 * @param key HMAC key for signing "future:" URLs
 */
@OptIn(SealedSerializationApi::class)
public class ExternalServerFileSerializer(
    public val clock: Clock,
    public val scanners: List<FileScanner>,
    public val fileSystems: List<PublicFileSystem>,
    public val jail: FileObject = fileSystems.first().root.then("upload-jail"),
    public val ready: FileObject = fileSystems.first().root.then("uploaded"),
    public val onUse: (FileObject) -> Unit,
    public val key: HMAC.Key
) : KSerializer<ServerFile> {
    private val primary = fileSystems.first()
    private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.files.ExternalServerFileSerializer")

    private val uploadFile: suspend (data: TypedData) -> FileObject = {
        scanners.scan(it)
        val d = primary.root.thenRandom("uploaded", "file")
        d.put(it)
        d
    }

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = object : SerialDescriptor {
        override val kind: SerialKind = PrimitiveKind.STRING
        override val serialName: String = "com.lightningkite.services.files.ServerFile"
        override val elementsCount: Int get() = 0
        override fun getElementName(index: Int): String = error()
        override fun getElementIndex(name: String): Int = error()
        override fun isElementOptional(index: Int): Boolean = error()
        override fun getElementDescriptor(index: Int): SerialDescriptor = error()
        override fun getElementAnnotations(index: Int): List<Annotation> = error()
        override fun toString(): String = "PrimitiveDescriptor($serialName)"
        private fun error(): Nothing = throw IllegalStateException("Primitive descriptor does not have elements")
        override val annotations: List<Annotation> = listOf()
    }

    /**
     * Serializes a ServerFile to a signed URL for client consumption.
     *
     * If the file's location matches one of the known file systems, it's converted to a signed URL.
     * Otherwise, the original URL is used with a warning (potential security risk).
     */
    // TODO: Is this dangerous? If someone could inject a foreign url, this allows them to direct users to malware
    override fun serialize(encoder: Encoder, value: ServerFile) {
        val url = value.location
        val file = fileSystems.firstNotNullOfOrNull {
            // We don't need to check signatures; this is coming from us, after all.
            it.parseInternalUrl(url)
        }
        if (file == null) {
            // TODO: Is this dangerous?  If someone could inject a foreign url, this allows them to direct users to malware
            logger.warn {
                "The given url (${value.location}) does not start with any files root. Known roots: ${
                    fileSystems.flatMap { it.rootUrls }.joinToString()
                }"
            }
            encoder.encodeString(value.location)
        } else {
            encoder.encodeString(file.signedUrl)
        }
    }

    /**
     * Creates a "future:" URL for a file in the jail directory.
     *
     * This URL can be used to reference a file that has been uploaded but not yet scanned.
     * The URL includes an expiration and signature.
     *
     * @param jailPath The relative path within the jail directory
     * @param expiration How long the URL should remain valid
     * @return A signed "future:" URL
     */
    public fun certifyForUse(jailPath: String, expiration: Duration): String =
        signUrl("future:$jailPath", expiration)

    /**
     * Creates a "future-prescanned:" URL for a file in the ready directory.
     *
     * This URL references a file that has already been scanned and approved.
     *
     * @param readyPath The relative path within the ready directory
     * @param expiration How long the URL should remain valid
     * @return A signed "future-prescanned:" URL
     */
    public fun certifyAlreadyScannedForUse(readyPath: String, expiration: Duration): String =
        signUrl("future-prescanned:$readyPath", expiration)

    /**
     * Scans a file from the jail directory and moves it to the ready directory.
     *
     * @param value A "future:" URL referencing the file to scan
     * @param expiration How long the resulting "future-prescanned:" URL should remain valid
     * @return A "future-prescanned:" URL for the scanned file
     * @throws IllegalArgumentException if the URL is invalid or has wrong scheme
     * @throws FileScanException if scanning fails
     */
    public suspend fun scan(value: String, expiration: Duration): String {
        val raw = value
        if (raw.startsWith("future-prescanned:")) return value
        if (!raw.startsWith("future:")) throw IllegalArgumentException("URL scheme is not 'future'")
        if (!verifyUrl(raw)) throw IllegalArgumentException("URL is not valid")
        val withoutFuture = raw.substringAfter("future:").substringBefore('?')
        val safe = ready.then(withoutFuture)
        val source = jail.then(withoutFuture)
        scanners.copyAndScan(source, safe)
        return certifyAlreadyScannedForUse(withoutFuture, expiration)
    }

    /**
     * Deserializes a string into a ServerFile, handling various URL schemes securely.
     *
     * Supports four input formats:
     * 1. `future:` - Scans the file from jail and moves to ready directory
     * 2. `future-prescanned:` - Uses already-scanned file from ready directory
     * 3. `data:` - Decodes base64 data, scans it, and uploads
     * 4. Regular URLs - Validates against known file systems
     *
     * All URLs are verified with signatures to prevent unauthorized file access.
     *
     * @param decoder The decoder containing the serialized string
     * @return A ServerFile with an internal URL
     * @throws IllegalArgumentException if URL validation fails or URL doesn't match known file systems
     */
    override fun deserialize(decoder: Decoder): ServerFile {
        val raw = decoder.decodeString()
        when {
            raw.startsWith("future:") -> {
                if (!verifyUrl(raw)) throw IllegalArgumentException("URL is not valid")
                val source = jail.then(raw.substringAfter("future:").substringBefore('?'))
                val safe = ready.then(raw.substringAfter("future:").substringBefore('?'))
                runBlocking { scanners.copyAndScan(source, safe) }
                return ServerFile(safe.url)
            }

            raw.startsWith("future-prescanned:") -> {
                if (!verifyUrl(raw)) throw IllegalArgumentException("URL is not valid")
                val safe = ready.then(raw.substringAfter("future-prescanned:").substringBefore('?'))
                onUse(safe)
                return ServerFile(safe.url)
            }

            raw.startsWith("data:") -> {
                val type = MediaType(raw.removePrefix("data:").substringBefore(';'))
                val base64 = raw.substringAfter("base64,")
                val data = Base64.decode(base64)
                //            if(data.size < 500) return ServerFile(raw)
                return runBlocking {
                    val typedData = TypedData.bytes(data, type)
                    scanners.scan(typedData)
                    uploadFile(typedData)
                }.let { ServerFile(it.url) }
            }

            else -> {
                val file = fileSystems.firstNotNullOfOrNull {
                    it.parseExternalUrl(raw)
                } ?: throw IllegalArgumentException(
                    "The given url (${raw.substringBefore('?')}) does not start with any files root.  Known roots: ${
                        fileSystems.flatMap { it.rootUrls }.joinToString()
                    }"
                )
                return ServerFile(file.url)
            }
        }
    }

    /**
     * Signs a URL with expiration timestamp.
     *
     * @param url The URL to sign (must not contain query parameters)
     * @param expiration How long the URL should remain valid
     * @return The signed URL with useUntil and token parameters
     * @throws IllegalArgumentException if the URL already contains query parameters
     */
    @TestOnly
    internal fun signUrl(url: String, expiration: Duration): String {
        if(url.contains('?')) throw IllegalArgumentException("URL cannot contain query parameters.")
        return url.plus("?useUntil=${clock.now().plus(expiration).toEpochMilliseconds()}").let {
            it + "&token=" + key.signatureGenerator().generateSignatureBlocking(it.toByteArray())
                .let { Base64.UrlSafe.encode(it) }
        }
    }

    /**
     * Verifies a signed URL by checking its signature and expiration.
     *
     * @param url The signed URL to verify
     * @return true if signature is valid and URL hasn't expired, false otherwise
     * @throws IllegalArgumentException if required parameters are missing
     */
    @TestOnly
    internal fun verifyUrl(url: String): Boolean {
        val params = url.substringAfter('?')
            .split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return verifyUrl(
            url.substringBefore('?'),
            params["useUntil"]?.toLong() ?: throw IllegalArgumentException("Parameter 'useUntil' is missing in '$url'"),
            params["token"] ?: throw IllegalArgumentException("Parameter 'token' is missing in '$url'")
        )
    }

    /**
     * Verifies a signed URL with explicit parameters.
     *
     * @param url The base URL (without query parameters)
     * @param exp The expiration timestamp in epoch milliseconds
     * @param token The URL-safe base64 encoded signature token
     * @return true if signature is valid and URL hasn't expired, false otherwise
     */
    @TestOnly
    internal fun verifyUrl(url: String, exp: Long, token: String): Boolean {
        return (Instant.fromEpochMilliseconds(exp) > clock.now()) && key.signatureVerifier().tryVerifySignatureBlocking(
            data = (url.substringBefore('?') + "?useUntil=$exp").toByteArray(),
            signature = Base64.UrlSafe.decode(token)
        )
    }
}

/*
 * TODO: API Recommendations
 *
 * 1. The serialize() method has a security concern - it allows unknown URLs to pass through
 *    with only a warning. Consider:
 *    - Making this behavior configurable (strict vs permissive mode)
 *    - Throwing an exception in strict mode
 *    - Providing a whitelist of allowed external domains
 *
 * 2. deserialize() uses runBlocking which can block threads. Consider:
 *    - Making this an async serializer if the serialization framework supports it
 *    - Documenting the blocking behavior
 *    - Providing configuration for timeouts
 *
 * 3. The "jail" and "ready" directory pattern is powerful but not documented for users.
 *    Consider adding comprehensive documentation about:
 *    - The upload workflow (client -> jail -> scan -> ready)
 *    - How to integrate with upload endpoints
 *    - Cleanup strategies for failed uploads
 *
 * 4. Error handling could be more granular. Consider different exception types for:
 *    - Signature validation failures
 *    - Expiration failures
 *    - Scanning failures
 *    - Unknown file system failures
 *
 * 5. The onUse callback is called only for future-prescanned URLs. Consider:
 *    - Documenting when and why this is called
 *    - Calling it for other URL types if appropriate
 *    - Making callback exceptions not break deserialization
 */
