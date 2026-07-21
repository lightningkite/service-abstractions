package com.lightningkite.services.data

import kotlinx.coroutines.withContext
import kotlinx.io.*
import kotlin.js.JsName

/**
 * Represents data that can be read in multiple formats.
 *
 * Provides a unified interface for handling data that may originate from different sources:
 * - In-memory byte arrays or strings ([Bytes], [Text])
 * - Blocking streaming sources or lazy producers ([Source], [Sink])
 * - Cooperative (non-blocking) streaming sources or lazy producers ([Suspending], [SuspendingProducer])
 *
 * ## Consumption is `suspend`
 *
 * All consumption ([bytes], [text], [write], [source], [writeTo]) is `suspend`. This lets streaming variants read/write
 * cooperatively instead of blocking a thread — consuming a [Suspending] on an engine's event loop can never deadlock
 * the way a blocking [Source]/[Sink] can. In-memory variants ([Bytes], [Text]) never actually suspend.
 *
 * ## Important Gotchas
 *
 * - **Single-use**: [Source], [Sink], [Suspending] and [SuspendingProducer] can only be consumed **once**.
 * - **Must close**: Always call [close] when done, especially with streaming variants.
 * - **Size may be unknown**: [size] returns null if the size is not known ahead of time.
 */
public sealed interface Data : AutoCloseable {
    public val size: Long? get() = null

    /** Get all bytes. Consumes and closes this instance. */
    public suspend fun bytes(): ByteArray

    /** Get the data as UTF-8 text. Consumes and closes this instance. */
    public suspend fun text(): String = bytes().decodeToString()

    /**
     * Get the data as a [Source]. Consumes this instance.
     *
     * For in-memory variants this is a buffer over the bytes. For a blocking streaming [Source] it is the **live**
     * underlying stream (not materialized) — reading it blocks, so read it on a blocking-capable thread, never an
     * engine event loop. Cooperative streaming variants must materialize here, since a blocking [kotlinx.io.Source]
     * cannot suspend; stream those via [writeTo] instead if you want to avoid buffering.
     */
    public suspend fun source(): kotlinx.io.Source = Buffer().apply { write(bytes()) }

    /** Write all bytes to a blocking [Sink]. Does not close [to]. Consumes this instance. */
    public suspend fun write(to: kotlinx.io.Sink) {
        to.write(bytes())
    }

    /** Write all bytes to a cooperative [SuspendingSink]. Does not close [to]. Consumes this instance. */
    public suspend fun writeTo(to: SuspendingSink) {
        to.writeAll(Buffer().apply { write(bytes()) })
    }

    override fun close() {}

    public class Bytes(public val data: ByteArray) : Data {
        public override val size: Long get() = data.size.toLong()
        public override suspend fun bytes(): ByteArray = data
        public override suspend fun text(): String = data.decodeToString()
        public override suspend fun source(): kotlinx.io.Source = Buffer().also { it.write(data) }
        public override suspend fun write(to: kotlinx.io.Sink) {
            to.write(data)
        }
        public override suspend fun writeTo(to: SuspendingSink) {
            to.writeAll(Buffer().also { it.write(data) })
        }
    }

    public class Text(public val data: String) : Data {
        private val asBytes by lazy { data.encodeToByteArray() }
        public val encoding: String = "UTF-8"
        public override val size: Long get() = asBytes.size.toLong()
        public override suspend fun bytes(): ByteArray = asBytes
        public override suspend fun text(): String = data
        public override suspend fun source(): kotlinx.io.Source = Buffer().also { it.writeString(data) }
        public override suspend fun write(to: kotlinx.io.Sink) {
            to.writeString(data)
        }
        public override suspend fun writeTo(to: SuspendingSink) {
            to.writeAll(Buffer().also { it.writeString(data) })
        }
    }

    /**
     * A blocking streaming source (a stream you read incrementally, not yet in memory — use [Bytes]/[Text] for
     * already-materialized data). Reading blocks a thread, so the materializing consumption methods ([bytes], [text],
     * [write], [writeTo]) offload to [ioDispatcher] automatically; they are therefore safe to consume from any thread,
     * including an engine event loop. You can only consume this once. Make sure you close it.
     *
     * The raw [source] property and [source] method are the **streaming escape hatch**: they hand back the live
     * underlying stream *without* reading it into memory and *without* self-dispatching. Use them when you will stream
     * the bytes yourself (e.g. `RequestBody.fromInputStream` for an S3 upload, or a virus scanner) — reading them
     * blocks, so you are responsible for your own thread offloading. Materializing them here instead would defeat the
     * whole point of a streaming source by pulling the entire payload into the heap.
     */
    public class Source(
        @JsName("sourceValue") public val source: kotlinx.io.Source,
        override val size: Long? = null,
    ) : Data {
        private var consumed = false
        private fun checkNotConsumed() {
            check(!consumed) { "Source has already been consumed. Source data can only be read once." }
            consumed = true
        }

        public override suspend fun bytes(): ByteArray {
            checkNotConsumed(); return withContext(ioDispatcher) { source.use { it.readByteArray() } }
        }

        public override suspend fun text(): String {
            checkNotConsumed(); return withContext(ioDispatcher) { source.use { it.readString() } }
        }

        /** Streaming escape hatch: returns the live underlying stream, not a materialized copy. See the class doc. */
        public override suspend fun source(): kotlinx.io.Source {
            checkNotConsumed(); return source
        }

        public override suspend fun write(to: kotlinx.io.Sink) {
            checkNotConsumed(); withContext(ioDispatcher) { source.use { it.transferTo(to) } }
        }

        public override suspend fun writeTo(to: SuspendingSink) {
            checkNotConsumed()
            withContext(ioDispatcher) {
                source.use { s ->
                    val staging = Buffer()
                    while (s.readAtMostTo(staging, 8192) != -1L) {
                        to.writeAll(staging)
                    }
                }
            }
        }

        public override fun close(): Unit = source.close()
    }

    /**
     * A blocking lazy producer. The [emit] lambda runs synchronously when consumed; since it may perform blocking I/O,
     * every consumption method offloads it to [ioDispatcher] automatically, so it is safe to consume from any thread,
     * including an engine event loop. You can only consume this once.
     */
    public class Sink(override val size: Long? = null, public val emit: (kotlinx.io.Sink) -> Unit) : Data {
        private var consumed = false
        private fun checkNotConsumed() {
            check(!consumed) { "Sink has already been consumed. Sink data can only be read once." }
            consumed = true
        }

        public override suspend fun bytes(): ByteArray {
            checkNotConsumed(); return withContext(ioDispatcher) { Buffer().also { emit(it) }.readByteArray() }
        }

        public override suspend fun text(): String {
            checkNotConsumed(); return withContext(ioDispatcher) { Buffer().also { emit(it) }.readString() }
        }

        public override suspend fun source(): kotlinx.io.Source {
            checkNotConsumed(); return withContext(ioDispatcher) { Buffer().also { emit(it) } }
        }

        public override suspend fun write(to: kotlinx.io.Sink) {
            // Do not close the caller's sink — the caller manages its own lifecycle.
            checkNotConsumed(); withContext(ioDispatcher) { emit(to) }
        }

        public override suspend fun writeTo(to: SuspendingSink) {
            checkNotConsumed(); withContext(ioDispatcher) { to.writeAll(Buffer().also { emit(it) }) }
        }

        public override fun close() {}
    }

    /**
     * A cooperative (non-blocking) streaming source backed by a [SuspendingSource]. You can only consume this once.
     * Make sure you close it.
     */
    public class Suspending(
        public val source: SuspendingSource,
        override val size: Long? = null,
    ) : Data {
        private var consumed = false
        private fun checkNotConsumed() {
            check(!consumed) { "Suspending has already been consumed. Streaming data can only be read once." }
            consumed = true
        }

        public override suspend fun bytes(): ByteArray {
            checkNotConsumed(); return source.readRemaining().readByteArray()
        }

        public override suspend fun text(): String {
            checkNotConsumed(); return source.readRemaining().readString()
        }

        public override suspend fun source(): kotlinx.io.Source {
            checkNotConsumed(); return source.readRemaining()
        }

        // If the destination sink throws (e.g. the client disconnects mid-response), the source's own read never
        // failed, so it would stay Open and leak its resource. Cancel it explicitly on any failure of the transfer.
        public override suspend fun write(to: kotlinx.io.Sink) {
            checkNotConsumed()
            try { source.transferTo(to) } catch (e: Throwable) { source.cancel(e); throw e }
        }

        public override suspend fun writeTo(to: SuspendingSink) {
            checkNotConsumed()
            try { source.transferTo(to) } catch (e: Throwable) { source.cancel(e); throw e }
        }

        public override fun close() {
            source.cancel()
        }
    }

    /**
     * A cooperative (non-blocking) lazy producer. The [emit] lambda writes into a [SuspendingSink] when consumed.
     * You can only consume this once.
     */
    public class SuspendingProducer(
        override val size: Long? = null,
        public val emit: suspend (SuspendingSink) -> Unit,
    ) : Data {
        private var consumed = false
        private fun checkNotConsumed() {
            check(!consumed) { "SuspendingProducer has already been consumed. Streaming data can only be read once." }
            consumed = true
        }

        private suspend fun collect(): Buffer {
            val sink = BufferSuspendingSink()
            emit(sink)
            sink.close()
            return sink.buffer
        }

        public override suspend fun bytes(): ByteArray {
            checkNotConsumed(); return collect().readByteArray()
        }

        public override suspend fun text(): String {
            checkNotConsumed(); return collect().readString()
        }

        public override suspend fun source(): kotlinx.io.Source {
            checkNotConsumed(); return collect()
        }

        public override suspend fun write(to: kotlinx.io.Sink) {
            // Do not close the caller's sink — closeUnderlying=false makes the producer's close only flush.
            checkNotConsumed(); emit(to.asSuspendingSink(closeUnderlying = false))
        }

        public override suspend fun writeTo(to: SuspendingSink) {
            checkNotConsumed(); emit(to)
        }

        public override fun close() {}
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

        public fun suspending(source: SuspendingSource, mediaType: MediaType, size: Long? = null): TypedData =
            TypedData(Data.Suspending(source, size), mediaType)

        public fun suspendingProducer(
            mediaType: MediaType,
            size: Long? = null,
            emit: suspend (SuspendingSink) -> Unit,
        ): TypedData = TypedData(Data.SuspendingProducer(size, emit), mediaType)
    }

    public suspend fun text(): String = data.text()
    public suspend fun write(to: kotlinx.io.Sink): Unit = data.write(to)
    public suspend fun writeTo(to: SuspendingSink): Unit = data.writeTo(to)

    override fun toString(): String {
        return "${data::class.simpleName}(${mediaType})"
    }
}
