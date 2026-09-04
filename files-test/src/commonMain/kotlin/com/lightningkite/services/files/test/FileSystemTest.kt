package com.lightningkite.services.files.test

import com.lightningkite.services.data.*
import com.lightningkite.services.data.DataSize.Companion.bytes
import com.lightningkite.services.default
import com.lightningkite.services.files.ExternalFile
import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.files.serverFile
import com.lightningkite.services.http.client
import com.lightningkite.services.test.runTestWithClock
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

abstract class FileSystemTests {
    abstract val system: ExternalFileSystem?
    open fun runSuspendingTest(body: suspend CoroutineScope.() -> Unit) = runTestWithClock { body() }

    @Test
    fun testHealth() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runSuspendingTest {
            system.healthCheck()
        }
    }

    @Test
    fun testWriteAndRead() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runSuspendingTest {
            val testFile = system.root.then("test.txt")
            val message = "Hello world!"
            testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
            try {
                assertEquals(message, testFile.get()!!.data.text())
            } finally {
                testFile.delete()
            }
        }
    }

    @Test
    fun testGetRange() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runSuspendingTest {
            val testFile = system.root.then("range.txt")
            val message = "0123456789ABCDEF"  // 16 bytes, one per index, so a window reads as its own bounds
            testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
            try {
                val middle = testFile.getRange(4L..6L)!!
                assertEquals(MediaType.Text.Plain, middle.mediaType)
                // The size must describe what was returned, not the whole object.
                assertEquals(3L, middle.data.size)
                assertEquals("456", middle.data.text())

                // Both ends inclusive, as in HTTP's `bytes=a-b`: 0..15 is all 16 bytes.
                assertEquals(message, testFile.getRange(0L..15L)!!.data.text())
                assertEquals("F", testFile.getRange(15L..15L)!!.data.text())
                assertEquals("0", testFile.getRange(0L..0L)!!.data.text())
            } finally {
                testFile.delete()
            }
        }
    }

    /**
     * A caller reading in fixed-size chunks can't know where the end falls without asking, so
     * overrunning it is an ordinary result rather than an error.
     */
    @Test
    fun testGetRangeClampsToEndOfFile() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runSuspendingTest {
            val testFile = system.root.then("range-clamp.txt")
            val message = "short"
            testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
            try {
                val overrun = testFile.getRange(0L..15L)!!
                assertEquals(message, overrun.data.text())
                assertEquals(message.length.toLong(), overrun.data.size)

                assertEquals("ort", testFile.getRange(2L..99L)!!.data.text())

                // Starting at or past the end yields nothing, and must not throw.
                assertEquals("", testFile.getRange(5L..15L)!!.data.text())
                assertEquals("", testFile.getRange(500L..600L)!!.data.text())
            } finally {
                testFile.delete()
            }
        }
    }

    /**
     * `a..Long.MAX_VALUE` is how an open-ended `bytes=a-` maps. Computing its length naively
     * overflows to a negative and silently yields nothing, which every implementation guards
     * against - so every implementation should be held to it.
     */
    @Test
    fun testGetRangeToEndOfLong() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runSuspendingTest {
            val testFile = system.root.then("range-to-end-of-long.txt")
            val message = "0123456789"
            testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
            try {
                assertEquals(message, testFile.getRange(0L..Long.MAX_VALUE)!!.data.text())
                assertEquals("456789", testFile.getRange(4L..Long.MAX_VALUE)!!.data.text())
            } finally {
                testFile.delete()
            }
        }
    }

    @Test
    fun testGetRangeOfEmptyFile() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runSuspendingTest {
            val testFile = system.root.then("range-empty.txt")
            testFile.put(TypedData(Data.Bytes(ByteArray(0)), MediaType.Text.Plain))
            try {
                val empty = testFile.getRange(0L..15L)!!
                assertEquals("", empty.data.text())
                assertEquals(0L, empty.data.size)
            } finally {
                testFile.delete()
            }
        }
    }

    @Test
    fun testGetRangeOfMissingFile() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runSuspendingTest {
            assertNull(system.root.then("range-does-not-exist.txt").getRange(0L..15L))
        }
    }

    @Test
    fun testGetRangeRejectsNonsensicalRange() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runSuspendingTest {
            val testFile = system.root.then("range-invalid.txt")
            testFile.put(TypedData(Data.Text("0123456789"), MediaType.Text.Plain))
            try {
                assertFailsWith<IllegalArgumentException> { testFile.getRange(-1L..5L) }
                assertFailsWith<IllegalArgumentException> { testFile.getRange(5L..1L) }
            } finally {
                testFile.delete()
            }
        }
    }

    /**
     * The canonical `sf://<name>/<path>` form is what gets persisted to a database, and it must
     * round-trip back to the same file through [ExternalFile.Parser] (the path the file serializer
     * takes when reading a stored value).
     */
    @Test
    fun testCanonicalRestoration() = runSuspendingTest {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return@runSuspendingTest
        }
        val parser = ExternalFile.Parser(listOf(system))
        for (file in listOf(
            system.root.then("folder/test.txt"),
            system.root.then("folder/file with spaces & odd ?#[]()@*,;= chars.txt"),
            system.root,
        )) {
            val canonical = file.serverFile.location
            println(canonical)
            assertTrue(canonical.startsWith("sf://"), "Persisted reference should be canonical, was '$canonical'")
            assertEquals(file, parser.parse(canonical))
        }
    }

    @Test
    fun testRemoteRestoration() = runSuspendingTest {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return@runSuspendingTest
        }
        val file = system.root.then("test.txt")
        println(file)
        assertEquals(file, system.parseExternalUrl(file.signedUrl.also { println(it) }))
    }

    @Test
    fun testInfo() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runSuspendingTest {
            val testFile = system.root.then("test.txt")
            val secondFile = system.root.then("test/secondTest.txt")
            val message = "Hello world!"
            try {
                val beforeModify = Clock.default().now().minus(120.seconds)
                testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
                val info = testFile.head()
                assertNotNull(info)
                assertEquals(MediaType.Text.Plain, info.type)
                assertTrue(info.size > 0L.bytes)
                assertTrue(info.lastModified == null || info.lastModified!! > beforeModify)

                // Testing with sub folders.
                val secondMessage = "Hello Second world!"
                val secondBeforeModify = Clock.default().now().minus(120.seconds)
                secondFile.put(TypedData(Data.Text(secondMessage), MediaType.Text.Plain))
                val secondInfo = secondFile.head()
                assertNotNull(secondInfo)
                assertEquals(MediaType.Text.Plain, secondInfo.type)
                assertTrue(secondInfo.size > 0L.bytes)
                assertTrue(secondInfo.lastModified == null || secondInfo.lastModified!! > secondBeforeModify)
            } finally {
                testFile.delete()
                secondFile.delete()
            }
        }
    }

    @Test
    fun testList() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runSuspendingTest {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10.seconds) {
                    val testFile = system.root.then("test.txt")
                    val testFileNotIncluded = system.root.then("doNotInclude/test.txt")
                    val message = "Hello world!"
                    try {
                        testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
                        testFileNotIncluded.put(TypedData(Data.Text(message), MediaType.Text.Plain))
                        assertContains(testFile.parent!!.list().also { println(it) }, testFile)
                        assertFalse(testFileNotIncluded in testFile.parent!!.list())
                        testFile.get()!!.data.text()
                    } finally {
                        testFile.delete()
                        testFileNotIncluded.delete()
                    }
                }
            }
        }
    }

    @Test
    open fun testSignedUrlAccess() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runSuspendingTest {
            val testFile = system.root.then("test.txt")
            val message = "Hello world!"
            try {
                testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
                assertEquals(testFile, system.parseExternalUrl(testFile.signedUrl))
                println("testfile.signedUrl: ${testFile.signedUrl}")
                assertTrue(client.get(testFile.signedUrl).status.isSuccess())
            } finally {
                testFile.delete()
            }
        }
        runSuspendingTest {
            val testFile = system.root.then("fileWithSpecialCharacters ?#[]()@*,;=.txt")
            val message = "Hello world!"
            try {
                testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
                assertEquals(testFile, system.parseExternalUrl(testFile.signedUrl))
                println("testfile.signedUrl: ${testFile.signedUrl}")
                assertTrue(client.get(testFile.signedUrl).status.isSuccess())
            } finally {
                testFile.delete()
            }
        }
        runSuspendingTest {
            val testFile = system.root.then("folder/fileWithSpecialCharacters ?#[]()@*,;=.txt")
            val message = "Hello world!"
            try {
                testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
                assertEquals(testFile, system.parseExternalUrl(testFile.signedUrl))
                println("testfile.signedUrl: ${testFile.signedUrl}")
                assertTrue(client.get(testFile.signedUrl).status.isSuccess())
            } finally {
                testFile.delete()
            }
        }
    }

    open fun uploadHeaders(builder: HttpRequestBuilder) {}

    @Test
    open fun testSignedUpload() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runSuspendingTest {
            val testFile = system.root.then("test.txt")
            val message = "Hello world!"
            try {
                assertTrue(client.put(testFile.uploadUrl(1.hours)) {
                    uploadHeaders(this)
                    setBody(TextContent(message, ContentType.Text.Plain))
                }.status.isSuccess())
            } finally {
                testFile.delete()
            }
        }
    }
}
