package com.lightningkite.services.files

import com.lightningkite.services.data.*
import com.lightningkite.services.database.PrimitiveDescriptorWithAnnotations
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Controls how [ExternalServerFileSerializer.serialize] handles a [ServerFile] whose location does
 * not belong to any known file-system root (a "foreign" URL).
 *
 * Foreign URLs are a security concern: if untrusted input can set a file's location, passing the URL
 * through verbatim lets an attacker direct users to arbitrary external content (open redirect /
 * malware distribution).
 */
public enum class ForeignUrlHandling {
    /** Log a warning and emit the original foreign URL unchanged (the legacy pass-through behavior). */
    WARN,

    /** Emit a blank string `""` in place of the foreign URL. */
    CENSOR,

    /** Throw an [IllegalArgumentException] rejecting the foreign URL. */
    ERROR,
}

/**
 * A [KSerializer] for [ServerFile] that translates between stored file references and the signed URLs
 * clients see.
 *
 * This type does one job - translation. It runs no scan, copy, or upload of its own; the upload
 * workflow that produces safe files lives in the server framework's upload endpoint, which supplies
 * [resolveUpload].
 *
 * It is not free of I/O, though, and [deserialize] runs on whatever thread is deserializing (often a
 * server event loop): [resolveUpload] and [ExternalFileSystem.parseExternalUrl] are both free to
 * block there, and today both do - the upload endpoint's hook deletes a database row, and the S3
 * backend falls back to a network round trip for a signature it cannot recompute locally.
 *
 * **Serializing** converts a stored location into a signed URL, subject to [foreignUrlHandling].
 *
 * **Deserializing** accepts either a reference the upload endpoint issued (resolved by
 * [resolveUpload]) or a signed URL belonging to one of [fileSystems]. Anything else is rejected.
 *
 * @param fileSystems The file systems whose files this serializer will resolve and sign
 * @param foreignUrlHandling What [serialize] does with a location belonging to no known file system
 * @param resolveUpload Resolves a reference issued by the upload endpoint
 */
public class ExternalServerFileSerializer(
    public val fileSystems: List<ExternalFileSystem>,
    public val foreignUrlHandling: ForeignUrlHandling = ForeignUrlHandling.ERROR,
    /**
     * Resolves a reference issued by the server's upload endpoint into the file it names.
     *
     * - Returns `null` when the string is not one of the endpoint's references at all, so that
     *   deserialization falls through to the signed-URL handling for [fileSystems].
     * - Throws when the string *is* one of them but must not be honored - forged, expired, or naming a
     *   file that has not passed scanning yet.
     *
     * The default resolves nothing, which is correct for a server with no upload endpoint mounted.
     * Because this serializer is the only thing that turns client input into a [ServerFile], an
     * implementation that returns a file for an unscanned upload defeats file scanning entirely.
     */
    public val resolveUpload: (String) -> ExternalFile? = { null },
) : KSerializer<ServerFile> {
    private val logger = KotlinLogging.logger("com.lightningkite.services.files.ExternalServerFileSerializer")

    /** Resolves stored locations: canonical `sf://` references, or legacy absolute URLs. */
    private val storedReferences = ExternalFile.Parser(fileSystems)
    private val knownSystemsString: String get() = fileSystems.joinToString { it.name }

    @OptIn(ExperimentalSerializationApi::class, ExperimentalLightningServer::class)
    override val descriptor: SerialDescriptor = PrimitiveDescriptorWithAnnotations(
        serialName = "com.lightningkite.services.files.ServerFile/ExternalServerFileSerializer",
        kind = PrimitiveKind.STRING,
        annotations = listOf(
            Description("A URL for a remote URL, signed.")
        )
    )

    /**
     * Serializes a ServerFile to a signed URL for client consumption.
     *
     * If the file's location matches one of the known file systems, it's converted to a signed URL.
     * Otherwise, the foreign URL is handled according to [foreignUrlHandling] (default: rejected).
     */
    override fun serialize(encoder: Encoder, value: ServerFile) {
        // We don't need to check signatures; this is coming from us, after all.
        val file = storedReferences.parseOrNull(value.location)
        if (file == null) {
            when (foreignUrlHandling) {
                ForeignUrlHandling.WARN -> {
                    logger.warn {
                        "The given url (${value.location}) belongs to no known file system. Known file systems: $knownSystemsString"
                    }
                    encoder.encodeString(value.location)
                }

                ForeignUrlHandling.CENSOR -> encoder.encodeString("")

                ForeignUrlHandling.ERROR -> throw IllegalArgumentException(
                    "Refusing to serialize foreign url (${value.location}); it belongs to no known file system. Known file systems: $knownSystemsString"
                )
            }
        } else {
            encoder.encodeString(file.signedUrl)
        }
    }

    /**
     * Deserializes a client-supplied string into a [ServerFile].
     *
     * Accepts a reference issued by the upload endpoint, or a URL belonging to one of [fileSystems].
     *
     * Whether that is enough to stop a client naming a file it was never given is the file system's
     * call, not this class's: [ExternalFileSystem.parseExternalUrl] checks a signature only where the
     * backend has signing configured. A backend with signing off accepts any path under its serve
     * URL, which defeats [resolveUpload] - a client that knows a path can name it directly rather
     * than going through whatever the hook enforces.
     *
     * @throws IllegalArgumentException if the string is not an accepted form, or is one whose
     * signature, expiration, or scan state makes it unusable
     */
    override fun deserialize(decoder: Decoder): ServerFile {
        val raw = decoder.decodeString()

        resolveUpload(raw)?.let { return it.serverFile }

        // Storing an inline data URL means uploading it, which is I/O this serializer deliberately
        // does not do. Called out separately so the client gets an actionable error rather than the
        // generic "belongs to no known file system" below.
        if (raw.startsWith("data:")) throw IllegalArgumentException(
            "Inline 'data:' URLs are not accepted. Upload the file via the dedicated upload endpoint and submit the token it returns instead."
        )

        val file = fileSystems.firstNotNullOfOrNull { it.parseExternalUrl(raw) }
            ?: throw IllegalArgumentException(
                "The given url (${raw.substringBefore('?')}) belongs to no known file system. Known file systems: $knownSystemsString"
            )
        return file.serverFile
    }
}
