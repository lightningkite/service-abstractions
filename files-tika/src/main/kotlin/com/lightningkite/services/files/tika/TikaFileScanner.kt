package com.lightningkite.services.files.tika

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.files.ExternalFile
import com.lightningkite.services.files.FileScanException
import com.lightningkite.services.files.FileScanner
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.telemetryTrace
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.tika.metadata.Metadata
import org.apache.tika.mime.MimeTypes
import java.io.ByteArrayInputStream
import org.apache.tika.mime.MediaType as TikaMediaType

/**
 * A [FileScanner] that checks that a file's content matches its claimed media type, using the magic-number
 * database bundled with Apache Tika (`tika-core`).
 *
 * Tika's database covers well over a thousand types and records how they relate (docx is a kind of zip,
 * SVG is a kind of XML, JSON is a kind of plain text, ...). This makes it a far more complete replacement
 * for [com.lightningkite.services.files.CheckMimeFileScanner], with no native libraries or external
 * processes required.
 *
 * ## Supported URL Schemes
 *
 * - `tika://` - Use the defaults
 * - `tika://?bytes=65536` - Override how many leading bytes are read for detection
 *
 * ```kotlin
 * FileScanner.Settings("tika://")
 * FileScanner.Settings.Companion.tika(maxBytes = 65_536)
 * ```
 *
 * ## How a file is judged
 *
 * The scanner detects a type from the file's leading bytes alone (the claimed type and file name are never
 * given to Tika as hints), then compares it to the claimed type:
 *
 * - **Pass** when the detected type is the claimed type or a more specific kind of it (claimed `application/zip`,
 *   detected docx; claimed `application/octet-stream`, detected anything).
 * - **Pass** when the claimed type is a more specific kind of the detected type, because many formats can't be
 *   told apart from their first bytes (claimed docx, detected zip; claimed `text/csv`, detected `text/plain`;
 *   claimed `video/mp4`, detected `video/quicktime`). The exception is when Tika recognized nothing at all
 *   (`application/octet-stream`) but the claimed type, or one of its parent types, has a signature Tika knows.
 *   For example, a claimed PNG that doesn't start with the PNG signature fails.
 * - **Fail** when the two types are unrelated (claimed `image/jpeg`, detected `image/png`).
 *
 * ## Unknown types
 *
 * When the claimed type is one Tika doesn't know (custom types like `application/x-protobuf-text`, most `+json`
 * types, wildcards), there's nothing to check the content against. What happens is configurable:
 *
 * - [allowUnknownTypes] `true` (the default): every unknown type passes.
 * - [allowUnknownTypes] `false`: unknown types fail, except those matched by [allowedUnknownTypes]. An entry's
 *   type or subtype may be `*` to match anything in that position.
 *
 * ```kotlin
 * // Block unknown types, except BSON and protobuf text
 * FileScanner.Settings("tika://?unknownTypes=block&allowedUnknownTypes=application/bson,application/x-protobuf-text")
 * FileScanner.Settings.Companion.tika(
 *     allowUnknownTypes = false,
 *     allowedUnknownTypes = setOf(MediaType("application/bson"), MediaType("application/x-protobuf-text")),
 * )
 * ```
 *
 * URL parameters aren't percent-decoded, so write `+` and `/` literally (`application/hal+json`).
 *
 * Text types (anything Tika considers a kind of `text/plain`) never require a signature: text formats rarely
 * have one, and Tika reports most of them as `text/plain`.
 *
 * ## Gotchas
 *
 * - **Range read**: only the first [maxBytes] bytes are fetched, so large objects aren't downloaded in full.
 *   The default is the longest offset any signature in Tika's database needs (64 KiB).
 * - **Containers**: Office Open XML files are zips, so their exact type depends on the order of entries in the
 *   archive. A docx whose first entry isn't `[Content_Types].xml` is detected as a plain zip and still passes,
 *   but so would any other zip claimed as docx.
 * - **Not a malware scanner**: this only checks that the file is the type it claims to be. Combine it with
 *   a scanner such as ClamAV (see [com.lightningkite.services.files.scan] for running several in parallel).
 *
 * @property maxBytes How many leading bytes of the file to read for detection.
 * @property allowUnknownTypes Whether files claiming a type Tika doesn't know pass without being checked.
 * @property allowedUnknownTypes Unknown types that still pass when [allowUnknownTypes] is `false`. Parameters
 * are ignored, and `*` works as a wildcard for the type or subtype. It has no effect when [allowUnknownTypes] is
 * `true`, and a warning is logged if it's set anyway.
 */
public class TikaFileScanner(
    override val name: String,
    override val context: SettingContext,
    public val maxBytes: Int = mimeTypes.minLength,
    public val allowUnknownTypes: Boolean = true,
    public val allowedUnknownTypes: Set<MediaType> = emptySet(),
) : FileScanner {

    init {
        require(maxBytes > 0) { "maxBytes must be positive, but was $maxBytes" }
        if (allowUnknownTypes && allowedUnknownTypes.isNotEmpty()) {
            logger.warn {
                "FileScanner '$name': allowedUnknownTypes is ignored because unknown types are already allowed " +
                    "(allowUnknownTypes = true); it only applies when unknown types are blocked"
            }
        }
    }

    private val allowedUnknownPatterns: List<MediaType> =
        allowedUnknownTypes.map { MediaType(it.type.lowercase(), it.subtype.lowercase()) }

    /**
     * Returns null when [bytes] are consistent with the [claimed] type, or a description of the mismatch.
     */
    internal fun mismatch(claimed: MediaType, bytes: ByteArray): String? {
        val claimedType = canonical(claimed) ?: return unknownTypeMismatch(claimed)
        val detected = mimeTypes.detect(ByteArrayInputStream(bytes), Metadata()).baseType

        if (registry.isInstanceOf(detected, claimedType)) return null
        if (registry.isInstanceOf(claimedType, detected)) {
            if (detected != TikaMediaType.OCTET_STREAM || !needsSignature(claimedType)) return null
            return "content doesn't have the ${claimed.subtype} signature"
        }
        return "content was detected as $detected"
    }

    private fun unknownTypeMismatch(claimed: MediaType): String? {
        if (allowUnknownTypes) return null
        val base = MediaType(claimed.type.lowercase(), claimed.subtype.lowercase())
        if (allowedUnknownPatterns.any { it.accepts(base) }) return null
        return "the type is unknown and unknown types are not allowed"
    }

    public companion object {
        private val logger = KotlinLogging.logger("com.lightningkite.services.files.tika.TikaFileScanner")
        private val mimeTypes: MimeTypes = MimeTypes.getDefaultMimeTypes()
        private val registry = mimeTypes.mediaTypeRegistry

        /**
         * Common non-standard names that Tika doesn't register as aliases, mapped to the canonical name Tika
         * uses. Without these, a correctly formed file claimed with one of these names would be treated as an
         * unknown type and never checked.
         */
        private val extraAliases: Map<String, String> = mapOf(
            "image/jpg" to "image/jpeg",
            "image/pjpeg" to "image/jpeg",
            "image/apng" to "image/png",
            "audio/mp3" to "audio/mpeg",
            "audio/x-mp3" to "audio/mpeg",
            "audio/mpeg3" to "audio/mpeg",
            "audio/x-mpeg-3" to "audio/mpeg",
            "audio/webm" to "video/webm",
            "video/matroska" to "video/x-matroska",
            "audio/matroska" to "audio/x-matroska",
        )

        public fun FileScanner.Settings.Companion.tika(
            maxBytes: Int? = null,
            allowUnknownTypes: Boolean = true,
            allowedUnknownTypes: Set<MediaType> = emptySet(),
        ): FileScanner.Settings {
            val params = buildList {
                if (maxBytes != null) add("bytes=$maxBytes")
                if (!allowUnknownTypes) add("unknownTypes=block")
                if (allowedUnknownTypes.isNotEmpty())
                    add("allowedUnknownTypes=" + allowedUnknownTypes.joinToString(",") { "${it.type}/${it.subtype}" })
            }
            return FileScanner.Settings("tika://" + if (params.isEmpty()) "" else params.joinToString("&", "?"))
        }

        init {
            FileScanner.Settings.register("tika") { name, url, context ->
                val params = url.substringAfter('?', "")
                    .split('&')
                    .filter { it.isNotBlank() }
                    .associate { it.substringBefore('=') to it.substringAfter('=', "") }
                val bytes = params["bytes"]?.let {
                    it.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid 'bytes' parameter in '$url'; it must be a positive integer")
                }
                val allowUnknown = when (val value = params["unknownTypes"]) {
                    null, "allow" -> true
                    "block" -> false
                    else -> throw IllegalArgumentException("Invalid 'unknownTypes' parameter '$value' in '$url'; it must be 'allow' or 'block'")
                }
                val allowedUnknown = params["allowedUnknownTypes"].orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map {
                        require(it.count { c -> c == '/' } == 1) {
                            "Invalid media type '$it' in 'allowedUnknownTypes' of '$url'; expected type/subtype"
                        }
                        MediaType(it)
                    }
                    .toSet()
                TikaFileScanner(
                    name,
                    context,
                    maxBytes = bytes ?: mimeTypes.minLength,
                    allowUnknownTypes = allowUnknown,
                    allowedUnknownTypes = allowedUnknown,
                )
            }
        }

        /** The Tika type for [type], or null if Tika doesn't know it (so it can't be checked). */
        private fun canonical(type: MediaType): TikaMediaType? {
            val base = "${type.type}/${type.subtype}".lowercase()
            val parsed = TikaMediaType.parse(extraAliases[base] ?: base) ?: return null
            val normalized = registry.normalize(parsed)
            return normalized.takeIf { mimeTypes.getRegisteredMimeType(it.toString()) != null }
        }

        /**
         * Whether a genuine file of [type] can be expected to start with a signature Tika recognizes: the type
         * itself or one of its parent types has magic. `application/octet-stream` and `text/plain` have magic
         * of their own in Tika's database (byte-order marks and the like), so they're excluded, and text types
         * never need a signature.
         */
        private fun needsSignature(type: TikaMediaType): Boolean {
            if (registry.isInstanceOf(type, TikaMediaType.TEXT_PLAIN)) return false
            var current: TikaMediaType? = type
            while (current != null && current != TikaMediaType.OCTET_STREAM) {
                if (mimeTypes.getRegisteredMimeType(current.toString())?.hasMagic() == true) return true
                current = registry.getSupertype(current)
            }
            return false
        }
    }

    override suspend fun scan(file: ExternalFile): Unit = telemetryTrace("scan") { span ->
        // The range is a maximum: getRange clamps at end-of-file, so short files are read whole.
        val item = file.getRange(0L until maxBytes.toLong()) ?: throw FileScanException("File does not exist")
        val bytes = item.use { it.data.bytes() }
        span.enrich(TelemetryAttributes { put(TelemetryKey.OfString("content_type"), item.mediaType.toString()) })

        mismatch(item.mediaType, bytes)?.let {
            throw FileScanException("Mime type mismatch; claimed ${item.mediaType.type}/${item.mediaType.subtype} but $it")
        }
    }

    override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
}
