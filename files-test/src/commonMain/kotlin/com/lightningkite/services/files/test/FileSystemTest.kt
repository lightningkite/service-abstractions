package com.lightningkite.services.files.test

import com.lightningkite.MediaType
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.default
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.http.client
import com.lightningkite.services.test.runTestWithClock
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

abstract class FileSystemTests {
    abstract val system: PublicFileSystem?
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
            assertEquals(message, testFile.get()!!.data.text())
            testFile.delete()
        }
    }
    @Test fun testLocalRestoration() = runSuspendingTest {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return@runSuspendingTest
        }
        val file = system.root.then("test.txt")
        println(file)
        assertEquals(file, system.parseInternalUrl(file.url.also { println(it) }))
    }
    @Test fun testRemoteRestoration() = runSuspendingTest {
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
            val message = "Hello world!"
            val beforeModify = Clock.default().now().minus(120.seconds)
            testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
            val info = testFile.head()
            assertNotNull(info)
            assertEquals(MediaType.Text.Plain, info.type)
            assertTrue(info.size > 0L)
            assertTrue(info.lastModified == null || info.lastModified!! > beforeModify)

            // Testing with sub folders.
            val secondFile = system.root.then("test/secondTest.txt")
            val secondMessage = "Hello Second world!"
            val secondBeforeModify = Clock.default().now().minus(120.seconds)
            secondFile.put(TypedData(Data.Text(secondMessage), MediaType.Text.Plain))
            val secondInfo = secondFile.head()
            assertNotNull(secondInfo)
            assertEquals(MediaType.Text.Plain, secondInfo.type)
            assertTrue(secondInfo.size > 0L)
            assertTrue(secondInfo.lastModified == null || secondInfo.lastModified!! > secondBeforeModify)
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
                withTimeout(10_000L) {
                    val testFile = system.root.then("test.txt")
                    val message = "Hello world!"
                    testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
                    val testFileNotIncluded = system.root.then("doNotInclude/test.txt")
                    testFileNotIncluded.put(TypedData(Data.Text(message), MediaType.Text.Plain))
                    assertContains(testFile.parent!!.list()!!.also { println(it) }, testFile)
                    assertFalse(testFileNotIncluded in testFile.parent!!.list()!!)
                    testFile.get()!!.data.text()
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
            testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
            assertTrue(testFile.signedUrl.startsWith(testFile.url))
            println("testFile.signedUrl: ${testFile.signedUrl}")
            assertTrue(client.get(testFile.signedUrl).status.isSuccess())
        }
        // TODO: Reenable and figure this out
//        runSuspendingTest {
//            val testFile = system.root.then("test with spaces.txt")
//            val message = "Hello world!"
//            testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
//            assertTrue(testFile.signedUrl.startsWith(testFile.url))
//            println("testFile.signedUrl: ${testFile.signedUrl}")
//            assertTrue(client.get(testFile.signedUrl).status.isSuccess())
//        }
//        runSuspendingTest {
//            val testFile = system.root.then("folder/test with spaces.txt")
//            val message = "Hello world!"
//            testFile.put(TypedData(Data.Text(message), MediaType.Text.Plain))
//            assertTrue(testFile.signedUrl.startsWith(testFile.url))
//            println("testFile.signedUrl: ${testFile.signedUrl}")
//            assertTrue(client.get(testFile.signedUrl).status.isSuccess())
//        }
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
            assertTrue(client.put(testFile.uploadUrl(1.hours)) {
                uploadHeaders(this)
                setBody(TextContent(message, ContentType.Text.Plain))
            }.status.isSuccess())
        }
    }
}
