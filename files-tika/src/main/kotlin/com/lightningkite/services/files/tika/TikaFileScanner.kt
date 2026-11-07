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
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.mime.MimeTypes
import org.apache.tika.parser.ParseContext
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
 *
 * ```kotlin
 * FileScanner.Settings("tika://")
 * FileScanner.Settings.Companion.tika()
 *
 * // Allow all unknown types, or specifically BSON and protobuf text
 * FileScanner.Settings("tika://?unknownTypes=allow")
 * FileScanner.Settings("tika://?allowedUnknownTypes=application/bson,application/x-protobuf-text")
 * ```

 * @property allowUnknownTypes Whether files claiming a type Tika doesn't know pass without being checked.
 * @property allowedUnknownTypes Unknown types that still pass when [allowUnknownTypes] is `false`. Parameters
 * are ignored, and `*` works as a wildcard for the type or subtype. It has no effect when [allowUnknownTypes] is
 * `true`, and a warning is logged if it's set anyway.
 */
public class TikaFileScanner(
    override val name: String,
    override val context: SettingContext,
    public val allowUnknownTypes: Boolean = false,
    public val allowedUnknownTypes: Set<MediaType> = emptySet(),
) : FileScanner {

    init {
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
        val detected = TikaInputStream.get(bytes).use { mimeTypes.detect(it, Metadata(), ParseContext()) }.baseType

        if (registry.isInstanceOf(detected, claimedType)) return null
        if (registry.isInstanceOf(claimedType, detected)) {
            if (detected != TikaMediaType.OCTET_STREAM) return null
            // Tika falls back to text/plain for anything that reads as text, so octet-stream means the content
            // isn't text. Empty content is the exception: Tika reports it as octet-stream too.
            if (registry.isInstanceOf(claimedType, TikaMediaType.TEXT_PLAIN)) {
                return if (bytes.isEmpty()) null else "content isn't text"
            }
            if (!needsSignature(claimedType)) return null
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
            allowUnknownTypes: Boolean = true,
            allowedUnknownTypes: Set<MediaType> = emptySet(),
        ): FileScanner.Settings {
            val params = buildList {
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
                val allowUnknown = when (val value = params["unknownTypes"]?.lowercase()) {
                    "allow" -> true
                    null, "block" -> false
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
        // Tika's detection never looks past minLength bytes. The range is a maximum: getRange clamps at
        // end-of-file, so short files are read whole.
        val item = file.getRange(0L until mimeTypes.minLength.toLong()) ?: throw FileScanException("File does not exist")
        val bytes = item.use { it.data.bytes() }
        span.enrich(TelemetryAttributes { put(TelemetryKey.OfString("content_type"), item.mediaType.toString()) })

        mismatch(item.mediaType, bytes)?.let {
            throw FileScanException("Mime type mismatch; claimed ${item.mediaType.type}/${item.mediaType.subtype} but $it")
        }
    }

    override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
}
