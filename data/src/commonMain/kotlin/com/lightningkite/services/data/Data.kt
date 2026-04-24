package com.lightningkite.services.data

import kotlinx.io.*
import kotlin.js.JsName

/**
 * Represents data that can be read in multiple formats.
 *
 * Provides a unified interface for handling data that may originate from different sources:
 * - In-memory byte arrays or strings ([Bytes], [Text])
 * - Streaming sources ([Source])
 * - Lazy-generated data ([Sink])
 *
 * ## Important Gotchas
 *
 * - **Single-use**: [Source] and [Sink] implementations can only be consumed **once**
 * - **Must close**: Always call [close] when done, especially with [Source]
 * - **Size may be unknown**: [size] returns null if the size is not known ahead of time
 *
 * ## Usage
 *
 * ```kotlin
 * // In-memory data (reusable)
 * val textData = Data.Text("Hello, world!")
 * val byteData = Data.Bytes(byteArrayOf(1, 2, 3))
 *
 * // Streaming data (single-use, must close)
 * val streamData = Data.Source(inputStream.asSource())
 * streamData.use { data ->
 *     val content = data.text()
 * }
 * ```
 */
public sealed interface Data : AutoCloseable {
    public val size: Long? get() = null
    public fun bytes(): ByteArray
    public fun write(to: kotlinx.io.Sink)
    public fun text(): String
    public fun source(): kotlinx.io.Source

    /**
     * You can only consume this once.
     * Make sure you close it.
     */
    public class Sink(override val size: Long? = null, public val emit: (kotlinx.io.Sink) -> Unit) : Data {
        private var consumed = false
        private fun checkNotConsumed() {
            check(!consumed) { "Sink has already been consumed. Sink data can only be read once." }
            consumed = true
        }

        override fun write(to: kotlinx.io.Sink) {
            checkNotConsumed(); to.use { emit(to) }
        }

        override fun text(): String {
            checkNotConsumed()
            val dest = Buffer()
            emit(dest)
            return dest.readString()
        }

        override fun bytes(): ByteArray {
            checkNotConsumed()
            val dest = Buffer()
            emit(dest)
            return dest.readByteArray()
        }

        override fun source(): kotlinx.io.Source {
            checkNotConsumed(); return Buffer().also { emit(it) }
        }

        override fun close() {}
    }

    /**
     * You can only consume this once.
     * Make sure you close it.
     */
    public class Source(@JsName("sourceValue") public val source: kotlinx.io.Source, override val size: Long? = null) :
        Data {
        private var consumed = false
        private fun checkNotConsumed() {
            check(!consumed) { "Source has already been consumed. Source data can only be read once." }
            consumed = true
        }

        public override fun write(to: kotlinx.io.Sink): Unit {
            checkNotConsumed()
            source.use { source -> source.transferTo(to) }
        }

        public override fun text(): String {
            checkNotConsumed()
            return source.use { source -> source.readString() }
        }

        public override fun bytes(): ByteArray {
            checkNotConsumed()
            return source.use { source -> source.readByteArray() }
        }

        public override fun source(): kotlinx.io.Source {
            checkNotConsumed(); return source
        }

        public override fun close(): Unit = source.close()
    }

    public class Bytes(public val data: ByteArray) : Data {
        public override val size: Long
            get() = data.size.toLong()

        public override fun write(to: kotlinx.io.Sink): Unit = to.use { to.write(data, 0, data.size) }
        public override fun bytes(): ByteArray = data
        public override fun text(): String = data.decodeToString()
        public override fun source(): kotlinx.io.Source = Buffer().also { it.write(data) }
        public override fun close(): Unit = Unit
    }

    public class Text(public val data: String) : Data {
        private val asBytes by lazy { data.encodeToByteArray() }
        public override val size: Long
            get() = asBytes.size.toLong()
        public val encoding: String = "UTF-8"
        public  override fun write(to: kotlinx.io.Sink) {
            to.use {
                to.writeString(data)
            }
        }

        public override fun source(): kotlinx.io.Source = Buffer().also { it.writeString(data) }
        public override fun bytes(): ByteArray = asBytes
        public override fun text(): String = data
        public override fun close(): Unit = Unit
    }
}

public class TypedData(public val data: Data, public val mediaType: MediaType) : AutoCloseable {
    public override fun close() {
        data.close()
    }

    public companion object {
        public fun text(text: String, mediaType: MediaType): TypedData = TypedData(Data.Text(text), mediaType)
        public fun bytes(bytes: ByteArray, mediaType: MediaType): TypedData = TypedData(Data.Bytes(bytes), mediaType)
        public fun source(source: Source, mediaType: MediaType, size: Long? = null): TypedData =
            TypedData(Data.Source(source, size), mediaType)

        public fun sink(mediaType: MediaType, size: Long? = null, emit: (Sink) -> Unit): TypedData =
            TypedData(Data.Sink(size, emit), mediaType)
    }

    public fun text(): String = data.text()
    public fun write(to: Sink): Unit = data.write(to)

    override fun toString(): String {
        return "${data::class.simpleName}(${mediaType})"
    }
}
