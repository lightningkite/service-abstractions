package com.lightningkite.services.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder


/**
 * Represents a Media (formerly known as MIME) content type.
 *
 * ## Serialization
 *
 * This is encoded as its string representation, i.e. `MediaType.Application.Json -> "application/json"`.
 */
@Serializable(MediaType.Serializer::class)
public data class MediaType(val type: String, val subtype: String, val parameters: Map<String, String> = mapOf()) {

    public object Serializer : KSerializer<MediaType> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("com.lightningkite.services.data.MediaType", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: MediaType): Unit =
            encoder.encodeString(value.toString())

        override fun deserialize(decoder: Decoder): MediaType =
            MediaType(decoder.decodeString())
    }

    public constructor(fullType: String) : this(
        fullType.substringBefore('/'),
        fullType.substringAfter('/').substringBefore(';'),
        fullType.substringAfter(';', "").split(';').filter { it.isNotBlank() }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
    )

    public fun accepts(other: MediaType): Boolean =
        (type == "*" || type == other.type) && (subtype == "*" || subtype == other.subtype)

    override fun toString(): String = "$type/$subtype" + (if (parameters.isNotEmpty()) parameters.entries.joinToString(
        "; ",
        "; "
    ) { "${it.key}=${it.value}" } else "")

    public companion object {
        public val Any: MediaType = MediaType("*/*")

        public val xmlTypes: Set<MediaType> = setOf(
            Application.Xml,
            Text.Html,
            Text.Xml,
        )

        // These are best guess items. Some items like mp4, actually exist in both Audio and Video because
        // they are multimedia containers. This map assumes to most likely use case of the type.
        private val extensionTypeMap = mapOf(
            "bin" to Application.OctetStream,
            "bson" to Application.Bson,
            "cbor" to Application.Cbor,
            "docx" to Application.Docx,
            "json" to Application.Json,
            "pdf" to Application.Pdf,
            "pptx" to Application.Pptx,
            "xlsx" to Application.Xlsx,
            "xml" to Application.Xml,
            "zip" to Application.Zip,

            "aac" to Audio.AAC,
            "flac" to Audio.FLAC,
            "m4a" to Audio.MP4,
            "mp3" to Audio.MP3,
            "oga" to Audio.OGG,
            "ogg" to Audio.OGG,
            "opus" to Audio.OPUS,
            "wav" to Audio.WAV,
            "weba" to Audio.WEBM,

            "apng" to Image.APNG,
            "avif" to Image.AVIF,
            "bmp" to Image.BMP,
            "gif" to Image.GIF,
            "ico" to Image.XIcon,
            "jp2" to Image.JPEG2000,
            "jpeg" to Image.JPEG,
            "jpg" to Image.JPEG,
            "jxl" to Image.JXL,
            "png" to Image.PNG,
            "svg" to Image.SVG,
            "tiff" to Image.Tiff,
            "webp" to Image.WebP,

            "css" to Text.CSS,
            "csv" to Text.CSV,
            "htm" to Text.Html,
            "html" to Text.Html,
            "js" to Text.JavaScript,
            "txt" to Text.Plain,
            "yaml" to Text.Yaml,
            "yml" to Text.Yaml,

            "avi" to Video.AVI,
            "mkv" to Video.MKV,
            "mov" to Video.QuickTime,
            "mp4v" to Video.MP4,
            "mpg4" to Video.MP4,
            "mp4" to Video.MP4,
            "mpeg" to Video.MPEG,
            "mpg" to Video.MPEG,
            "ogv" to Video.OGG,
            "webm" to Video.WEBM,
        )

        /**
         * Gets a content type based on a file extension.
         */
        private val reversedMappings = extensionTypeMap.entries.associate { it.value to it.key }
        public fun fromExtension(extension: String): MediaType =
            extensionTypeMap[extension.lowercase()] ?: Application.OctetStream
    }

    public val withoutParameters: MediaType get() = MediaType(type, subtype)
    public val extension: String get() = reversedMappings[this.withoutParameters] ?: "unknown"


    // This is not an exhaustive list of mime types, these are just the most common MimeTypes.

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
        public val Bson: MediaType = MediaType("application", "bson")
        public val Cbor: MediaType = MediaType("application", "cbor")
        public val Docx: MediaType =
            MediaType("application", "vnd.openxmlformats-officedocument.wordprocessingml.document")
        public val FontWoff: MediaType = MediaType("application", "font-woff")
        public val FormUrlEncoded: MediaType = MediaType("application", "x-www-form-urlencoded")
        public val GZip: MediaType = MediaType("application", "gzip")
        public val HalJson: MediaType = MediaType("application", "hal+json")
        public val JavaScript: MediaType = MediaType("application", "javascript")
        public val Json: MediaType = MediaType("application", "json")
        public val OctetStream: MediaType = MediaType("application", "octet-stream")
        public val Pdf: MediaType = MediaType("application", "pdf")
        public val Pptx: MediaType =
            MediaType("application", "vnd.openxmlformats-officedocument.presentationml.presentation")
        public val ProblemJson: MediaType = MediaType("application", "problem+json")
        public val ProblemXml: MediaType = MediaType("application", "problem+xml")
        public val ProtoBuf: MediaType = MediaType("application", "protobuf")
        public val ProtoBufDeclaration: MediaType = MediaType("application", "x-protobuf-declaration")
        public val ProtoBufText: MediaType = MediaType("application", "x-protobuf-text")
        public val Rss: MediaType = MediaType("application", "rss+xml")
        public val StructuredBytes: MediaType = MediaType("application", "x-structured-bytes")
        public val Wasm: MediaType = MediaType("application", "wasm")
        public val Xlsx: MediaType = MediaType("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        public val Xml: MediaType = MediaType("application", "xml")
        public val Xml_Dtd: MediaType = MediaType("application", "xml-dtd")
        public val Zip: MediaType = MediaType("application", "zip")
    }

    /**
     * Provides a list of standard subtypes of an `audio` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Audio {
        public val Any: MediaType = MediaType("audio", "*")
        public val AAC: MediaType = MediaType("audio", "aac")
        public val FLAC: MediaType = MediaType("audio", "flac")
        public val MP3: MediaType = MediaType("audio", "mpeg")
        public val MP4: MediaType = MediaType("audio", "mp4")
        public val MPEG: MediaType = MediaType("audio", "mpeg")
        public val OGG: MediaType = MediaType("audio", "ogg")
        public val OPUS: MediaType = MediaType("audio", "opus")
        public val WAV: MediaType = MediaType("audio", "wav")
        public val WEBM: MediaType = MediaType("audio", "webm")
    }

    /**
     * Provides a list of standard subtypes of an `image` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Image {
        public val Any: MediaType = MediaType("image", "*")
        public val APNG: MediaType = MediaType("image", "apng")
        public val AVIF: MediaType = MediaType("image", "avif")
        public val BMP: MediaType = MediaType("image", "bmp")
        public val GIF: MediaType = MediaType("image", "gif")
        public val ICO: MediaType = MediaType("image", "vnd.microsoft.icon")
        public val JPEG2000: MediaType = MediaType("image", "jp2")
        public val JPEG: MediaType = MediaType("image", "jpeg")
        public val JXL: MediaType = MediaType("image", "jxl")
        public val PNG: MediaType = MediaType("image", "png")
        public val SVG: MediaType = MediaType("image", "svg+xml")
        public val Tiff: MediaType = MediaType("image", "tiff")
        public val WebP: MediaType = MediaType("image", "webp")
        public val XIcon: MediaType = MediaType("image", "x-icon")
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
        public val Alternative: MediaType = MediaType("multipart", "alternative")
        public val ByteRanges: MediaType = MediaType("multipart", "byteranges")
        public val Encrypted: MediaType = MediaType("multipart", "encrypted")
        public val FormData: MediaType = MediaType("multipart", "form-data")
        public val Mixed: MediaType = MediaType("multipart", "mixed")
        public val Related: MediaType = MediaType("multipart", "related")
        public val Signed: MediaType = MediaType("multipart", "signed")
    }

    /**
     * Provides a list of standard subtypes of a `text` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Text {
        public val Any: MediaType = MediaType("text", "*")
        public val CSS: MediaType = MediaType("text", "css", mapOf("charset" to "UTF-8"))
        public val CSV: MediaType = MediaType("text", "csv", mapOf("charset" to "UTF-8"))
        public val EventStream: MediaType = MediaType("text", "event-stream", mapOf("charset" to "UTF-8"))
        public val Html: MediaType = MediaType("text", "html", mapOf("charset" to "UTF-8"))
        public val JavaScript: MediaType = MediaType("text", "javascript", mapOf("charset" to "UTF-8"))
        public val Plain: MediaType = MediaType("text", "plain", mapOf("charset" to "UTF-8"))
        public val UriList: MediaType = MediaType("text", "uri-list", mapOf("charset" to "UTF-8"))
        public val VCard: MediaType = MediaType("text", "vcard", mapOf("charset" to "UTF-8"))
        public val Xml: MediaType = MediaType("text", "xml", mapOf("charset" to "UTF-8"))
        public val Yaml: MediaType = MediaType("text", "vnd.yaml", mapOf("charset" to "UTF-8"))
    }

    /**
     * Provides a list of standard subtypes of a `video` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Video {
        public val Any: MediaType = MediaType("video", "*")
        public val AVI: MediaType = MediaType("video", "avi")
        public val MKV: MediaType = MediaType("video", "matroska")
        public val MP4: MediaType = MediaType("video", "mp4")
        public val MPEG: MediaType = MediaType("video", "mpeg")
        public val OGG: MediaType = MediaType("video", "ogg")
        public val QuickTime: MediaType = MediaType("video", "quicktime")
        public val WEBM: MediaType = MediaType("video", "webm")
    }
}