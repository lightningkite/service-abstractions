package com.lightningkite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer


/**
 * Represents a Media (formerly known as MIME) content type.
 */
@Serializable(MediaType.Serializer::class)
public data class MediaType(val type: String, val subtype: String, val parameters: Map<String, String> = mapOf()) {

    public object Serializer: KSerializer<MediaType> {
        override val descriptor = String.serializer().descriptor
        override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: MediaType) = encoder.encodeString(value.toString())
        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): MediaType = MediaType(decoder.decodeString())
    }

    public constructor(fullType: String) : this(
        fullType.substringBefore('/'),
        fullType.substringAfter('/').substringBefore(';'),
        fullType.substringAfter(';', "").split(';').filter { it.isNotBlank() }.associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
    )

    public fun accepts(other: MediaType): Boolean
        = (type == "*" || type == other.type) && (subtype == "*" || subtype == other.subtype)

    override fun toString(): String = "$type/$subtype" + (if(parameters.isNotEmpty()) parameters.entries.joinToString("; ", "; ") { "${it.key}=${it.value}" } else "")

    public companion object {
        public val Any: MediaType = MediaType("*/*")

        public val xmlTypes = setOf(
            Application.Xml,
            Text.Html,
            Text.Xml,
        )
        private val mapping = listOf(
            "bin" to Application.OctetStream,
            "cbor" to Application.Cbor,
            "json" to Application.Json,
            "bson" to Application.Bson,
            "js" to Application.JavaScript,
            "xml" to Application.Xml,
            "zip" to Application.Zip,
            "wav" to Audio.WAV,
            "mp3" to Audio.MP3,
            "mpg" to Video.MPEG,
            "mpeg" to Video.MPEG,
            "mp4" to Video.MP4,
            "ogg" to Video.OGG,
            "mov" to Video.QuickTime,
            "txt" to Text.Plain,
            "css" to Text.CSS,
            "csv" to Text.CSV,
            "htm" to Text.Html,
            "html" to Text.Html,
            "js" to Text.JavaScript,
            "xml" to Text.Xml,
            "gif" to Image.GIF,
            "jpg" to Image.JPEG,
            "jpeg" to Image.JPEG,
            "png" to Image.PNG,
            "svg" to Image.SVG,
            "webp" to Image.WebP,
            "tiff" to Image.Tiff,
            "bmp" to Image.BMP,
            "jp2" to Image.JPEG2000,
            "pdf" to Application.Pdf,
            "xlsx" to Application.Xlsx,
            "docx" to Application.Docx,
            "pptx" to Application.Pptx,
        )

        /**
         * Gets a content type based on a file extension.
         */
        private val extensionToType = mapping.associate { it }
        private val typeToExtension = mapping.associate { it.second to it.first }
        public fun fromExtension(extension: String): MediaType = extensionToType[extension.lowercase()] ?: Application.OctetStream
    }
    public val withoutParameters: MediaType get() = MediaType(type, subtype)
    public val extension: String get() = typeToExtension[this.withoutParameters] ?: "unknown"


    /**
     * Provides a list of standard subtypes of an `application` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Application {
        /**
         * Represents a pattern `application / *` to match any application content type.
         */
        public val Any: MediaType = MediaType("application", "*")
        public val Atom: MediaType = MediaType("application", "atom+xml")
        public val Cbor: MediaType = MediaType("application", "cbor")
        public val Json: MediaType = MediaType("application", "json")
        public val Bson: MediaType = MediaType("application", "bson")
        public val HalJson: MediaType = MediaType("application", "hal+json")
        public val JavaScript: MediaType = MediaType("application", "javascript")
        public val OctetStream: MediaType = MediaType("application", "octet-stream")
        public val StructuredBytes: MediaType = MediaType("application", "x-structured-bytes")
        public val FontWoff: MediaType = MediaType("application", "font-woff")
        public val Rss: MediaType = MediaType("application", "rss+xml")
        public val Xml: MediaType = MediaType("application", "xml")
        public val Xml_Dtd: MediaType = MediaType("application", "xml-dtd")
        public val Zip: MediaType = MediaType("application", "zip")
        public val GZip: MediaType = MediaType("application", "gzip")

        public val FormUrlEncoded: MediaType =
            MediaType("application", "x-www-form-urlencoded")

        public val Pdf: MediaType = MediaType("application", "pdf")
        public val Xlsx: MediaType = MediaType(
            "application",
            "vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
        public val Docx: MediaType = MediaType(
            "application",
            "vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        public val Pptx: MediaType = MediaType(
            "application",
            "vnd.openxmlformats-officedocument.presentationml.presentation"
        )
        public val ProtoBuf: MediaType = MediaType("application", "protobuf")
        public val ProtoBufText: MediaType = MediaType("application", "x-protobuf-text")
        public val ProtoBufDeclaration: MediaType = MediaType("application", "x-protobuf-declaration")
        public val Wasm: MediaType = MediaType("application", "wasm")
        public val ProblemJson: MediaType = MediaType("application", "problem+json")
        public val ProblemXml: MediaType = MediaType("application", "problem+xml")
    }

    /**
     * Provides a list of standard subtypes of an `audio` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Audio {
        public val Any: MediaType = MediaType("audio", "*")
        public val WAV: MediaType = MediaType("audio", "wav")
        public val MP4: MediaType = MediaType("audio", "mp4")
        public val MPEG: MediaType = MediaType("audio", "mpeg")
        public val MP3: MediaType = MediaType("audio", "mpeg")
        public val OGG: MediaType = MediaType("audio", "ogg")
    }

    /**
     * Provides a list of standard subtypes of an `image` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Image {
        public val Any: MediaType = MediaType("image", "*")
        public val APNG: MediaType = MediaType("image", "apng")
        public val AVIF: MediaType = MediaType("image", "avif")
        public val GIF: MediaType = MediaType("image", "gif")
        public val JPEG2000: MediaType = MediaType("image", "jp2")
        public val JPEG: MediaType = MediaType("image", "jpeg")
        public val PNG: MediaType = MediaType("image", "png")
        public val SVG: MediaType = MediaType("image", "svg+xml")
        public val WebP: MediaType = MediaType("image", "webp")
        public val XIcon: MediaType = MediaType("image", "x-icon")
        public val Tiff: MediaType = MediaType("image", "tiff")
        public val BMP: MediaType = MediaType("image", "bmp")
    }

    /**
     * Provides a list of standard subtypes of a `message` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Message {
        public val Any: MediaType = MediaType("message", "*")
        public val Http: MediaType = MediaType("message", "http")
    }

    /**
     * Provides a list of standard subtypes of a `multipart` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object MultiPart {
        public val Any: MediaType = MediaType("multipart", "*")
        public val Mixed: MediaType = MediaType("multipart", "mixed")
        public val Alternative: MediaType = MediaType("multipart", "alternative")
        public val Related: MediaType = MediaType("multipart", "related")
        public val FormData: MediaType = MediaType("multipart", "form-data")
        public val Signed: MediaType = MediaType("multipart", "signed")
        public val Encrypted: MediaType = MediaType("multipart", "encrypted")
        public val ByteRanges: MediaType = MediaType("multipart", "byteranges")
    }

    /**
     * Provides a list of standard subtypes of a `text` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Text {
        public val Any: MediaType = MediaType("text", "*")
        public val Plain: MediaType = MediaType("text", "plain", mapOf("charset" to "UTF-8"))
        public val CSS: MediaType = MediaType("text", "css", mapOf("charset" to "UTF-8"))
        public val CSV: MediaType = MediaType("text", "csv", mapOf("charset" to "UTF-8"))
        public val Html: MediaType = MediaType("text", "html", mapOf("charset" to "UTF-8"))
        public val JavaScript: MediaType = MediaType("text", "javascript", mapOf("charset" to "UTF-8"))
        public val VCard: MediaType = MediaType("text", "vcard", mapOf("charset" to "UTF-8"))
        public val Xml: MediaType = MediaType("text", "xml", mapOf("charset" to "UTF-8"))
        public val EventStream: MediaType = MediaType("text", "event-stream", mapOf("charset" to "UTF-8"))
        public val UriList: MediaType = MediaType("text", "uri-list", mapOf("charset" to "UTF-8"))
        public val Yaml: MediaType = MediaType("text", "vnd.yaml", mapOf("charset" to "UTF-8"))
    }

    /**
     * Provides a list of standard subtypes of a `video` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Video {
        public val Any: MediaType = MediaType("video", "*")
        public val MPEG: MediaType = MediaType("video", "mpeg")
        public val MP4: MediaType = MediaType("video", "mp4")
        public val OGG: MediaType = MediaType("video", "ogg")
        public val QuickTime: MediaType = MediaType("video", "quicktime")
    }
}