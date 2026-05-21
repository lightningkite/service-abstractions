package com.lightningkite.services.data

import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the contract that [Data.write] does NOT close the caller's sink.
 *
 * Before the 1.0.0 performance-and-security pass, three of the four [Data] subclasses
 * (`Bytes`, `Text`, `Sink`) wrapped their write in `to.use { ... }`, silently closing
 * the caller's sink and breaking any pattern that wrote multiple payloads to the same
 * underlying buffer (e.g. multipart encoders).
 *
 * Verifying for every variant guarantees no implementation regresses individually.
 */
class DataWriteSinkLifecycleTest {

    @Test
    fun bytesWriteLeavesSinkOpen() {
        val buffer = Buffer()
        Data.Bytes("hello".encodeToByteArray()).write(buffer)
        // If write() closed the buffer, this would throw IllegalStateException.
        buffer.writeString(" world")
        assertEquals("hello world", buffer.readString())
    }

    @Test
    fun textWriteLeavesSinkOpen() {
        val buffer = Buffer()
        Data.Text("hello").write(buffer)
        buffer.writeString(" world")
        assertEquals("hello world", buffer.readString())
    }

    @Test
    fun sinkWriteLeavesSinkOpen() {
        val buffer = Buffer()
        Data.Sink { it.writeString("hello") }.write(buffer)
        buffer.writeString(" world")
        assertEquals("hello world", buffer.readString())
    }

    @Test
    fun sourceWriteLeavesSinkOpen() {
        // Source.write was already implemented this way before the change — verify it stays correct.
        val payload = Buffer().also { it.writeString("hello") }
        val buffer = Buffer()
        Data.Source(payload).write(buffer)
        buffer.writeString(" world")
        assertEquals("hello world", buffer.readString())
    }
}
