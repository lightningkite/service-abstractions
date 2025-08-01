package com.lightningkite.services.data

import com.lightningkite.MediaType
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

public sealed interface Data: AutoCloseable {
    public fun bytes(): ByteArray
    public fun write(to: kotlinx.io.Sink)
    public fun text(): String

    /**
     * You can only consume this once.
     * Make sure you close it.
     */
    public class Sink(public val emit: (kotlinx.io.Sink) -> Unit): Data {
        override fun write(to: kotlinx.io.Sink) { emit(to) }
        override fun text(): String {
            val dest = Buffer()
            emit(dest)
            return dest.readString()
        }
        override fun bytes(): ByteArray {
            val dest = Buffer()
            emit(dest)
            return dest.readByteArray()
        }
        override fun close() {}
    }

    /**
     * You can only consume this once.
     * Make sure you close it.
     */
    public class Source(public val source: kotlinx.io.Source): Data {
        override fun write(to: kotlinx.io.Sink): Unit = source.use { source ->
            source.transferTo(to)
        }

        override fun text(): String = source.use { source ->
            source.readString()
        }
        override fun bytes(): ByteArray = source.use { source ->
            source.readByteArray()
        }
        override fun close() = source.close()
    }
    public class Bytes(public val data: ByteArray): Data {
        override fun write(to: kotlinx.io.Sink): Unit = to.write(data, 0, data.size)
        override fun bytes(): ByteArray = data
        override fun text(): String = data.decodeToString()
        override fun close() = Unit
    }
    public class Text(public val data: String): Data {
        public val encoding: String = "UTF-8"
        override fun write(to: kotlinx.io.Sink) {
            to.writeString(data)
        }
        override fun bytes(): ByteArray = data.encodeToByteArray()
        override fun text(): String = data
        override fun close() = Unit
    }
}

public data class TypedData(val data: Data, val mediaType: MediaType): AutoCloseable {
    override fun close() { data.close() }
    public companion object {
        public fun text(text: String, mediaType: MediaType): TypedData = TypedData(Data.Text(text), mediaType)
        public fun bytes(bytes: ByteArray, mediaType: MediaType): TypedData = TypedData(Data.Bytes(bytes), mediaType)
        public fun source(source: kotlinx.io.Source, mediaType: MediaType): TypedData = TypedData(Data.Source(source), mediaType)
        public fun sink(emit: (kotlinx.io.Sink) -> Unit, mediaType: MediaType): TypedData = TypedData(Data.Sink(emit), mediaType)
    }
    public fun text(): String = data.text()
    public fun write(to: kotlinx.io.Sink) = data.write(to)
}
