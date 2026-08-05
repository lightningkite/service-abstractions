package com.lightningkite.services.files.clamav

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.FileScanException
import com.lightningkite.services.files.FileScanner
import com.lightningkite.services.files.clamav.ClamAvFileScanner.Companion.clamav
import com.lightningkite.services.files.scan
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.capybara.clamav.ClamavClient
import xyz.capybara.clamav.Platform
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for FIX 40: [ClamAvFileScanner.scan] must fail within its configured timeout
 * rather than hang forever when clamd accepts the TCP connection but never responds - the
 * `clamav-client` library opens a plain blocking socket with no read timeout of its own.
 */
class ClamAvTimeoutTest {
    @Test
    fun scanFailsWithinTimeoutOnUnresponsiveDaemon(): Unit = runBlocking {
        // Accepts the connection (so the socket connect succeeds) but never writes a response,
        // simulating an overloaded or wedged clamd.
        val serverSocket = ServerSocket(0)
        val acceptThread = Thread { runCatching { serverSocket.accept() } }.apply { isDaemon = true; start() }
        try {
            val scanner = ClamAvFileScanner(
                name = "test",
                context = TestSettingContext(),
                get = { ClamavClient("127.0.0.1", serverSocket.localPort, Platform.JVM_PLATFORM) },
                scanTimeout = 500.milliseconds,
            )

            // Outer bound proves scan() actually returns (with a failure) instead of hanging forever -
            // without FIX 40, this would never complete, since clamd never responds and the underlying
            // socket read has no timeout of its own.
            withTimeout(5.seconds) {
                assertFailsWith<FileScanException> {
                    scanner.scan(TypedData.text("hello", MediaType.Text.Plain))
                }
            }
        } finally {
            serverSocket.close()
        }
    }

    /**
     * The [clamav] Settings helper encodes [scanTimeout] as a `timeoutSeconds` query parameter, and
     * the platform segment in the URL regex must stop before that `?` rather than swallowing it -
     * otherwise `Platform.valueOf(...)` would throw on the un-parsed "UNIX?timeoutSeconds=30" string.
     */
    @Test
    fun settingsUrlWithTimeoutParamParsesCorrectly() {
        val settings = FileScanner.Settings.Companion.clamav(
            host = "localhost",
            port = 3310,
            platform = Platform.UNIX,
            scanTimeout = 45.seconds,
        )
        // Must not throw - constructing the scanner resolves the "clamav" URL scheme end to end.
        settings("test", TestSettingContext())
    }
}
