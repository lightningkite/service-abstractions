package com.lightningkite


/**
 * Represents a Media (formerly known as MIME) content type.
 */
public data class MediaType(val type: String, val subtype: String) {
    public constructor(fullType: String) : this(
        fullType.substringBefore('/'),
        fullType.substringAfter('/')
    )

    override fun toString(): String = "$type/$subtype"

    public companion object {
        public val Any: MediaType = MediaType("*/*")

        /**
         * Gets a content type based on a file extension.
         */
        public fun fromExtension(extension: String): MediaType = when (extension.lowercase()) {
            "json" -> Application.Json
            "pdf" -> Application.Pdf
            "xml" -> Application.Xml
            "txt" -> Text.Plain
            "html", "htm" -> Text.Html
            "css" -> Text.Css
            "csv" -> Text.Csv
            "jpg", "jpeg" -> Image.Jpeg
            "png" -> Image.Png
            "gif" -> Image.Gif
            "svg" -> Image.Svg
            "mp4" -> Video.Mp4
            "mpeg", "mpg" -> Video.Mpeg
            "mp3" -> Audio.Mp3
            "wav" -> Audio.Wav
            else -> Application.OctetStream
        }
    }

    public object Application {
        public val Any: MediaType = MediaType("application/*")
        public val Json: MediaType = MediaType("application/json")
        public val OctetStream: MediaType = MediaType("application/octet-stream")
        public val Pdf: MediaType = MediaType("application/pdf")
        public val Xml: MediaType = MediaType("application/xml")
    }

    public object Text {
        public val Any: MediaType = MediaType("text/*")
        public val Plain: MediaType = MediaType("text/plain")
        public val Html: MediaType = MediaType("text/html")
        public val Css: MediaType = MediaType("text/css")
        public val Csv: MediaType = MediaType("text/csv")
    }

    public object Image {
        public val Any: MediaType = MediaType("image/*")
        public val Jpeg: MediaType = MediaType("image/jpeg")
        public val Png: MediaType = MediaType("image/png")
        public val Gif: MediaType = MediaType("image/gif")
        public val Svg: MediaType = MediaType("image/svg+xml")
    }

    public object Video {
        public val Any: MediaType = MediaType("video/*")
        public val Mp4: MediaType = MediaType("video/mp4")
        public val Mpeg: MediaType = MediaType("video/mpeg")
    }

    public object Audio {
        public val Any: MediaType = MediaType("audio/*")
        public val Mp3: MediaType = MediaType("audio/mpeg")
        public val Wav: MediaType = MediaType("audio/wav")
    }
}